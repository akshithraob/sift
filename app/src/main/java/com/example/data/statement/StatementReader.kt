package com.example.data.statement

import android.content.Context
import android.net.Uri
import com.example.parser.ParsedMandateCandidate
import com.example.parser.RecurringDetector
import com.example.parser.StatementParser
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.UUID

class StatementReader(
    private val context: Context,
    private val parser: StatementParser,
    private val recurringDetector: RecurringDetector
) {

    init {
        try {
            PDFBoxResourceLoader.init(context)
        } catch (_: Throwable) {
        }
    }

    suspend fun processStatementFile(uri: Uri, password: String? = null): List<ParsedMandateCandidate> = withContext(Dispatchers.IO) {
        val tempFile = createTempCacheFile(uri)
        try {
            if (tempFile == null || !tempFile.exists()) {
                return@withContext emptyList()
            }

            val filename = uri.lastPathSegment ?: tempFile.name
            val isPdf = filename.endsWith(".pdf", ignoreCase = true) || hasPdfHeader(tempFile)

            val lines = if (isPdf) {
                extractLinesFromPdf(tempFile, password)
            } else {
                extractLinesFromCsv(tempFile)
            }

            // Parse raw transaction lines
            val rawTransactions = parser.parseStatementLines(lines)

            // Detect recurring debits
            val candidates = recurringDetector.detectRecurring(rawTransactions)

            // Core Privacy Principle: raw transaction lines are discarded and never kept or stored
            candidates
        } finally {
            // Delete temp file immediately after processing
            tempFile?.delete()
        }
    }

    private fun extractLinesFromPdf(file: File, password: String?): List<String> {
        var document: PDDocument? = null
        return try {
            document = if (password.isNullOrBlank()) {
                PDDocument.load(file)
            } else {
                PDDocument.load(file, password)
            }

            val stripper = PDFTextStripper()
            val text = stripper.getText(document)
            text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        } finally {
            try {
                document?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun extractLinesFromCsv(file: File): List<String> {
        return try {
            file.bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun hasPdfHeader(file: File): Boolean {
        return try {
            val header = ByteArray(4)
            file.inputStream().use { it.read(header) }
            header.contentEquals("%PDF".toByteArray())
        } catch (_: Exception) {
            false
        }
    }

    private fun createTempCacheFile(uri: Uri): File? {
        val tempFile = File(context.cacheDir, "stmt_${UUID.randomUUID()}.tmp")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            tempFile
        } catch (_: Exception) {
            // A partial copy must not linger in the cache
            tempFile.delete()
            null
        }
    }
}

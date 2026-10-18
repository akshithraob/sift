package com.leakproof.app.data.ocr

import android.content.Context
import android.net.Uri
import com.leakproof.app.parser.MandateScreenshotParser
import com.leakproof.app.parser.OcrBoundingBox
import com.leakproof.app.parser.OcrDocument
import com.leakproof.app.parser.OcrTextBlock
import com.leakproof.app.parser.OcrTextLine
import com.leakproof.app.parser.ParsedMandateCandidate
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MlKitOcrScanner(
    private val context: Context,
    private val parser: MandateScreenshotParser
) {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun processScreenshots(uris: List<Uri>): List<ParsedMandateCandidate> = withContext(Dispatchers.IO) {
        val allCandidates = mutableListOf<ParsedMandateCandidate>()

        for (uri in uris) {
            val tempFile = createTempCacheFile(uri)
            try {
                if (tempFile != null && tempFile.exists()) {
                    val inputImage = InputImage.fromFilePath(context, Uri.fromFile(tempFile))
                    val ocrDoc = runTextRecognition(inputImage)
                    val candidates = parser.parseOcrDocument(ocrDoc)
                    allCandidates.addAll(candidates)
                }
            } catch (e: Exception) {
                // If OCR fails on one image, continue gracefully with others
            } finally {
                // Core Privacy Principle: user files never remain on device
                tempFile?.delete()
            }
        }

        // Merge duplicates across overlapping screenshots
        parser.mergeDuplicates(allCandidates)
    }

    private suspend fun runTextRecognition(image: InputImage): OcrDocument =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val blocks = visionText.textBlocks.map { block ->
                        val bBox = block.boundingBox
                        val ocrBBox = if (bBox != null) {
                            OcrBoundingBox(bBox.left, bBox.top, bBox.right, bBox.bottom)
                        } else null

                        val lines = block.lines.map { line ->
                            val lBox = line.boundingBox
                            val ocrLBox = if (lBox != null) {
                                OcrBoundingBox(lBox.left, lBox.top, lBox.right, lBox.bottom)
                            } else null
                            OcrTextLine(text = line.text, boundingBox = ocrLBox)
                        }

                        OcrTextBlock(
                            text = block.text,
                            boundingBox = ocrBBox,
                            lines = lines
                        )
                    }
                    continuation.resume(OcrDocument(blocks))
                }
                .addOnFailureListener { exception ->
                    continuation.resumeWithException(exception)
                }
        }

    private fun createTempCacheFile(uri: Uri): File? {
        val tempFile = File(context.cacheDir, "ocr_${UUID.randomUUID()}.tmp")
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

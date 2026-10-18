package com.example.backup

import com.example.data.local.SubscriptionEntity
import com.example.insights.normalizeName
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase-protected backups for people who lose or replace their phone (system backup is off).
 * Everything here is pure Kotlin: the caller reads and writes the file the user picked.
 * The passphrase is never stored or logged; there is no recovery if it is forgotten.
 */

class WrongPassphraseException : Exception("Wrong passphrase, or the file was modified")
class InvalidBackupException(message: String) : Exception(message)

object BackupCrypto {
    private val MAGIC = "STBK1".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    const val ITERATIONS = 210_000
    private const val MIN_ITERATIONS = 100_000
    private const val MAX_ITERATIONS = 2_000_000
    private const val HEADER_BYTES = 5 + 4 + SALT_BYTES // magic + iterations + salt

    const val MIN_PASSPHRASE_LENGTH = 8

    fun encrypt(plain: ByteArray, passphrase: CharArray, random: SecureRandom = SecureRandom()): ByteArray {
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val header = MAGIC + intToBytes(ITERATIONS) + salt

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, ITERATIONS), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(header) // binds the header, so tampering with salt/iterations is detected
        return header + iv + cipher.doFinal(plain)
    }

    /** @throws WrongPassphraseException for a bad passphrase or a modified file */
    fun decrypt(blob: ByteArray, passphrase: CharArray): ByteArray {
        if (blob.size < HEADER_BYTES + IV_BYTES + TAG_BITS / 8 ||
            !blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
        ) {
            throw InvalidBackupException("This is not a SubTrack backup file")
        }
        val iterations = bytesToInt(blob, MAGIC.size)
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) {
            throw InvalidBackupException("This backup file is damaged")
        }
        val salt = blob.copyOfRange(MAGIC.size + 4, HEADER_BYTES)
        val iv = blob.copyOfRange(HEADER_BYTES, HEADER_BYTES + IV_BYTES)
        val cipherText = blob.copyOfRange(HEADER_BYTES + IV_BYTES, blob.size)

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(blob.copyOfRange(0, HEADER_BYTES))
            cipher.doFinal(cipherText)
        } catch (_: AEADBadTagException) {
            throw WrongPassphraseException()
        } catch (e: GeneralSecurityException) {
            throw InvalidBackupException("This backup file is damaged")
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun intToBytes(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun bytesToInt(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0xFF) shl 24) or ((b[offset + 1].toInt() and 0xFF) shl 16) or
            ((b[offset + 2].toInt() and 0xFF) shl 8) or (b[offset + 3].toInt() and 0xFF)
}

data class BackupContents(
    val subscriptions: List<SubscriptionEntity>,
    val monthlyBudget: Double,
    val createdAtMs: Long
)

object BackupCodec {
    private const val FORMAT = "subtrack-backup"
    private const val VERSION = 2

    fun encode(subscriptions: List<SubscriptionEntity>, monthlyBudget: Double, nowMs: Long = System.currentTimeMillis()): ByteArray {
        val array = JSONArray()
        for (s in subscriptions) {
            array.put(
                JSONObject()
                    .put("merchant", s.merchant)
                    .put("amount", s.amount)
                    .put("frequency", s.frequency)
                    .put("nextDate", s.nextDate)
                    .put("nextDateEpochMs", s.nextDateEpochMs)
                    .put("rail", s.rail)
                    .put("source", s.source)
                    .put("last4", s.last4 ?: JSONObject.NULL)
                    .put("status", s.status)
                    .put("userConfirmed", s.userConfirmed)
                    .put("notes", s.notes ?: JSONObject.NULL)
                    .put("maxAmount", s.maxAmount ?: JSONObject.NULL)
                    .put("trialEndEpochMs", s.trialEndEpochMs ?: JSONObject.NULL)
                    .put("lastSeenAtMs", s.lastSeenAtMs)
            )
        }
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("createdAt", nowMs)
            .put("monthlyBudget", monthlyBudget)
            .put("subscriptions", array)
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): BackupContents {
        try {
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            if (root.optString("format") != FORMAT) throw InvalidBackupException("This is not a SubTrack backup file")
            if (root.optInt("version", 0) > VERSION) {
                throw InvalidBackupException("This backup was made by a newer version of SubTrack. Update the app and try again.")
            }
            val array = root.getJSONArray("subscriptions")
            val subs = (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                SubscriptionEntity(
                    id = 0, // always a fresh row on restore
                    merchant = o.getString("merchant"),
                    amount = o.getDouble("amount"),
                    frequency = o.getString("frequency"),
                    nextDate = o.optString("nextDate", "Upcoming"),
                    nextDateEpochMs = o.optLong("nextDateEpochMs", 0L),
                    rail = o.optString("rail", "OTHER"),
                    source = o.optString("source", "MANUAL"),
                    last4 = o.optString("last4", "").ifBlank { null }?.takeLast(4),
                    status = o.optString("status", "ACTIVE"),
                    userConfirmed = o.optBoolean("userConfirmed", true),
                    notes = if (o.isNull("notes")) null else o.optString("notes").ifBlank { null },
                    maxAmount = if (o.isNull("maxAmount")) null else o.optDouble("maxAmount"),
                    trialEndEpochMs = if (o.isNull("trialEndEpochMs")) null else o.optLong("trialEndEpochMs"),
                    lastSeenAtMs = o.optLong("lastSeenAtMs", 0L)
                )
            }
            return BackupContents(subs, root.optDouble("monthlyBudget", 0.0).let { if (it.isNaN()) 0.0 else it }, root.optLong("createdAt", 0L))
        } catch (e: JSONException) {
            throw InvalidBackupException("This backup file is damaged")
        }
    }
}

data class RestorePlan(val toInsert: List<SubscriptionEntity>, val skipped: Int)

object BackupMerge {
    private fun key(s: SubscriptionEntity) =
        "${normalizeName(s.merchant)}|${"%.2f".format(java.util.Locale.ROOT, s.amount)}|${s.frequency.uppercase()}"

    /** Restore adds what is missing and never overwrites or removes what is already tracked. */
    fun plan(existing: List<SubscriptionEntity>, incoming: List<SubscriptionEntity>): RestorePlan {
        val seen = existing.map { key(it) }.toMutableSet()
        val insert = mutableListOf<SubscriptionEntity>()
        var skipped = 0
        for (sub in incoming) {
            if (seen.add(key(sub))) insert.add(sub.copy(id = 0)) else skipped++
        }
        return RestorePlan(insert, skipped)
    }
}

object CsvExport {
    private val header = listOf(
        "Merchant", "Amount (INR)", "Frequency", "Next renewal", "Payment rail", "Status",
        "Source", "Account last 4", "Trial ends", "Mandate limit (INR)", "Notes"
    )

    fun toCsv(subscriptions: List<SubscriptionEntity>): String {
        val fmt = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.ENGLISH)
        val sb = StringBuilder()
        sb.append(header.joinToString(",") { escape(it) }).append("\r\n")
        for (s in subscriptions) {
            sb.append(
                listOf(
                    s.merchant,
                    "%.2f".format(java.util.Locale.ROOT, s.amount),
                    s.frequency,
                    s.nextDate,
                    s.rail,
                    s.status,
                    s.source,
                    s.last4.orEmpty(),
                    s.trialEndEpochMs?.let { fmt.format(java.util.Date(it)) }.orEmpty(),
                    s.maxAmount?.let { "%.2f".format(java.util.Locale.ROOT, it) }.orEmpty(),
                    s.notes.orEmpty()
                ).joinToString(",") { escape(it) }
            ).append("\r\n")
        }
        return sb.toString()
    }

    // Quote fields, and neutralise cells a spreadsheet would run as a formula
    internal fun escape(raw: String): String {
        var v = raw
        if (v.isNotEmpty() && v[0] in charArrayOf('=', '+', '-', '@', '\t', '\r')) v = "'$v"
        return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }
}

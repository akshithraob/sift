package com.leakproof.app

import com.leakproof.app.backup.BackupCodec
import com.leakproof.app.backup.BackupCrypto
import com.leakproof.app.backup.BackupMerge
import com.leakproof.app.backup.CsvExport
import com.leakproof.app.backup.InvalidBackupException
import com.leakproof.app.backup.WrongPassphraseException
import com.leakproof.app.data.local.SubscriptionEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupTest {

    private fun sub(
        merchant: String,
        amount: Double,
        id: Long = 0,
        frequency: String = "MONTHLY",
        notes: String? = null,
        maxAmount: Double? = null,
        trialEnd: Long? = null
    ) = SubscriptionEntity(
        id = id, merchant = merchant, amount = amount, frequency = frequency, nextDate = "20 Oct 2026",
        nextDateEpochMs = 1_792_000_000_000L, rail = "UPI", source = "SCREENSHOT", last4 = "4821",
        status = "ACTIVE", notes = notes, maxAmount = maxAmount, trialEndEpochMs = trialEnd, lastSeenAtMs = 123L
    )

    // --- Crypto

    @Test
    fun crypto_roundTrip() {
        val plain = "hello subscriptions".toByteArray()
        val blob = BackupCrypto.encrypt(plain, "correct horse battery".toCharArray())

        assertArrayEquals(plain, BackupCrypto.decrypt(blob, "correct horse battery".toCharArray()))
        // The plaintext must not appear in the file
        assertFalse(String(blob, Charsets.ISO_8859_1).contains("hello subscriptions"))
    }

    @Test
    fun crypto_wrongPassphraseIsRejected() {
        val blob = BackupCrypto.encrypt("secret".toByteArray(), "correct horse battery".toCharArray())

        try {
            BackupCrypto.decrypt(blob, "wrong passphrase!".toCharArray())
            fail("expected WrongPassphraseException")
        } catch (_: WrongPassphraseException) {
        }
    }

    @Test
    fun crypto_tamperingWithAnyPartIsDetected() {
        val pass = "correct horse battery".toCharArray()
        val blob = BackupCrypto.encrypt("secret data".toByteArray(), pass)

        // Flip a byte in the ciphertext, the salt and the tag (last byte)
        for (index in listOf(blob.size - 1, blob.size - 20, 12)) {
            val tampered = blob.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            try {
                BackupCrypto.decrypt(tampered, pass)
                fail("expected failure for modified byte $index")
            } catch (_: WrongPassphraseException) {
            }
        }
    }

    @Test
    fun crypto_eachBackupUsesFreshSaltAndIv() {
        val pass = "correct horse battery".toCharArray()
        val a = BackupCrypto.encrypt("same".toByteArray(), pass)
        val b = BackupCrypto.encrypt("same".toByteArray(), pass)

        assertFalse(a.contentEquals(b))
    }

    @Test
    fun crypto_garbageIsNotABackup() {
        for (junk in listOf(ByteArray(0), "not a backup at all, just some text bytes".toByteArray(), ByteArray(200)))
            try {
                BackupCrypto.decrypt(junk, "whatever123".toCharArray())
                fail("expected InvalidBackupException")
            } catch (_: InvalidBackupException) {
            }
    }

    @Test
    fun readAtMost_stopsOneByteBeyondTheLimit() {
        val data = ByteArray(100) { it.toByte() }

        assertEquals(100, com.leakproof.app.backup.readAtMost(data.inputStream(), 100).size) // exactly at the limit
        assertEquals(51, com.leakproof.app.backup.readAtMost(data.inputStream(), 50).size)   // limit + 1 signals "too big"
        assertEquals(0, com.leakproof.app.backup.readAtMost(ByteArray(0).inputStream(), 50).size)
    }

    // --- Codec

    @Test
    fun codec_roundTripKeepsEveryField() {
        val original = listOf(
            sub("Claude", 1999.0, id = 7, notes = "Pro plan, \"annual\" later", maxAmount = 1999.0, trialEnd = 1_793_000_000_000L),
            sub("Netflix India", 649.0)
        )
        val decoded = BackupCodec.decode(BackupCodec.encode(original, 10000.0, nowMs = 42L))

        assertEquals(10000.0, decoded.monthlyBudget, 0.0)
        assertEquals(42L, decoded.createdAtMs)
        assertEquals(original.map { it.copy(id = 0) }, decoded.subscriptions)
        assertNull(decoded.subscriptions[1].notes)
        assertNull(decoded.subscriptions[1].maxAmount)
        assertNull(decoded.subscriptions[1].trialEndEpochMs)
    }

    @Test
    fun codec_rejectsOtherJsonAndNewerVersions() {
        for (bad in listOf("""{"hello":"world"}""", "not json", """{"format":"leakproof-backup","version":99,"subscriptions":[]}""")) {
            try {
                BackupCodec.decode(bad.toByteArray())
                fail("expected InvalidBackupException for $bad")
            } catch (_: InvalidBackupException) {
            }
        }
    }

    @Test
    fun endToEnd_encryptedBackupRestoresSameData() {
        val subs = listOf(sub("Spotify Premium", 119.0), sub("Claude", 1999.0, maxAmount = 1999.0))
        val pass = "a long passphrase".toCharArray()

        val blob = BackupCrypto.encrypt(BackupCodec.encode(subs, 5000.0), pass)
        val restored = BackupCodec.decode(BackupCrypto.decrypt(blob, pass))

        assertEquals(subs, restored.subscriptions)
        assertEquals(5000.0, restored.monthlyBudget, 0.0)
    }

    // --- Merge

    @Test
    fun merge_addsMissingAndSkipsWhatIsAlreadyTracked() {
        val existing = listOf(sub("Netflix", 649.0, id = 1))
        val incoming = listOf(sub("Netflix India", 649.0), sub("Spotify", 119.0), sub("Spotify", 119.0))
        val plan = BackupMerge.plan(existing, incoming)

        assertEquals(listOf("Spotify"), plan.toInsert.map { it.merchant })
        assertEquals(2, plan.skipped)
        assertTrue(plan.toInsert.all { it.id == 0L })
    }

    @Test
    fun merge_differentPriceCountsAsDifferentEntry() {
        val plan = BackupMerge.plan(listOf(sub("Netflix", 649.0, id = 1)), listOf(sub("Netflix", 799.0)))

        assertEquals(1, plan.toInsert.size)
    }

    @Test
    fun merge_intoEmptyRestoresEverything() {
        val plan = BackupMerge.plan(emptyList(), listOf(sub("A", 1.0), sub("B", 2.0)))

        assertEquals(2, plan.toInsert.size)
        assertEquals(0, plan.skipped)
    }

    // --- CSV

    @Test
    fun csv_quotesFieldsAndNeutralisesFormulas() {
        val csv = CsvExport.toCsv(
            listOf(
                sub("=HYPERLINK(\"http://evil\")", 100.0, notes = "line1\nline2, with comma"),
                sub("Claude", 1999.0, maxAmount = 1999.0)
            )
        )
        val lines = csv.split("\r\n")

        assertEquals("Merchant,Amount (INR),Frequency,Next renewal,Payment rail,Status,Source,Account last 4,Trial ends,Mandate limit (INR),Notes,Type,Instalments paid,Instalments total,Ends,Interest rate (%)", lines[0])
        // The formula is prefixed with a quote so spreadsheets treat it as text
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"http://evil\"\")\""))
        assertTrue(csv.contains("\"line1\nline2, with comma\""))
        assertTrue(csv.contains("Claude,1999.00,MONTHLY,20 Oct 2026,UPI,ACTIVE,SCREENSHOT,4821,,1999.00,,SUBSCRIPTION"))
    }
}

package com.leakproof.app

import com.leakproof.app.security.TrustReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustReportTest {
    @Test
    fun cleanBuildPassesEveryCheck() {
        val checks = TrustReport.build(listOf("android.permission.USE_BIOMETRIC", "android.permission.POST_NOTIFICATIONS"), false, false, false)
        assertEquals(5, checks.size)
        assertTrue(checks.all { it.passed })
    }

    @Test
    fun internetPermissionFailsTheNetworkCheck() {
        val checks = TrustReport.build(listOf(TrustReport.INTERNET), false, false, false)
        assertFalse(checks.first { it.title.startsWith("No internet") }.passed)
    }

    @Test
    fun sensitivePermissionsAreNamed() {
        val check = TrustReport.build(listOf("android.permission.READ_SMS"), false, false, false)[1]
        assertFalse(check.passed)
        assertTrue(check.detail.contains("read sms"))
    }

    @Test
    fun backupDebuggableAndCaptureFlagsAreReported() {
        val checks = TrustReport.build(emptyList(), true, true, true)
        assertEquals(listOf(true, true, false, false, false), checks.map { it.passed })
    }

    @Test
    fun fingerprintIsColonSeparatedSha256() {
        val fp = TrustReport.fingerprint(ByteArray(0))
        assertEquals(32, fp.split(":").size)
        assertTrue(fp.startsWith("E3:B0:C4:42:98:FC:1C:14"))
    }
}

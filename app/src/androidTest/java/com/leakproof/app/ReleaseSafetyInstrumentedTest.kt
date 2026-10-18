package com.leakproof.app

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks, on a real device or emulator, the promises the app makes to users:
 * it is installed under the right id, can never use the network, does not allow system backups,
 * and exports only the launcher activity.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseSafetyInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val packageInfo
        get() = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES
        )

    @Test
    fun installedUnderTheExpectedPackage() {
        assertEquals("com.leakproof.app", context.packageName)
    }

    @Test
    fun appCannotUseTheNetwork() {
        val requested = packageInfo.requestedPermissions?.toList().orEmpty()

        assertFalse("INTERNET must never be requested", requested.contains("android.permission.INTERNET"))
        assertEquals(
            "App must not be granted network access",
            PackageManager.PERMISSION_DENIED,
            context.packageManager.checkPermission("android.permission.INTERNET", context.packageName)
        )
    }

    @Test
    fun systemBackupIsDisabled() {
        val flags = context.applicationInfo.flags
        assertEquals(0, flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test
    fun onlyTheLauncherActivityIsExported() {
        val exported = packageInfo.activities.orEmpty().filter { it.exported }.map { it.name }

        assertEquals(listOf("com.leakproof.app.MainActivity"), exported)
    }

    @Test
    fun launcherIntentResolvesToTheApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull(intent)
        assertTrue(intent!!.component?.className?.endsWith("MainActivity") == true)
    }
}

package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Uses a plain Application: SubTrackApplication opens the SQLCipher database, whose native
// library is unavailable on the JVM. Launching MainActivity is covered by androidTest instead.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("SubTrack", appName)
    }
}

package com.leakproof.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.isSystemInDarkTheme
import android.graphics.Color
import androidx.activity.SystemBarStyle
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leakproof.app.ui.navigation.LeakproofNavGraph
import com.leakproof.app.ui.theme.LeakproofTheme
import com.leakproof.app.ui.viewmodel.LeakproofViewModel

class MainActivity : FragmentActivity() {

    private val viewModel: LeakproofViewModel by viewModels()

    override fun onStop() {
        super.onStop()
        viewModel.onAppBackgrounded()
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForegrounded()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Strict Security: Block screenshots and screen recording in release builds
        // Note: In debug builds on the browser streaming emulator, FLAG_SECURE blacks out the canvas
        if (!BuildConfig.ALLOW_SCREEN_CAPTURE) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val dark = when (themeMode) {
                1 -> false
                2 -> true
                else -> systemDark
            }
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                )
                onDispose {}
            }
            LeakproofTheme(themeMode = themeMode) {
                LeakproofNavGraph(viewModel = viewModel)
            }
        }
    }
}

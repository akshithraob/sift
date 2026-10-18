package com.leakproof.app

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
        if (!BuildConfig.DEBUG) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        enableEdgeToEdge()

        setContent {
            LeakproofTheme {
                                LeakproofNavGraph(viewModel = viewModel)
            }
        }
    }
}

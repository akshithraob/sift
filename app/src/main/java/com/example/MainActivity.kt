package com.example

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.navigation.SubTrackNavGraph
import com.example.ui.theme.SubTrackTheme
import com.example.ui.viewmodel.SubTrackViewModel

class MainActivity : FragmentActivity() {

    private val viewModel: SubTrackViewModel by viewModels()

    override fun onStop() {
        super.onStop()
        viewModel.onAppBackgrounded()
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForegrounded()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
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
            SubTrackTheme {
                                SubTrackNavGraph(viewModel = viewModel)
            }
        }
    }
}

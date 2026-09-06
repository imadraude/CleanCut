package com.cleancut.bgremover.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.cleancut.bgremover.ui.screen.MainScreen
import com.cleancut.bgremover.ui.theme.CleanCutTheme
import com.cleancut.bgremover.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            CleanCutTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }
}

package ru.hznik.devicebridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import ru.hznik.devicebridge.app.DeviceBridgeApp
import ru.hznik.devicebridge.ui.theme.DeviceBridgeTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DeviceBridgeTheme {
                DeviceBridgeApp()
            }
        }
    }
}

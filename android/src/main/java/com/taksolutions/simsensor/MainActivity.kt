package com.taksolutions.simsensor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.taksolutions.simsensor.connection.OkHttpServerConnection
import com.taksolutions.simsensor.settings.SharedPreferencesSettingsStore
import com.taksolutions.simsensor.ui.DetectorScreen
import com.taksolutions.simsensor.ui.SimSensorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val factory = viewModelFactory {
            initializer {
                val settings = SharedPreferencesSettingsStore(applicationContext)
                MainViewModel(
                    settings = settings,
                    connection = OkHttpServerConnection(
                        appVersion = BuildConfig.VERSION_NAME,
                        deviceId = { settings.deviceId },
                    ),
                )
            }
        }

        setContent {
            val vm: MainViewModel = viewModel(factory = factory)
            SimSensorTheme {
                DetectorScreen(
                    connectionState = vm.connectionState.collectAsStateWithLifecycle().value,
                    serverUrl = vm.serverUrl.collectAsStateWithLifecycle().value,
                    appVersion = BuildConfig.VERSION_NAME,
                    onServerUrlChanged = vm::onServerUrlChanged,
                    onConnect = vm::connect,
                    onDisconnect = vm::disconnect,
                )
            }
        }
    }
}

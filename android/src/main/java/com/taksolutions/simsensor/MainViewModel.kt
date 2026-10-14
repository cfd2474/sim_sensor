package com.taksolutions.simsensor

import androidx.lifecycle.ViewModel
import com.taksolutions.simsensor.connection.ConnectionState
import com.taksolutions.simsensor.connection.ServerConnection
import com.taksolutions.simsensor.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(
    private val settings: SettingsStore,
    private val connection: ServerConnection,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = connection.state

    private val _serverUrl = MutableStateFlow(settings.serverUrl)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    fun onServerUrlChanged(value: String) {
        _serverUrl.value = value
    }

    fun connect() {
        settings.serverUrl = _serverUrl.value
        connection.connect(_serverUrl.value)
    }

    fun disconnect() = connection.disconnect()

    override fun onCleared() {
        connection.disconnect()
    }
}

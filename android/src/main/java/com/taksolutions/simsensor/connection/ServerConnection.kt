package com.taksolutions.simsensor.connection

import kotlinx.coroutines.flow.StateFlow

/** State of the link to the Sim Sensor server. */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val serverVersion: String, val clockOffsetMillis: Long) : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}

/** The device's link to the server. Implementations own the transport. */
interface ServerConnection {
    val state: StateFlow<ConnectionState>
    fun connect(serverUrl: String)
    fun disconnect()
}

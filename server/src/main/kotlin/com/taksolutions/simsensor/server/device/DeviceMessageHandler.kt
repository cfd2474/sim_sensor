package com.taksolutions.simsensor.server.device

import com.taksolutions.simsensor.protocol.ErrorMessage
import com.taksolutions.simsensor.protocol.Hello
import com.taksolutions.simsensor.protocol.ProtocolCodec
import com.taksolutions.simsensor.protocol.ProtocolMessage
import com.taksolutions.simsensor.protocol.Welcome

/**
 * Turns one incoming device message into the reply to send (or null for none).
 * Transport-free, so it can be tested without a socket.
 */
class DeviceMessageHandler(
    private val serverVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun handle(text: String): ProtocolMessage? =
        when (ProtocolCodec.decodeOrNull(text)) {
            is Hello -> Welcome(serverTimeMillis = clock(), serverVersion = serverVersion)
            null -> ErrorMessage(code = "bad_message", message = "Unrecognized message")
            else -> ErrorMessage(code = "unexpected_message", message = "Not accepted from a device")
        }
}

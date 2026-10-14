package com.taksolutions.simsensor.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Version of the device <-> server WebSocket protocol (docs/PROTOCOL.md).
 * Raise it only for breaking changes; additive fields keep the same version.
 */
const val PROTOCOL_VERSION: Int = 1

/** Every message on the device WebSocket. The JSON `type` field selects the subtype. */
@Serializable
sealed interface ProtocolMessage

/** Device -> server: first message after the socket opens. */
@Serializable
@SerialName("hello")
data class Hello(
    val deviceId: String? = null,
    val appVersion: String,
    val protocolVersion: Int = PROTOCOL_VERSION,
) : ProtocolMessage

/** Server -> device: reply to [Hello]. */
@Serializable
@SerialName("welcome")
data class Welcome(
    val serverTimeMillis: Long,
    val serverVersion: String,
    val protocolVersion: Int = PROTOCOL_VERSION,
) : ProtocolMessage

/** Server -> device: the last message could not be handled. */
@Serializable
@SerialName("error")
data class ErrorMessage(
    val code: String,
    val message: String,
) : ProtocolMessage

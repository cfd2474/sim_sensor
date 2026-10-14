package com.taksolutions.simsensor.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Encodes and decodes [ProtocolMessage]s. The one place the wire format is defined. */
object ProtocolCodec {
    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true // newer peers may add fields
        encodeDefaults = true
    }

    fun encode(message: ProtocolMessage): String =
        json.encodeToString(ProtocolMessage.serializer(), message)

    /** Returns null when [text] is not a message this build understands. */
    fun decodeOrNull(text: String): ProtocolMessage? =
        try {
            json.decodeFromString(ProtocolMessage.serializer(), text)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
}

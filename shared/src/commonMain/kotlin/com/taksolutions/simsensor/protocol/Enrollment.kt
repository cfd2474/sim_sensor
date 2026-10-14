package com.taksolutions.simsensor.protocol

import kotlinx.serialization.Serializable

/** Body of `POST /api/devices/enroll`. */
@Serializable
data class EnrollRequest(
    val enrollmentToken: String,
    val appVersion: String,
    /** Optional name pushed by the MDM (`device_name`); the server picks one otherwise. */
    val deviceName: String? = null,
    val model: String? = null,
)

/** Response of `POST /api/devices/enroll`. The device keeps [deviceToken] secret. */
@Serializable
data class EnrollResponse(
    val deviceId: String,
    val deviceToken: String,
    val deviceName: String,
)

/**
 * The enrollment QR code content: `simsensor://enroll?server=<url>&token=<token>`.
 * The console builds it, the phone parses it.
 */
object EnrollmentLink {
    private const val PREFIX = "simsensor://enroll?"

    data class Parsed(val serverUrl: String, val token: String)

    fun build(serverUrl: String, token: String): String =
        PREFIX + "server=" + percentEncode(serverUrl) + "&token=" + percentEncode(token)

    fun parse(link: String): Parsed? {
        if (!link.startsWith(PREFIX)) return null
        val params = link.removePrefix(PREFIX).split('&').mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null else pair.substring(0, i) to (percentDecode(pair.substring(i + 1)) ?: return null)
        }.toMap()
        val server = params["server"]?.takeIf { it.isNotBlank() } ?: return null
        val token = params["token"]?.takeIf { it.isNotBlank() } ?: return null
        return Parsed(server, token)
    }

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    private fun percentEncode(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val c = byte.toInt().toChar()
            if (byte >= 0 && c in UNRESERVED) {
                append(c)
            } else {
                append('%').append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF])
            }
        }
    }

    private fun percentDecode(value: String): String? {
        val bytes = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%') {
                if (i + 2 >= value.length) return null
                val hex = value.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                bytes.add(hex.toByte())
                i += 3
            } else {
                bytes.addAll(c.toString().encodeToByteArray().toList())
                i++
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    private const val HEX = "0123456789ABCDEF"
}

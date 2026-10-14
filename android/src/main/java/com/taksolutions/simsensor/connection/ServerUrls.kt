package com.taksolutions.simsensor.connection

/** Derives endpoint URLs from the server base URL an operator enters (or an MDM pushes). */
object ServerUrls {
    private const val DEVICE_SOCKET_PATH = "/ws/device"

    /**
     * "https://host" -> "wss://host/ws/device", "http://host:8080/" -> "ws://host:8080/ws/device".
     * A bare host is treated as HTTPS. Returns null for anything else.
     */
    fun deviceSocketUrl(serverUrl: String): String? {
        val trimmed = serverUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val base = when {
            trimmed.startsWith("https://", ignoreCase = true) -> "wss://" + trimmed.substring(8)
            trimmed.startsWith("http://", ignoreCase = true) -> "ws://" + trimmed.substring(7)
            trimmed.contains("://") -> return null
            else -> "wss://$trimmed"
        }
        return base + DEVICE_SOCKET_PATH
    }
}

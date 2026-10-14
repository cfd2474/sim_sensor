package com.taksolutions.simsensor.connection

/** Derives endpoint URLs from the server base URL in a QR code or MDM setting. */
object ServerUrls {
    private const val DEVICE_SOCKET_PATH = "/ws/device"

    /**
     * "https://host" -> "wss://host/ws/device", "http://host:8080/" -> "ws://host:8080/ws/device".
     * A bare host is treated as HTTPS. Returns null for anything else.
     */
    fun deviceSocketUrl(serverUrl: String): String? {
        val base = httpBase(serverUrl) ?: return null
        return base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + DEVICE_SOCKET_PATH
    }

    /** "https://host" + "/api/x" -> "https://host/api/x". */
    fun apiUrl(serverUrl: String, path: String): String? = httpBase(serverUrl)?.let { it + path }

    private fun httpBase(serverUrl: String): String? {
        val trimmed = serverUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        return when {
            trimmed.startsWith("https://", ignoreCase = true) -> "https://" + trimmed.substring(8)
            trimmed.startsWith("http://", ignoreCase = true) -> "http://" + trimmed.substring(7)
            trimmed.contains("://") -> null
            else -> "https://$trimmed"
        }
    }
}

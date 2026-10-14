package com.taksolutions.simsensor.server

import java.io.File

/** Runtime settings, read from environment variables (see deploy/.env.example). */
data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    /** Built console (console/dist). Not served when null or missing. */
    val consoleDir: File? = null,
    val version: String = BuildInfo.version,
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): ServerConfig = ServerConfig(
            host = env["SIM_HOST"] ?: "0.0.0.0",
            port = env["SIM_PORT"]?.toIntOrNull() ?: 8080,
            consoleDir = env["SIM_CONSOLE_DIR"]?.let(::File),
        )
    }
}

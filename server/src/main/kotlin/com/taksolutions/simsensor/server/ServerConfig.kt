package com.taksolutions.simsensor.server

import java.io.File
import java.time.Duration

/** Database connection settings. */
data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int = 10,
)

/** First-start admin account, created only when no instructor exists yet. */
data class BootstrapAdmin(val email: String, val password: String)

/** Runtime settings, read from environment variables (see deploy/.env.example). */
data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    /** Built console (console/dist). Not served when null or missing. */
    val consoleDir: File? = null,
    val version: String = BuildInfo.version,
    val database: DatabaseConfig? = null,
    val bootstrapAdmin: BootstrapAdmin? = null,
    /** Send the session cookie only over HTTPS. Turn off only for local plain-HTTP runs. */
    val cookieSecure: Boolean = true,
    val sessionTtl: Duration = Duration.ofHours(12),
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): ServerConfig {
            val dbUrl = env["SIM_DATABASE_URL"]
            val adminEmail = env["SIM_ADMIN_EMAIL"]?.takeIf { it.isNotBlank() }
            val adminPassword = env["SIM_ADMIN_PASSWORD"]?.takeIf { it.isNotBlank() }
            return ServerConfig(
                host = env["SIM_HOST"] ?: "0.0.0.0",
                port = env["SIM_PORT"]?.toIntOrNull() ?: 8080,
                consoleDir = env["SIM_CONSOLE_DIR"]?.let(::File),
                database = dbUrl?.let {
                    DatabaseConfig(
                        url = it,
                        user = env["SIM_DATABASE_USER"] ?: "simsensor",
                        password = env["SIM_DATABASE_PASSWORD"] ?: "",
                        maxPoolSize = env["SIM_DATABASE_POOL_SIZE"]?.toIntOrNull() ?: 10,
                    )
                },
                bootstrapAdmin = if (adminEmail != null && adminPassword != null) {
                    BootstrapAdmin(adminEmail, adminPassword)
                } else {
                    null
                },
                cookieSecure = env["SIM_COOKIE_SECURE"]?.toBooleanStrictOrNull() ?: true,
                sessionTtl = env["SIM_SESSION_HOURS"]?.toLongOrNull()?.let(Duration::ofHours) ?: Duration.ofHours(12),
            )
        }
    }
}

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
    /** Public base URL, put into enrollment QR codes, e.g. https://simsensor.tak-solutions.com */
    val publicUrl: String = "http://localhost:8080",
    /** Redis for fan-out between server instances. Null = single instance, in-memory bus. */
    val redisUrl: String? = null,
    /** Map tiles for the console. OpenStreetMap by default (fine for low use; see docs/DESIGN.md §4). */
    val tileUrl: String = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
    val tileAttribution: String = "© OpenStreetMap contributors",
    /** Folder holding the published phone app (simsensor.apk + simsensor.json). */
    val apkDir: File? = null,
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
                publicUrl = env["SIM_PUBLIC_URL"]?.trimEnd('/') ?: "http://localhost:8080",
                redisUrl = env["SIM_REDIS_URL"]?.takeIf { it.isNotBlank() },
                tileUrl = env["SIM_TILE_URL"]?.takeIf { it.isNotBlank() } ?: "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
                tileAttribution = env["SIM_TILE_ATTRIBUTION"]?.takeIf { it.isNotBlank() } ?: "© OpenStreetMap contributors",
                apkDir = env["SIM_APK_DIR"]?.takeIf { it.isNotBlank() }?.let(::File),
            )
        }
    }
}

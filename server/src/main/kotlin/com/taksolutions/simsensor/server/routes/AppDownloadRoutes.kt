package com.taksolutions.simsensor.server.routes

import com.taksolutions.simsensor.server.api.ErrorResponse
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Written next to the APK by tools/publish-apk.sh. */
@Serializable
data class PublishedApp(
    val version: String,
    val versionCode: Int,
    val sha256: String,
    val sizeBytes: Long,
    val publishedAt: String,
)

@Serializable
data class AppInfoDto(val available: Boolean, val url: String, val app: PublishedApp? = null)

const val APK_FILE = "simsensor.apk"
private const val META_FILE = "simsensor.json"
private val metaJson = Json { ignoreUnknownKeys = true }

/**
 * The phone app for sideloading (docs/DESIGN.md §11.2): `GET /download/simsensor.apk` (no login,
 * so a trainee's browser can fetch it) and `GET /api/app` (what is published).
 */
fun Route.appDownloadRoutes(apkDir: File?, publicUrl: String) {
    val url = "$publicUrl/download/$APK_FILE"

    get("/download/$APK_FILE") {
        val apk = apkDir?.let { File(it, APK_FILE) }?.takeIf { it.isFile }
        if (apk == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "The app has not been published yet"))
            return@get
        }
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, APK_FILE).toString(),
        )
        call.response.header(HttpHeaders.ContentType, APK_MIME.toString())
        call.respondFile(apk)
    }

    get("/api/app") {
        val apk = apkDir?.let { File(it, APK_FILE) }?.takeIf { it.isFile }
        val meta = apkDir?.let { File(it, META_FILE) }?.takeIf { it.isFile }
            ?.let { runCatching { metaJson.decodeFromString(PublishedApp.serializer(), it.readText()) }.getOrNull() }
        call.respond(AppInfoDto(available = apk != null, url = url, app = meta.takeIf { apk != null }))
    }
}

private val APK_MIME = ContentType("application", "vnd.android.package-archive")

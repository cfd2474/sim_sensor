package com.taksolutions.simsensor.server.routes

import com.taksolutions.simsensor.server.api.ConsoleConfigDto
import com.taksolutions.simsensor.server.api.ErrorResponse
import io.ktor.http.CacheControl
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File

/** Public console settings: version and map tiles. */
fun Route.consoleConfigRoute(config: ConsoleConfigDto) {
    get("/api/config") { call.respond(config) }
}

/**
 * Serves the built instructor console (console/dist). Any path that isn't a file gets
 * index.html, so console URLs such as /exercises/<id> survive a reload. /api and /ws paths are
 * never answered with the page.
 */
fun Route.consoleRoutes(consoleDir: File) {
    val root = consoleDir.canonicalFile
    val index = File(root, "index.html")
    get("{path...}") {
        val path = call.parameters.getAll("path")?.joinToString("/").orEmpty()
        if (path == "api" || path.startsWith("api/") || path == "ws" || path.startsWith("ws/")) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "Not found"))
            return@get
        }
        val file = File(root, path).canonicalFile
        val inside = file.path.startsWith(root.path + File.separator)
        if (path.isNotEmpty() && inside && file.isFile) {
            // Vite puts content hashes in asset names, so they can be cached for good.
            if (path.startsWith("assets/")) call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 31_536_000))
            call.respondFile(file)
        } else {
            call.response.cacheControl(CacheControl.NoCache(null))
            call.respondFile(index)
        }
    }
}

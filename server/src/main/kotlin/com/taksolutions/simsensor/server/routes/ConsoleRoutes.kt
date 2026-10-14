package com.taksolutions.simsensor.server.routes

import io.ktor.server.http.content.staticFiles
import io.ktor.server.routing.Route
import java.io.File

/** Serves the built instructor console (console/dist) at the site root. */
fun Route.consoleRoutes(consoleDir: File) {
    staticFiles("/", consoleDir) {
        default("index.html")
    }
}

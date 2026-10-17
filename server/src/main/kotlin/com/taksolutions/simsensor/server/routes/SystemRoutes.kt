package com.taksolutions.simsensor.server.routes

import com.taksolutions.simsensor.server.auth.actor
import com.taksolutions.simsensor.server.system.UpdateService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class UpdateRequest(val tag: String)

/** Settings → Server update (admins). Mount inside `authenticate(AUTH_INSTRUCTOR)`. */
fun Route.systemRoutes(updates: UpdateService, certificates: com.taksolutions.simsensor.server.system.CertificateService) {
    get("/api/system/update") { call.respond(updates.info(call.actor())) }
    /** The server's HTTPS certificate (admins); null body (204) on plain-HTTP or local installs. */
    get("/api/system/certificate") {
        val status = certificates.status(call.actor())
        if (status == null) call.respond(HttpStatusCode.NoContent) else call.respond(status)
    }
    post("/api/system/update") {
        updates.request(call.actor(), call.receive<UpdateRequest>().tag)
        call.respond(HttpStatusCode.Accepted, updates.info(call.actor()))
    }
}

package com.taksolutions.simsensor.server.routes

import com.taksolutions.simsensor.server.api.BadRequestApiException
import com.taksolutions.simsensor.server.auth.actor
import com.taksolutions.simsensor.server.settings.SettingsService
import com.taksolutions.simsensor.server.settings.SystemSettings
import com.taksolutions.simsensor.server.sources.SourceService
import com.taksolutions.simsensor.source.GasSource
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** Virtual leaks and the preview. Mount inside `authenticate(AUTH_INSTRUCTOR)`. */
fun Route.sourceRoutes(service: SourceService) {
    route("/api/exercises/{id}") {
        route("/sources") {
            get {
                call.respond(service.list(call.actor(), call.uuidParam("id")))
            }
            post {
                call.respond(HttpStatusCode.Created, service.create(call.actor(), call.uuidParam("id"), call.receive<GasSource>()))
            }
            route("/{sourceId}") {
                put {
                    call.respond(service.update(call.actor(), call.uuidParam("id"), call.uuidParam("sourceId"), call.receive<GasSource>()))
                }
                delete {
                    service.delete(call.actor(), call.uuidParam("id"), call.uuidParam("sourceId"))
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/activate") {
                    call.respond(service.activate(call.actor(), call.uuidParam("id"), call.uuidParam("sourceId")))
                }
            }
        }

        get("/preview") {
            val q = call.request.queryParameters
            fun number(name: String) = q[name]?.toDoubleOrNull() ?: throw BadRequestApiException("'$name' is required")
            call.respond(
                service.preview(
                    call.actor(), call.uuidParam("id"),
                    gas = q["gas"] ?: throw BadRequestApiException("'gas' is required"),
                    south = number("south"), west = number("west"), north = number("north"), east = number("east"),
                    cells = q["cells"]?.toIntOrNull() ?: 64,
                ),
            )
        }
    }
}

/** System settings: everyone reads, admins change. Mount inside `authenticate(AUTH_INSTRUCTOR)`. */
fun Route.settingsRoutes(service: SettingsService, mail: com.taksolutions.simsensor.server.mail.MailService) {
    route("/api/settings/smtp") {
        get { call.respond(mail.view(call.actor())) }
        put { call.respond(mail.update(call.actor(), call.receive<com.taksolutions.simsensor.server.mail.SmtpSettingsInput>())) }
        post("/test") { call.respond(mapOf("sentTo" to mail.sendTest(call.actor()))) }
    }
    route("/api/settings") {
        get { call.respond(service.get()) }
        put { call.respond(service.update(call.actor(), call.receive<SystemSettings>())) }
    }
}

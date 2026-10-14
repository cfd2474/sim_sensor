package com.taksolutions.simsensor.server.routes

import com.taksolutions.simsensor.profile.SensorProfile
import com.taksolutions.simsensor.server.api.toDto
import com.taksolutions.simsensor.server.auth.actor
import com.taksolutions.simsensor.server.profiles.ProfileService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** Profile templates. Mount inside `authenticate(AUTH_INSTRUCTOR)`. */
fun Route.profileRoutes(service: ProfileService) {
    route("/api/profiles") {
        get {
            call.respond(service.list().map { it.toDto() })
        }

        post {
            val created = service.create(call.actor(), call.receive<SensorProfile>())
            call.respond(HttpStatusCode.Created, created.toDto())
        }

        get("/{id}") {
            call.respond(service.get(call.uuidParam("id")).toDto())
        }

        put("/{id}") {
            call.respond(service.update(call.actor(), call.uuidParam("id"), call.receive<SensorProfile>()).toDto())
        }

        delete("/{id}") {
            service.delete(call.actor(), call.uuidParam("id"))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

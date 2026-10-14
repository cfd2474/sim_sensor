package com.taksolutions.simsensor.server.routes

import com.taksolutions.simsensor.server.api.CreateInstructorRequest
import com.taksolutions.simsensor.server.api.UpdateInstructorRequest
import com.taksolutions.simsensor.server.api.toDto
import com.taksolutions.simsensor.server.auth.actor
import com.taksolutions.simsensor.server.instructors.InstructorService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** Admin-only instructor management. Mount inside `authenticate(AUTH_INSTRUCTOR)`. */
fun Route.instructorRoutes(service: InstructorService) {
    route("/api/instructors") {
        get {
            call.respond(service.list(call.actor()).map { it.toDto() })
        }

        post {
            val request = call.receive<CreateInstructorRequest>()
            val created = service.create(call.actor(), request.email, request.name, request.password, parseRole(request.role))
            call.respond(HttpStatusCode.Created, created.toDto())
        }

        patch("/{id}") {
            val request = call.receive<UpdateInstructorRequest>()
            val updated = service.update(
                actor = call.actor(),
                id = call.uuidParam("id"),
                name = request.name,
                role = request.role?.let(::parseRole),
                disabled = request.disabled,
                newPassword = request.password,
            )
            call.respond(updated.toDto())
        }
    }
}

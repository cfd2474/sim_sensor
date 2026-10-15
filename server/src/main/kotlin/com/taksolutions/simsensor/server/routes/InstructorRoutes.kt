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

/** Instructor list (everyone) and management (admins). Mount inside `authenticate(AUTH_INSTRUCTOR)`. */
fun Route.instructorRoutes(service: InstructorService, passwords: com.taksolutions.simsensor.server.auth.PasswordResetService) {
    route("/api/instructors") {
        get {
            call.respond(service.list().map { it.toDto() })
        }

        post {
            val request = call.receive<CreateInstructorRequest>()
            if (request.sendInvite && !passwords.emailAvailable()) {
                throw com.taksolutions.simsensor.server.api.ConflictException("Set up email first (Settings → Email)")
            }
            val created = service.create(call.actor(), request.email, request.name, request.password, parseRole(request.role), request.sendInvite)
            val invited = if (request.sendInvite) passwords.invite(call.actor(), created) else null
            call.respond(HttpStatusCode.Created, created.toDto().copy(inviteSent = invited))
        }

        post("/{id}/reset-email") {
            passwords.sendResetLink(call.actor(), call.uuidParam("id"))
            call.respond(HttpStatusCode.NoContent)
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

package com.taksolutions.simsensor.server.routes

import com.taksolutions.simsensor.server.api.ChangePasswordRequest
import com.taksolutions.simsensor.server.api.LoginRequest
import com.taksolutions.simsensor.server.api.toMe
import com.taksolutions.simsensor.server.auth.AUTH_INSTRUCTOR
import com.taksolutions.simsensor.server.auth.AuthService
import com.taksolutions.simsensor.server.auth.SessionCookie
import com.taksolutions.simsensor.server.auth.actor
import com.taksolutions.simsensor.server.instructors.InstructorService
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set

fun Route.authRoutes(authService: AuthService, instructorService: InstructorService) {
    route("/api/auth") {
        post("/login") {
            val request = call.receive<LoginRequest>()
            val result = authService.login(request.email, request.password, clientKey = call.request.origin.remoteHost)
            call.sessions.set(SessionCookie(result.token))
            call.respond(result.instructor.toMe())
        }

        post("/logout") {
            call.sessions.get<SessionCookie>()?.let { authService.logout(it.token) }
            call.sessions.clear<SessionCookie>()
            call.respond(HttpStatusCode.NoContent)
        }

        authenticate(AUTH_INSTRUCTOR) {
            get("/me") {
                call.respond(call.actor().toMe())
            }

            post("/password") {
                val request = call.receive<ChangePasswordRequest>()
                instructorService.changeOwnPassword(call.actor(), request.currentPassword, request.newPassword)
                call.sessions.clear<SessionCookie>()
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

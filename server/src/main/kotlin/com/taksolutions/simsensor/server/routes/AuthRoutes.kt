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

@kotlinx.serialization.Serializable
data class ForgotPasswordRequest(val email: String)

@kotlinx.serialization.Serializable
data class ResetPasswordRequest(val token: String, val password: String)

/** What the sign-in page may offer. */
@kotlinx.serialization.Serializable
data class AuthOptions(val forgotPassword: Boolean)

fun Route.authRoutes(
    authService: AuthService,
    instructorService: InstructorService,
    passwords: com.taksolutions.simsensor.server.auth.PasswordResetService,
) {
    route("/api/auth") {
        get("/options") {
            call.respond(AuthOptions(forgotPassword = passwords.emailAvailable()))
        }

        // Same answer whether or not the address has an account (no account probing).
        post("/forgot") {
            passwords.forgot(call.receive<ForgotPasswordRequest>().email, clientKey = call.request.origin.remoteHost)
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "sent if the account exists"))
        }

        get("/reset") {
            val token = call.request.queryParameters["token"].orEmpty()
            call.respond(passwords.info(token))
        }

        post("/reset") {
            val request = call.receive<ResetPasswordRequest>()
            passwords.reset(request.token, request.password)
            call.respond(HttpStatusCode.NoContent)
        }

        post("/login") {
            val request = call.receive<LoginRequest>()
            val result = authService.login(request.email, request.password, clientKey = call.request.origin.remoteHost)
            call.sessions.set(SessionCookie(result.token))
            call.respond(result.instructor.toMe(instructorService.defaultAdminActive()))
        }

        post("/logout") {
            call.sessions.get<SessionCookie>()?.let { authService.logout(it.token) }
            call.sessions.clear<SessionCookie>()
            call.respond(HttpStatusCode.NoContent)
        }

        authenticate(AUTH_INSTRUCTOR) {
            get("/me") {
                call.respond(call.actor().toMe(instructorService.defaultAdminActive()))
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

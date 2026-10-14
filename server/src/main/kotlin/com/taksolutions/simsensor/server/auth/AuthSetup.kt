package com.taksolutions.simsensor.server.auth

import com.taksolutions.simsensor.server.ServerConfig
import com.taksolutions.simsensor.server.api.ErrorResponse
import com.taksolutions.simsensor.server.api.UnauthorizedException
import com.taksolutions.simsensor.server.instructors.InstructorPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.principal
import io.ktor.server.auth.session
import io.ktor.server.response.respond
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import kotlinx.serialization.Serializable

/** Name of the authentication provider protecting instructor routes. */
const val AUTH_INSTRUCTOR = "instructor"

const val SESSION_COOKIE = "simsensor_session"

/** What the session cookie holds: an opaque token whose hash is in `instructor_sessions`. */
@Serializable
data class SessionCookie(val token: String)

fun Application.installInstructorAuth(config: ServerConfig, authService: AuthService) {
    install(Sessions) {
        cookie<SessionCookie>(SESSION_COOKIE) {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = config.cookieSecure
            cookie.maxAgeInSeconds = config.sessionTtl.seconds
            cookie.extensions["SameSite"] = "Strict"
        }
    }
    install(Authentication) {
        session<SessionCookie>(AUTH_INSTRUCTOR) {
            validate { cookie -> authService.resolve(cookie.token) }
            challenge {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "Log in first"))
            }
        }
    }
}

/** The logged-in instructor. Only valid inside `authenticate(AUTH_INSTRUCTOR)`. */
fun ApplicationCall.actor(): InstructorPrincipal = principal<InstructorPrincipal>() ?: throw UnauthorizedException()

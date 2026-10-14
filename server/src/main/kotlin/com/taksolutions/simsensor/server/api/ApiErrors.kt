package com.taksolutions.simsensor.server.api

import com.taksolutions.simsensor.profile.ValidationError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

/** Body of every error response. */
@Serializable
data class ErrorResponse(
    val code: String,
    val message: String,
    val errors: List<ValidationError> = emptyList(),
)

/** Errors the API reports to the caller. Thrown by services, mapped to responses in one place. */
sealed class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
    val errors: List<ValidationError> = emptyList(),
) : RuntimeException(message)

class BadRequestApiException(message: String) : ApiException(HttpStatusCode.BadRequest, "bad_request", message)

class UnauthorizedException(message: String = "Log in first") :
    ApiException(HttpStatusCode.Unauthorized, "unauthorized", message)

class ForbiddenException(message: String = "Not allowed") : ApiException(HttpStatusCode.Forbidden, "forbidden", message)

class NotFoundException(message: String = "Not found") : ApiException(HttpStatusCode.NotFound, "not_found", message)

class ConflictException(message: String) : ApiException(HttpStatusCode.Conflict, "conflict", message)

class TooManyRequestsException(message: String) :
    ApiException(HttpStatusCode.TooManyRequests, "too_many_requests", message)

class ValidationException(errors: List<ValidationError>, message: String = "Validation failed") :
    ApiException(HttpStatusCode.UnprocessableEntity, "validation_failed", message, errors)

fun Application.installErrorHandling() {
    install(StatusPages) {
        exception<ApiException> { call, e ->
            call.respond(e.status, ErrorResponse(e.code, e.message, e.errors))
        }
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", e.message ?: "Bad request"))
        }
    }
}

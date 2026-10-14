package com.taksolutions.simsensor.server.routes

import com.taksolutions.simsensor.profile.ValidationError
import com.taksolutions.simsensor.server.api.BadRequestApiException
import com.taksolutions.simsensor.server.api.ValidationException
import com.taksolutions.simsensor.server.instructors.Role
import io.ktor.server.application.ApplicationCall
import java.util.UUID

/** Path parameter [name] as a UUID. A malformed id is a 400. */
fun ApplicationCall.uuidParam(name: String): UUID {
    val raw = parameters[name] ?: throw BadRequestApiException("Missing '$name'")
    return parseUuid(raw, name)
}

fun parseUuid(raw: String, field: String): UUID =
    runCatching { UUID.fromString(raw) }.getOrNull() ?: throw BadRequestApiException("'$field' is not a valid id")

fun parseRole(raw: String): Role =
    Role.entries.firstOrNull { it.dbValue == raw }
        ?: throw ValidationException(listOf(ValidationError(null, "role", "Role must be 'admin' or 'instructor'")))

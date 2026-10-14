package com.taksolutions.simsensor.server.instructors

import java.time.Instant
import java.util.UUID

enum class Role(val dbValue: String) {
    ADMIN("admin"),
    INSTRUCTOR("instructor"),
    ;

    companion object {
        fun fromDb(value: String): Role = entries.first { it.dbValue == value }
    }
}

data class Instructor(
    val id: UUID,
    val email: String,
    val name: String,
    val role: Role,
    val disabled: Boolean,
    val createdAt: Instant,
)

/** The logged-in instructor making a request. */
data class InstructorPrincipal(
    val id: UUID,
    val email: String,
    val name: String,
    val role: Role,
) {
    val isAdmin: Boolean get() = role == Role.ADMIN
}

fun Instructor.toPrincipal() = InstructorPrincipal(id = id, email = email, name = name, role = role)

package com.taksolutions.simsensor.server.instructors

import com.taksolutions.simsensor.profile.ValidationError
import com.taksolutions.simsensor.server.api.ConflictException
import com.taksolutions.simsensor.server.api.ForbiddenException
import com.taksolutions.simsensor.server.api.NotFoundException
import com.taksolutions.simsensor.server.api.UnauthorizedException
import com.taksolutions.simsensor.server.api.ValidationException
import com.taksolutions.simsensor.server.auth.PasswordHasher
import com.taksolutions.simsensor.server.auth.SessionRepository
import java.util.UUID

/**
 * Instructor accounts. Every instructor can list them (to share exercises with colleagues);
 * creating and changing accounts is admin-only.
 */
class InstructorService(
    private val instructors: InstructorRepository,
    private val sessions: SessionRepository,
    private val hasher: PasswordHasher,
) {
    /** Any logged-in instructor may list (the route requires a login). */
    suspend fun list(): List<Instructor> = instructors.list()

    suspend fun create(actor: InstructorPrincipal, email: String, name: String, password: String, role: Role): Instructor {
        requireAdmin(actor)
        validate(email = email, name = name, password = password)
        return instructors.create(email, name, hasher.hash(password), role)
    }

    suspend fun update(
        actor: InstructorPrincipal,
        id: UUID,
        name: String?,
        role: Role?,
        disabled: Boolean?,
        newPassword: String?,
    ): Instructor {
        requireAdmin(actor)
        if (id == actor.id && (disabled == true || (role != null && role != Role.ADMIN))) {
            throw ConflictException("You can't disable or demote your own account")
        }
        validate(name = name, password = newPassword)
        val updated = instructors.update(id, name, role, disabled) ?: throw NotFoundException("Instructor not found")
        if (newPassword != null) instructors.setPasswordHash(id, hasher.hash(newPassword))
        if (newPassword != null || disabled == true || role != null) sessions.deleteForInstructor(id)
        return updated
    }

    /** Changes the caller's own password and signs out their other sessions. */
    suspend fun changeOwnPassword(actor: InstructorPrincipal, currentPassword: String, newPassword: String) {
        val stored = instructors.passwordHash(actor.id) ?: throw NotFoundException("Instructor not found")
        if (!hasher.verify(currentPassword, stored)) throw UnauthorizedException("Current password is wrong")
        validate(password = newPassword)
        instructors.setPasswordHash(actor.id, hasher.hash(newPassword))
        sessions.deleteForInstructor(actor.id)
    }

    private fun validate(email: String? = null, name: String? = null, password: String? = null) {
        val errors = buildList {
            if (email != null && !EMAIL.matches(email.trim())) add(ValidationError(null, "email", "Enter a valid email address"))
            if (name != null && name.isBlank()) add(ValidationError(null, "name", "Name is required"))
            if (password != null && password.length < MIN_PASSWORD_LENGTH) {
                add(ValidationError(null, "password", "Password must be at least $MIN_PASSWORD_LENGTH characters"))
            }
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
    }

    companion object {
        const val MIN_PASSWORD_LENGTH = 12
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}

fun requireAdmin(actor: InstructorPrincipal) {
    if (!actor.isAdmin) throw ForbiddenException("Admins only")
}

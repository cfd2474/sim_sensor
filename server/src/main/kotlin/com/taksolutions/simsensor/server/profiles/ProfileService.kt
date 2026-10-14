package com.taksolutions.simsensor.server.profiles

import com.taksolutions.simsensor.profile.ProfileValidator
import com.taksolutions.simsensor.profile.SensorProfile
import com.taksolutions.simsensor.profile.ValidationError
import com.taksolutions.simsensor.server.api.ConflictException
import com.taksolutions.simsensor.server.api.NotFoundException
import com.taksolutions.simsensor.server.api.ValidationException
import com.taksolutions.simsensor.server.exercises.ExerciseRepository
import com.taksolutions.simsensor.server.instructors.InstructorPrincipal
import com.taksolutions.simsensor.server.instructors.requireAdmin
import com.taksolutions.simsensor.server.realtime.ChangeNotifier
import java.util.UUID

/** Profile templates. Everyone can read them; only admins change them. */
class ProfileService(
    private val profiles: ProfileRepository,
    private val exercises: ExerciseRepository,
    private val notifier: ChangeNotifier,
) {
    suspend fun list(): List<StoredProfile> = profiles.list()

    suspend fun get(id: UUID): StoredProfile = profiles.find(id) ?: throw NotFoundException("Profile not found")

    suspend fun create(actor: InstructorPrincipal, definition: SensorProfile): StoredProfile {
        requireAdmin(actor)
        ensureValid(definition)
        return profiles.create(definition)
    }

    /**
     * Rejected when any exercise using this profile would end up with invalid criteria,
     * for example when a lowered range would put an exercise's set point out of range.
     */
    suspend fun update(actor: InstructorPrincipal, id: UUID, definition: SensorProfile): StoredProfile {
        requireAdmin(actor)
        ensureValid(definition)
        val dependents = exercises.listUsingProfile(id)
        val dependentErrors = dependents.flatMap { exercise ->
            ProfileValidator.validateOverrides(definition, exercise.criteria).map { error ->
                error.copy(message = "Exercise '${exercise.name}': ${error.message}")
            }
        }
        if (dependentErrors.isNotEmpty()) {
            throw ValidationException(dependentErrors, "The change would make some exercises' criteria invalid")
        }
        val updated = profiles.update(id, definition) ?: throw NotFoundException("Profile not found")
        dependents.forEach { notifier.exerciseChanged(it.id) }
        return updated
    }

    suspend fun delete(actor: InstructorPrincipal, id: UUID) {
        requireAdmin(actor)
        val profile = get(id)
        if (profile.builtin) throw ConflictException("Built-in profiles can't be deleted")
        if (exercises.listUsingProfile(id).isNotEmpty()) throw ConflictException("The profile is used by exercises")
        profiles.delete(id)
    }

    private fun ensureValid(definition: SensorProfile) {
        val errors: List<ValidationError> = ProfileValidator.validate(definition)
        if (errors.isNotEmpty()) throw ValidationException(errors)
    }
}

package com.taksolutions.simsensor.server.profiles

import com.taksolutions.simsensor.profile.ProfileValidator
import com.taksolutions.simsensor.profile.SensorProfile
import com.taksolutions.simsensor.profile.ValidationError
import com.taksolutions.simsensor.server.api.ConflictException
import com.taksolutions.simsensor.server.api.NotFoundException
import com.taksolutions.simsensor.server.api.ValidationException
import com.taksolutions.simsensor.server.devices.DeviceRepository
import com.taksolutions.simsensor.server.exercises.ExerciseRepository
import com.taksolutions.simsensor.server.instructors.InstructorPrincipal
import com.taksolutions.simsensor.server.instructors.requireAdmin
import com.taksolutions.simsensor.server.realtime.ChangeNotifier
import java.util.UUID

/** Monitors (stored as profiles). Everyone can read them; only admins change them. */
class ProfileService(
    private val profiles: ProfileRepository,
    private val exercises: ExerciseRepository,
    private val devices: DeviceRepository,
    private val notifier: ChangeNotifier,
) {
    suspend fun list(): List<StoredProfile> = profiles.list()

    suspend fun get(id: UUID): StoredProfile = profiles.find(id) ?: throw NotFoundException("Profile not found")

    suspend fun create(actor: InstructorPrincipal, definition: SensorProfile): StoredProfile {
        requireAdmin(actor)
        ensureValid(definition)
        return profiles.create(definition)
    }

    /** Phones using the monitor get the change within about a second. */
    suspend fun update(actor: InstructorPrincipal, id: UUID, definition: SensorProfile): StoredProfile {
        requireAdmin(actor)
        ensureValid(definition)
        val dependents = exercises.listUsingProfile(id)
        val updated = profiles.update(id, definition) ?: throw NotFoundException("Profile not found")
        dependents.forEach { notifier.exerciseChanged(it.id) }
        return updated
    }

    suspend fun delete(actor: InstructorPrincipal, id: UUID) {
        requireAdmin(actor)
        val profile = get(id)
        if (profile.builtin) throw ConflictException("The built-in monitor can't be deleted")
        val users = exercises.listUsingProfile(id)
        if (users.isNotEmpty()) throw ConflictException("The monitor is used in: ${users.joinToString { it.name }}")
        if (devices.anyUsingMonitor(id)) throw ConflictException("Phones still use the monitor")
        profiles.delete(id)
    }

    private fun ensureValid(definition: SensorProfile) {
        val errors: List<ValidationError> = ProfileValidator.validate(definition)
        if (errors.isNotEmpty()) throw ValidationException(errors)
    }
}

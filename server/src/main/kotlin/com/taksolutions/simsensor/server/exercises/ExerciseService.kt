package com.taksolutions.simsensor.server.exercises

import com.taksolutions.simsensor.profile.CriteriaOverrides
import com.taksolutions.simsensor.profile.ProfileValidator
import com.taksolutions.simsensor.profile.SensorProfile
import com.taksolutions.simsensor.profile.ValidationError
import com.taksolutions.simsensor.profile.withOverrides
import com.taksolutions.simsensor.server.api.ConflictException
import com.taksolutions.simsensor.server.api.NotFoundException
import com.taksolutions.simsensor.server.api.ValidationException
import com.taksolutions.simsensor.server.devices.DeviceRepository
import com.taksolutions.simsensor.server.instructors.InstructorPrincipal
import com.taksolutions.simsensor.server.instructors.InstructorRepository
import com.taksolutions.simsensor.server.profiles.ProfileRepository
import com.taksolutions.simsensor.server.realtime.ChangeNotifier
import java.util.UUID

/**
 * Exercises and their criteria (docs/DESIGN.md §3.3, §7.2).
 *
 * Access rule: admins reach every exercise; an instructor reaches only exercises they are
 * linked to. An exercise the caller can't reach is reported as not found, so its existence
 * isn't revealed.
 */
class ExerciseService(
    private val exercises: ExerciseRepository,
    private val profiles: ProfileRepository,
    private val instructors: InstructorRepository,
    private val devices: DeviceRepository,
    private val notifier: ChangeNotifier,
) {
    suspend fun list(actor: InstructorPrincipal): List<Exercise> =
        if (actor.isAdmin) exercises.listAll() else exercises.listForInstructor(actor.id)

    suspend fun get(actor: InstructorPrincipal, id: UUID): Exercise {
        val exercise = exercises.find(id)
        if (exercise == null || !canAccess(actor, exercise)) throw NotFoundException("Exercise not found")
        return exercise
    }

    suspend fun create(
        actor: InstructorPrincipal,
        name: String,
        profileId: UUID,
        criteria: CriteriaOverrides,
        o2Displacement: Boolean,
    ): Exercise {
        requireName(name)
        ensureCriteriaValid(templateFor(profileId), criteria)
        return exercises.create(name, profileId, criteria, o2Displacement, createdBy = actor.id)
    }

    /** Changing the profile re-checks the exercise's criteria against the new template. */
    suspend fun update(actor: InstructorPrincipal, id: UUID, changes: ExerciseChanges): Exercise {
        val exercise = get(actor, id)
        changes.name?.let(::requireName)
        changes.windDirDeg?.let { if (it < 0.0 || it >= 360.0) invalid("windDirDeg", "Wind direction must be 0–359 degrees") }
        changes.windSpeedMps?.let { if (it < 0.0) invalid("windSpeedMps", "Wind speed can't be negative") }
        if (changes.profileId != null && changes.profileId != exercise.profileId) {
            ensureCriteriaValid(templateFor(changes.profileId), exercise.criteria)
        }
        val updated = exercises.update(id, changes) ?: throw NotFoundException("Exercise not found")
        notifier.exerciseChanged(id)
        return updated
    }

    suspend fun setCriteria(actor: InstructorPrincipal, id: UUID, criteria: CriteriaOverrides): Exercise {
        val exercise = get(actor, id)
        ensureCriteriaValid(templateFor(exercise.profileId), criteria)
        val updated = exercises.setCriteria(id, criteria) ?: throw NotFoundException("Exercise not found")
        notifier.exerciseChanged(id)
        return updated
    }

    suspend fun setInstructors(actor: InstructorPrincipal, id: UUID, instructorIds: List<UUID>): Exercise {
        get(actor, id)
        if (instructorIds.isEmpty()) invalid("instructorIds", "An exercise needs at least one instructor")
        val found = instructors.findByIds(instructorIds).map { it.id }.toSet()
        val missing = instructorIds.filterNot { it in found }
        if (missing.isNotEmpty()) invalid("instructorIds", "Unknown instructors: ${missing.joinToString()}")
        return exercises.setInstructors(id, instructorIds) ?: throw NotFoundException("Exercise not found")
    }

    suspend fun delete(actor: InstructorPrincipal, id: UUID) {
        val exercise = get(actor, id)
        if (exercise.state == ExerciseState.RUNNING) throw ConflictException("Stop the exercise before deleting it")
        val assigned = devices.listInExercise(id)
        exercises.delete(id) // devices.exercise_id becomes NULL (ON DELETE SET NULL)
        assigned.forEach { notifier.deviceChanged(it.id, id, null) }
    }

    /** What devices in the exercise use: the template with the exercise's criteria applied. */
    suspend fun effectiveProfile(actor: InstructorPrincipal, id: UUID): SensorProfile {
        val exercise = get(actor, id)
        return templateFor(exercise.profileId).withOverrides(exercise.criteria)
    }

    private fun canAccess(actor: InstructorPrincipal, exercise: Exercise) =
        actor.isAdmin || actor.id in exercise.instructorIds

    private suspend fun templateFor(profileId: UUID): SensorProfile =
        profiles.find(profileId)?.definition ?: invalid("profileId", "Unknown profile")

    private fun ensureCriteriaValid(template: SensorProfile, criteria: CriteriaOverrides) {
        val errors = ProfileValidator.validateOverrides(template, criteria)
        if (errors.isNotEmpty()) throw ValidationException(errors)
    }

    private fun requireName(name: String) {
        if (name.isBlank()) invalid("name", "Name is required")
    }

    private fun invalid(field: String, message: String): Nothing =
        throw ValidationException(listOf(ValidationError(null, field, message)))
}

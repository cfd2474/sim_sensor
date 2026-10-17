package com.taksolutions.simsensor.server.exercises

import com.taksolutions.simsensor.profile.SensorProfile
import com.taksolutions.simsensor.profile.ValidationError
import com.taksolutions.simsensor.server.api.ConflictException
import com.taksolutions.simsensor.server.api.NotFoundException
import com.taksolutions.simsensor.server.api.ValidationException
import com.taksolutions.simsensor.server.devices.DeviceRepository
import com.taksolutions.simsensor.server.events.EventLog
import com.taksolutions.simsensor.server.events.EventTypes
import com.taksolutions.simsensor.server.events.LoggedEvent
import com.taksolutions.simsensor.server.instructors.InstructorPrincipal
import com.taksolutions.simsensor.server.instructors.InstructorRepository
import com.taksolutions.simsensor.server.profiles.ExerciseMonitors
import com.taksolutions.simsensor.server.profiles.ProfileRepository
import com.taksolutions.simsensor.server.profiles.StoredProfile
import com.taksolutions.simsensor.server.realtime.ChangeNotifier
import java.util.UUID

/**
 * Exercises (docs/DESIGN.md §3.3). Alarm levels come from the monitors the phones use.
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
    private val eventLog: EventLog,
    private val clock: java.time.Clock = java.time.Clock.systemUTC(),
    private val monitors: ExerciseMonitors = ExerciseMonitors(profiles),
) {
    suspend fun list(actor: InstructorPrincipal): List<Exercise> =
        if (actor.isAdmin) exercises.listAll() else exercises.listForInstructor(actor.id)

    suspend fun get(actor: InstructorPrincipal, id: UUID): Exercise {
        val exercise = exercises.find(id)
        if (exercise == null || !canAccess(actor, exercise)) throw NotFoundException("Exercise not found")
        return exercise
    }

    /** The exercise's name for log entries, without an access check (null when it no longer exists). */
    suspend fun nameOf(id: UUID): String? = exercises.find(id)?.name

    suspend fun create(
        actor: InstructorPrincipal,
        name: String,
        profileId: UUID,
        o2Displacement: Boolean,
        location: ExerciseLocation? = null,
    ): Exercise {
        requireName(name)
        location?.let(::requireLocation)
        templateFor(profileId)
        return exercises.create(name, profileId, o2Displacement, createdBy = actor.id, location = location?.let(::trimmed))
    }

    private fun requireLocation(l: ExerciseLocation) {
        if (l.label.isBlank() || l.label.length > MAX_LOCATION) invalid("location", "The location needs a name of up to $MAX_LOCATION characters")
        if (!l.lat.isFinite() || l.lat !in -90.0..90.0 || !l.lon.isFinite() || l.lon !in -180.0..180.0) invalid("location", "The location's coordinates are not valid")
    }

    private fun trimmed(l: ExerciseLocation) = l.copy(label = l.label.trim())

    /** Changing the default monitor reaches every phone without a monitor of its own. */
    suspend fun update(actor: InstructorPrincipal, id: UUID, changes: ExerciseChanges): Exercise {
        val exercise = get(actor, id)
        changes.name?.let(::requireName)
        changes.windDirDeg?.let { if (it < 0.0 || it >= 360.0) invalid("windDirDeg", "Wind direction must be 0–359 degrees") }
        changes.windSpeedMps?.let { if (it < 0.0) invalid("windSpeedMps", "Wind speed can't be negative") }
        changes.location?.let(::requireLocation)
        if (changes.profileId != null && changes.profileId != exercise.profileId) templateFor(changes.profileId)
        val updated = exercises.update(id, changes.copy(location = changes.location?.let(::trimmed))) ?: throw NotFoundException("Exercise not found")
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

    /** Lifecycle actions (docs/DESIGN.md §9). */
    enum class Action { START, PAUSE, RESUME, END, RESET }

    private data class Step(val to: ExerciseState, val open: Boolean, val close: Boolean, val clock: ClockChange)

    /**
     * Moves the exercise through its lifecycle:
     * draft/ended → START → running → PAUSE ⇄ RESUME → END → ended. RESET (from any state)
     * ends the open session, releases every override and returns to draft.
     * Each run from START to END is one session; telemetry is tagged with it.
     */
    suspend fun transition(actor: InstructorPrincipal, id: UUID, action: Action): Exercise {
        val exercise = get(actor, id)
        val from = exercise.state
        val step = when (action) {
            Action.START -> Step(ExerciseState.RUNNING, open = true, close = false, ClockChange.RESTART)
                .takeIf { from == ExerciseState.DRAFT || from == ExerciseState.ENDED }
            Action.PAUSE -> Step(ExerciseState.PAUSED, open = false, close = false, ClockChange.FREEZE).takeIf { from == ExerciseState.RUNNING }
            Action.RESUME -> Step(ExerciseState.RUNNING, open = false, close = false, ClockChange.RESUME).takeIf { from == ExerciseState.PAUSED }
            Action.END -> Step(ExerciseState.ENDED, open = false, close = true, ClockChange.FREEZE)
                .takeIf { from == ExerciseState.RUNNING || from == ExerciseState.PAUSED }
            Action.RESET -> Step(ExerciseState.DRAFT, open = false, close = true, ClockChange.ZERO).takeIf { from != ExerciseState.ARCHIVED }
        } ?: throw ConflictException("Can't ${action.name.lowercase()} an exercise that is ${from.dbValue}")
        val to = step.to

        if (action == Action.RESET) {
            val ids = devices.listInExercise(id).map { it.id }
            if (ids.isNotEmpty()) devices.releaseOverrides(ids, null)
            notifier.overridesChanged(id, ids)
        }
        val updated = exercises.transition(id, to, step.open, step.close, step.clock, clock.instant())
            ?: throw NotFoundException("Exercise not found")
        eventLog.record(
            EventTypes.EXERCISE_STATE, exerciseId = id, actorId = actor.id,
            data = mapOf("action" to action.name, "from" to from.dbValue, "to" to to.dbValue),
        )
        notifier.exerciseChanged(id)
        return updated
    }

    /** Newest first. */
    suspend fun events(actor: InstructorPrincipal, id: UUID, limit: Int, beforeId: Long?): List<LoggedEvent> {
        get(actor, id)
        return eventLog.forExercise(id, limit.coerceIn(1, 500), beforeId)
    }

    /** Monitors the exercise's phones use: the default first, then the others. */
    suspend fun monitors(actor: InstructorPrincipal, id: UUID): List<StoredProfile> {
        val exercise = get(actor, id)
        return monitors.inUse(exercise, devices.listInExercise(id))
    }

    /** Every gas of the exercise's monitors in one profile (leak preview, overrides). */
    suspend fun combinedMonitor(actor: InstructorPrincipal, id: UUID): SensorProfile = monitors.combined(monitors(actor, id))


    private fun canAccess(actor: InstructorPrincipal, exercise: Exercise) =
        actor.isAdmin || actor.id in exercise.instructorIds

    private suspend fun templateFor(profileId: UUID): SensorProfile =
        profiles.find(profileId)?.definition ?: invalid("profileId", "Unknown monitor")

    private fun requireName(name: String) {
        if (name.isBlank()) invalid("name", "Name is required")
    }

    private fun invalid(field: String, message: String): Nothing =
        throw ValidationException(listOf(ValidationError(null, field, message)))
}

private const val MAX_LOCATION = 200

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
import com.taksolutions.simsensor.server.events.EventLog
import com.taksolutions.simsensor.server.events.EventTypes
import com.taksolutions.simsensor.server.events.LoggedEvent
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
    private val eventLog: EventLog,
    private val clock: java.time.Clock = java.time.Clock.systemUTC(),
    /** Gases used by the exercise's leaks (to keep a meter-setup change consistent). */
    private val sourceGases: suspend (UUID) -> Set<String> = { emptySet() },
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
            val template = templateFor(changes.profileId)
            ensureCriteriaValid(template, exercise.criteria)
            val missing = sourceGases(id).filter { template.channel(it) == null }
            if (missing.isNotEmpty()) invalid("profileId", "Leaks use gases this meter setup lacks: ${missing.joinToString()}")
        }
        val updated = exercises.update(id, changes) ?: throw NotFoundException("Exercise not found")
        notifier.exerciseChanged(id)
        return updated
    }

    suspend fun setCriteria(actor: InstructorPrincipal, id: UUID, criteria: CriteriaOverrides): Exercise {
        val exercise = get(actor, id)
        ensureCriteriaValid(templateFor(exercise.profileId), criteria)
        val updated = exercises.setCriteria(id, criteria) ?: throw NotFoundException("Exercise not found")
        eventLog.record(
            EventTypes.CRITERIA_CHANGED, exerciseId = id, actorId = actor.id,
            data = criteria.channels.mapValues { (_, o) -> describe(o) },
        )
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

    /** What devices in the exercise use: the template with the exercise's criteria applied. */
    suspend fun effectiveProfile(actor: InstructorPrincipal, id: UUID): SensorProfile {
        val exercise = get(actor, id)
        return templateFor(exercise.profileId).withOverrides(exercise.criteria)
    }

    /** "A1=25, A2=50, A2 latching=no" for the event log. */
    private fun describe(o: com.taksolutions.simsensor.profile.ChannelOverride): String = listOfNotNull(
        o.lowAlarm?.let { "A1=$it" }, o.highAlarm?.let { "A2=$it" }, o.stelAlarm?.let { "stel=$it" },
        o.twaAlarm?.let { "twa=$it" }, o.rangeMax?.let { "rangeMax=$it" }, o.baseline?.let { "baseline=$it" },
        o.t90Seconds?.let { "response=${it}s" },
        o.lowAlarmLatching?.let { "A1 latching=${yesNo(it)}" }, o.highAlarmLatching?.let { "A2 latching=${yesNo(it)}" },
        o.stelAlarmLatching?.let { "STEL latching=${yesNo(it)}" }, o.twaAlarmLatching?.let { "TWA latching=${yesNo(it)}" },
    ).joinToString(", ")

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"

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

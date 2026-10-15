package com.taksolutions.simsensor.server.beacons

import com.taksolutions.simsensor.profile.ValidationError
import com.taksolutions.simsensor.server.api.ConflictException
import com.taksolutions.simsensor.server.api.ValidationException
import com.taksolutions.simsensor.server.events.EventLog
import com.taksolutions.simsensor.server.events.EventTypes
import com.taksolutions.simsensor.server.exercises.ExerciseRepository
import com.taksolutions.simsensor.server.exercises.ExerciseService
import com.taksolutions.simsensor.server.instructors.InstructorPrincipal
import com.taksolutions.simsensor.server.realtime.ChangeNotifier
import com.taksolutions.simsensor.server.sources.SourceRepository
import com.taksolutions.simsensor.source.BeaconRef
import com.taksolutions.simsensor.source.BeaconShape
import java.util.UUID

/**
 * The beacons of an exercise (its Devices tab). Phones listen only for these, so a beacon not in
 * the exercise makes no readings there. A beacon may belong to several exercises at once: each
 * exercise's phones use only that exercise's leaks, so a shared beacon doesn't interfere.
 */
class ExerciseBeacons(
    private val beacons: BeaconRepository,
    private val inventory: BeaconService,
    private val exercises: ExerciseService,
    private val exerciseRepository: ExerciseRepository,
    private val sources: SourceRepository,
    private val notifier: ChangeNotifier,
    private val eventLog: EventLog,
) {
    suspend fun list(actor: InstructorPrincipal, exerciseId: UUID): List<BeaconRef> {
        exercises.get(actor, exerciseId)
        return beacons.inExercise(exerciseId)
    }

    /** For the phones' snapshot (no access check: the device is in the exercise). */
    suspend fun forSnapshot(exerciseId: UUID): List<BeaconRef> = beacons.inExercise(exerciseId)

    suspend fun add(actor: InstructorPrincipal, exerciseId: UUID, beaconId: UUID): List<BeaconRef> {
        exercises.get(actor, exerciseId)
        val beacon = inventory.require(beaconId)
        if (beacons.addToExercise(exerciseId, beaconId)) changed(actor, exerciseId, EventTypes.BEACON_ADDED, beacon.beacon.label)
        return beacons.inExercise(exerciseId)
    }

    /** Refused while one of the exercise's leaks uses the beacon. */
    suspend fun remove(actor: InstructorPrincipal, exerciseId: UUID, beaconId: UUID): List<BeaconRef> {
        exercises.get(actor, exerciseId)
        val beacon = inventory.require(beaconId)
        val leaks = sources.list(exerciseId).filter { (it.shape as? BeaconShape)?.beaconId == beaconId.toString() }
        if (leaks.isNotEmpty()) throw ConflictException("Leaks use this beacon: ${leaks.joinToString { it.name }}. Remove them first.")
        if (beacons.removeFromExercise(exerciseId, beaconId)) changed(actor, exerciseId, EventTypes.BEACON_REMOVED, beacon.beacon.label)
        return beacons.inExercise(exerciseId)
    }

    /** 422 unless the beacon of a beacon leak belongs to the exercise. */
    suspend fun requireMember(exerciseId: UUID, beaconId: String) {
        val id = runCatching { UUID.fromString(beaconId) }.getOrNull()
        if (id == null || beacons.inExercise(exerciseId).none { it.id == beaconId }) {
            throw ValidationException(listOf(ValidationError(null, "beaconId", "Add the beacon to this exercise first (Devices tab)")))
        }
    }

    private suspend fun changed(actor: InstructorPrincipal, exerciseId: UUID, type: String, label: String) {
        exerciseRepository.bumpVersion(exerciseId)
        notifier.exerciseChanged(exerciseId)
        eventLog.record(type, exerciseId = exerciseId, actorId = actor.id, data = mapOf("beacon" to label))
    }
}

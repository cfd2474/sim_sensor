package com.taksolutions.simsensor.server.devices

import com.taksolutions.simsensor.profile.SensorProfile
import com.taksolutions.simsensor.protocol.ExerciseSnapshot
import com.taksolutions.simsensor.protocol.OverridesUpdate
import com.taksolutions.simsensor.protocol.ReadingOverride
import com.taksolutions.simsensor.server.exercises.Exercise
import com.taksolutions.simsensor.server.exercises.ExerciseRepository
import com.taksolutions.simsensor.server.profiles.ExerciseMonitors
import com.taksolutions.simsensor.server.profiles.StoredProfile
import com.taksolutions.simsensor.server.sources.SourceRepository
import com.taksolutions.simsensor.source.ExerciseClock
import java.time.Clock
import java.util.UUID

/** Builds the state a device is sent: its exercise (with its own monitor) and its overrides. */
class DeviceStateService(
    private val devices: DeviceRepository,
    private val exercises: ExerciseRepository,
    private val monitors: ExerciseMonitors,
    private val sources: SourceRepository,
    private val clock: Clock,
    /** The exercise's beacons: phones listen for these and ignore all others. */
    private val beaconsOf: suspend (UUID) -> List<com.taksolutions.simsensor.source.BeaconRef> = { emptyList() },
) {
    /** The exercise as [device] sees it, or null in standby. */
    suspend fun snapshotFor(device: Device): ExerciseSnapshot? {
        val exerciseId = device.exerciseId ?: return null
        return snapshotsFor(exerciseId, listOf(device))[device.id]
    }

    /**
     * Snapshots for [inExercise] (devices of one exercise): the shared part is built once and
     * each distinct monitor once, so a large exercise costs a few queries.
     */
    suspend fun snapshotsFor(exerciseId: UUID, inExercise: List<Device>): Map<UUID, ExerciseSnapshot> {
        if (inExercise.isEmpty()) return emptyMap()
        val exercise = exercises.find(exerciseId) ?: return emptyMap()
        val catalog = monitors.catalog()
        val list = sources.list(exercise.id)
        val beacons = beaconsOf(exercise.id)
        val byMonitor = HashMap<UUID, ExerciseSnapshot>()
        return inExercise.mapNotNull { device ->
            val monitor = monitors.monitorOf(device, exercise, catalog) ?: return@mapNotNull null
            device.id to byMonitor.getOrPut(monitor.id) { snapshot(exercise, monitor.definition, list, beacons) }
        }.toMap()
    }

    private fun snapshot(
        exercise: Exercise,
        monitor: SensorProfile,
        list: List<com.taksolutions.simsensor.source.GasSource>,
        beacons: List<com.taksolutions.simsensor.source.BeaconRef>,
    ) =
        ExerciseSnapshot(
            exerciseId = exercise.id.toString(),
            name = exercise.name,
            version = exercise.version,
            state = exercise.state.dbValue,
            profile = monitor,
            o2Displacement = exercise.o2Displacement,
            windDirDeg = exercise.windDirDeg,
            windSpeedMps = exercise.windSpeedMps,
            sources = list,
            beacons = beacons,
            clock = if (exercise.sourcesEmit) {
                val now = clock.instant()
                ExerciseClock(exercise.elapsedS(now), now.toEpochMilli(), running = exercise.clockResumedAt != null)
            } else {
                null
            },
        )

    suspend fun overrides(device: Device): OverridesUpdate = toUpdate(device, devices.overrides(device.id))

    /** Override updates for many devices with two queries in total. */
    suspend fun overridesFor(deviceIds: Collection<UUID>): Map<UUID, OverridesUpdate> {
        val found = devices.findMany(deviceIds).filterNot { it.revoked }
        val overrides = devices.overridesFor(found.map { it.id })
        return found.associate { it.id to toUpdate(it, overrides[it.id].orEmpty()) }
    }

    private fun toUpdate(device: Device, stored: List<StoredOverride>) = OverridesUpdate(
        version = device.overridesVersion,
        overrides = stored.map {
            ReadingOverride(
                channelId = it.channelId,
                mode = it.mode,
                value = it.value,
                rampSeconds = it.rampSeconds,
                startedAtMillis = it.createdAt.toEpochMilli(),
            )
        },
    )
}

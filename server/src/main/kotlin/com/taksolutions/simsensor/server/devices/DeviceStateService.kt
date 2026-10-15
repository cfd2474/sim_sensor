package com.taksolutions.simsensor.server.devices

import com.taksolutions.simsensor.profile.withOverrides
import com.taksolutions.simsensor.protocol.ExerciseSnapshot
import com.taksolutions.simsensor.protocol.OverridesUpdate
import com.taksolutions.simsensor.protocol.ReadingOverride
import com.taksolutions.simsensor.server.exercises.ExerciseRepository
import com.taksolutions.simsensor.server.profiles.ProfileRepository
import com.taksolutions.simsensor.server.sources.SourceRepository
import com.taksolutions.simsensor.source.ExerciseClock
import java.time.Clock
import java.util.UUID

/** Builds the state a device is sent: its exercise (with effective profile) and its overrides. */
class DeviceStateService(
    private val devices: DeviceRepository,
    private val exercises: ExerciseRepository,
    private val profiles: ProfileRepository,
    private val sources: SourceRepository,
    private val clock: Clock,
) {
    suspend fun exerciseSnapshot(exerciseId: UUID): ExerciseSnapshot? {
        val exercise = exercises.find(exerciseId) ?: return null
        val template = profiles.find(exercise.profileId)?.definition ?: return null
        return ExerciseSnapshot(
            exerciseId = exercise.id.toString(),
            name = exercise.name,
            version = exercise.version,
            state = exercise.state.dbValue,
            profile = template.withOverrides(exercise.criteria),
            o2Displacement = exercise.o2Displacement,
            windDirDeg = exercise.windDirDeg,
            windSpeedMps = exercise.windSpeedMps,
            sources = sources.list(exercise.id),
            clock = if (exercise.sourcesEmit) {
                val now = clock.instant()
                ExerciseClock(exercise.elapsedS(now), now.toEpochMilli(), running = exercise.clockResumedAt != null)
            } else {
                null
            },
        )
    }

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

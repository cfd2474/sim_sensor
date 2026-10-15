package com.taksolutions.simsensor.server.profiles

import com.taksolutions.simsensor.profile.ChannelConfig
import com.taksolutions.simsensor.profile.SensorCatalog
import com.taksolutions.simsensor.profile.SensorProfile
import com.taksolutions.simsensor.server.devices.Device
import com.taksolutions.simsensor.server.exercises.Exercise
import java.util.UUID

/**
 * Which monitor each phone imitates. A monitor is a stored profile (sensors, alarm levels,
 * latching, response times). A phone uses its own monitor when one is set, otherwise the
 * exercise's default monitor.
 */
class ExerciseMonitors(private val profiles: ProfileRepository) {

    /** Every monitor by id (a short list; read once per use). */
    suspend fun catalog(): Map<UUID, StoredProfile> = profiles.list().associateBy { it.id }

    /** The monitor [device] uses in [exercise], from [catalog]. */
    fun monitorOf(device: Device, exercise: Exercise, catalog: Map<UUID, StoredProfile>): StoredProfile? =
        device.monitorId?.let(catalog::get) ?: catalog[exercise.profileId]

    /** Monitors used in the exercise: the default first, then the others by name. */
    suspend fun inUse(exercise: Exercise, devices: List<Device>): List<StoredProfile> {
        val catalog = catalog()
        val default = catalog[exercise.profileId]
        val others = devices.mapNotNull { it.monitorId }.distinct().mapNotNull(catalog::get)
            .filter { it.id != exercise.profileId }
            .sortedBy { it.definition.name.lowercase() }
        return listOfNotNull(default) + others
    }

    /**
     * One profile with every gas of [monitors], each defined by the first monitor that has it
     * (the default monitor wins). Used where a gas matters for the whole exercise: the leak
     * preview and instructor overrides.
     */
    fun combined(monitors: List<StoredProfile>): SensorProfile {
        val channels = LinkedHashMap<String, ChannelConfig>()
        monitors.forEach { m -> m.definition.channels.forEach { channels.putIfAbsent(it.id, it) } }
        return SensorProfile(name = monitors.firstOrNull()?.definition?.name ?: "", channels = channels.values.toList())
    }

    /**
     * What a leak may release: every catalog sensor, defined as the monitors define it where one
     * has it (the built-in first), otherwise with the catalog's defaults.
     */
    suspend fun allGases(): SensorProfile {
        val fromMonitors = combined(catalog().values.sortedByDescending { it.builtin })
        val missing = SensorCatalog.SENSORS.filter { fromMonitors.channel(it.id) == null }
        return fromMonitors.copy(channels = fromMonitors.channels + missing)
    }
}

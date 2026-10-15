package com.taksolutions.simsensor.server.devices

import com.taksolutions.simsensor.profile.ValidationError
import com.taksolutions.simsensor.protocol.CommandKind
import com.taksolutions.simsensor.protocol.DeviceCommand
import com.taksolutions.simsensor.protocol.OverrideMode
import com.taksolutions.simsensor.server.api.ConflictException
import com.taksolutions.simsensor.server.api.NotFoundException
import com.taksolutions.simsensor.server.api.ValidationException
import com.taksolutions.simsensor.server.events.EventLog
import com.taksolutions.simsensor.server.events.EventTypes
import com.taksolutions.simsensor.server.exercises.ExerciseService
import com.taksolutions.simsensor.server.instructors.InstructorPrincipal
import com.taksolutions.simsensor.server.instructors.requireAdmin
import com.taksolutions.simsensor.server.realtime.ChangeNotifier
import java.time.Clock
import java.util.UUID

/** Which devices of an exercise an override or command applies to. Exactly one is set. */
data class DeviceTarget(
    val deviceIds: List<UUID>? = null,
    val groupId: UUID? = null,
    val all: Boolean = false,
)

/**
 * The device fleet and control of devices in exercises.
 *
 * Every instructor sees the whole fleet (devices are shared agency equipment). Changing a
 * device requires access to the exercise it is in; a device in an exercise the caller can't
 * reach can't be taken over, except by an admin.
 */
class DeviceService(
    private val devices: DeviceRepository,
    private val groups: DeviceGroupRepository,
    private val exercises: ExerciseService,
    private val exerciseRepository: com.taksolutions.simsensor.server.exercises.ExerciseRepository,
    private val profiles: com.taksolutions.simsensor.server.profiles.ProfileRepository,
    private val state: DeviceStateService,
    private val notifier: ChangeNotifier,
    private val eventLog: EventLog,
    private val clock: Clock,
) {
    suspend fun list(): List<Device> = devices.list()

    suspend fun get(id: UUID): Device = devices.find(id) ?: throw NotFoundException("Device not found")

    suspend fun listInExercise(actor: InstructorPrincipal, exerciseId: UUID): List<Device> {
        exercises.get(actor, exerciseId)
        return devices.listInExercise(exerciseId)
    }

    suspend fun rename(actor: InstructorPrincipal, id: UUID, name: String): Device {
        val device = requireControllable(actor, id)
        if (name.isBlank() || name.length > MAX_NAME) invalid("name", "Name must be 1–$MAX_NAME characters")
        val renamed = devices.rename(id, name) ?: throw NotFoundException("Device not found")
        notifier.deviceChanged(id, device.exerciseId, device.exerciseId)
        return renamed
    }

    suspend fun setGroup(actor: InstructorPrincipal, id: UUID, groupId: UUID?): Device {
        requireControllable(actor, id)
        if (groupId != null && groups.find(groupId) == null) invalid("groupId", "Unknown group")
        return devices.setGroup(id, groupId) ?: throw NotFoundException("Device not found")
    }

    /** Moves a device into an exercise (or out of any, with null). Clears its overrides. */
    suspend fun assign(actor: InstructorPrincipal, id: UUID, exerciseId: UUID?): Device {
        val device = requireControllable(actor, id)
        if (device.revoked) throw ConflictException("The device has been revoked")
        val target = exerciseId?.let { exercises.get(actor, it) }
        val updated = devices.assign(id, exerciseId) ?: throw NotFoundException("Device not found")
        // Names, not ids: the log is read by instructors (after-action review).
        val from = device.exerciseId?.let { exercises.nameOf(it) } ?: STANDBY
        eventLog.record(
            EventTypes.DEVICE_ASSIGNED, exerciseId = exerciseId ?: device.exerciseId, deviceId = id, actorId = actor.id,
            data = mapOf("from" to from, "to" to (target?.name ?: STANDBY)),
        )
        notifier.deviceChanged(id, device.exerciseId, exerciseId)
        return updated
    }

    /** Admin only. The device's token stops working and its connection is closed. */
    suspend fun revoke(actor: InstructorPrincipal, id: UUID): Device {
        requireAdmin(actor)
        val device = get(id)
        val revoked = devices.revoke(id, clock.instant()) ?: throw NotFoundException("Device not found")
        eventLog.record(EventTypes.DEVICE_REVOKED, exerciseId = device.exerciseId, deviceId = id, actorId = actor.id)
        notifier.deviceChanged(id, device.exerciseId, null)
        return revoked
    }

    /**
     * Admin only, revoked devices only: removes the device, its telemetry and its events for
     * good (decommissioning, load-test cleanup). Revoke first so a connected phone is cut off.
     */
    suspend fun delete(actor: InstructorPrincipal, id: UUID) {
        requireAdmin(actor)
        val device = get(id)
        if (!device.revoked) throw ConflictException("Revoke the device before deleting it")
        devices.delete(id)
    }

    suspend fun setOverride(actor: InstructorPrincipal, exerciseId: UUID, target: DeviceTarget, spec: OverrideSpec): List<UUID> {
        exercises.get(actor, exerciseId)
        validate(actor, exerciseId, spec)
        val ids = resolve(exerciseId, target)
        devices.setOverride(ids, exerciseId, spec, actor.id)
        notifier.overridesChanged(exerciseId, ids)
        eventLog.recordForDevices(
            EventTypes.OVERRIDE_SET, exerciseId, ids, actor.id,
            data = mapOf(
                "channel" to spec.channelId,
                "mode" to spec.mode.name,
                "value" to spec.value?.toString(),
                "rampSeconds" to spec.rampSeconds?.toString(),
            ),
        )
        return ids
    }

    suspend fun releaseOverrides(actor: InstructorPrincipal, exerciseId: UUID, target: DeviceTarget, channelId: String?): List<UUID> {
        exercises.get(actor, exerciseId)
        val ids = resolve(exerciseId, target)
        devices.releaseOverrides(ids, channelId)
        notifier.overridesChanged(exerciseId, ids)
        eventLog.recordForDevices(EventTypes.OVERRIDE_RELEASED, exerciseId, ids, actor.id, data = mapOf("channel" to channelId))
        return ids
    }

    suspend fun sendCommand(actor: InstructorPrincipal, exerciseId: UUID, target: DeviceTarget, command: DeviceCommand): List<UUID> {
        exercises.get(actor, exerciseId)
        val text = command.text
        val value = command.value
        when (command.kind) {
            CommandKind.SHOW_MESSAGE ->
                if (text.isNullOrBlank() || text.length > MAX_MESSAGE) invalid("text", "Message must be 1–$MAX_MESSAGE characters")
            CommandKind.SET_TELEMETRY_INTERVAL ->
                if (value == null || value !in 0.5..60.0) invalid("value", "Interval must be 0.5–60 seconds")
            CommandKind.SILENCE_ALARMS -> Unit
        }
        val ids = resolve(exerciseId, target)
        notifier.command(exerciseId, ids, command)
        eventLog.recordForDevices(
            EventTypes.COMMAND_SENT, exerciseId, ids, actor.id,
            data = mapOf("kind" to command.kind.name, "text" to command.text, "value" to command.value?.toString()),
        )
        return ids
    }

    /** Device ids in the exercise selected by [target]. */
    private suspend fun resolve(exerciseId: UUID, target: DeviceTarget): List<UUID> {
        val selectors = listOf(target.deviceIds != null, target.groupId != null, target.all).count { it }
        if (selectors != 1) invalid("target", "Give exactly one of deviceIds, groupId or all")
        val inExercise = devices.listInExercise(exerciseId)
        return when {
            target.all -> inExercise.map { it.id }
            target.groupId != null -> inExercise.filter { it.groupId == target.groupId }.map { it.id }
            else -> {
                val wanted = target.deviceIds!!.toSet()
                val present = inExercise.map { it.id }.toSet()
                val missing = wanted - present
                if (missing.isNotEmpty()) invalid("deviceIds", "Not in this exercise: ${missing.joinToString()}")
                wanted.toList()
            }
        }
    }

    private suspend fun validate(actor: InstructorPrincipal, exerciseId: UUID, spec: OverrideSpec) {
        val profile = exercises.combinedMonitor(actor, exerciseId)
        if (spec.channelId != null && profile.channel(spec.channelId) == null) {
            invalid("channelId", "No monitor in this exercise has '${spec.channelId}'")
        }
        val value = spec.value
        if (value != null && !value.isFinite()) invalid("value", "Value must be a number")
        when (spec.mode) {
            OverrideMode.FIXED, OverrideMode.OFFSET -> if (value == null) invalid("value", "A value is required")
            OverrideMode.RAMP -> {
                if (value == null) invalid("value", "A target value is required")
                val ramp = spec.rampSeconds
                if (ramp == null || ramp <= 0.0 || ramp > MAX_RAMP_SECONDS) invalid("rampSeconds", "Ramp time must be 1 second to 1 hour")
            }
            OverrideMode.FAULT -> Unit
        }
    }

    /**
     * Sets the monitor of phones in the exercise ([monitorId] null = the exercise default). The
     * phones get their new sensors and alarm levels within about a second.
     */
    suspend fun setMonitor(actor: InstructorPrincipal, exerciseId: UUID, deviceIds: List<UUID>, monitorId: UUID?): List<Device> {
        val exercise = exercises.get(actor, exerciseId)
        if (deviceIds.isEmpty()) invalid("deviceIds", "Choose at least one phone")
        val present = devices.listInExercise(exerciseId).map { it.id }.toSet()
        val missing = deviceIds.filterNot { it in present }
        if (missing.isNotEmpty()) invalid("deviceIds", "Not in this exercise: ${missing.joinToString()}")
        val monitor = monitorId?.let { profiles.find(it) ?: invalid("monitorId", "Unknown monitor") }
        devices.setMonitor(deviceIds, monitorId)
        exerciseRepository.bumpVersion(exerciseId)
        eventLog.recordForDevices(
            EventTypes.DEVICE_MONITOR, exerciseId, deviceIds, actor.id,
            data = mapOf("monitor" to (monitor?.definition?.name ?: "exercise default (${exercises.monitors(actor, exercise.id).first().definition.name})")),
        )
        notifier.exerciseChanged(exerciseId)
        return devices.findMany(deviceIds)
    }

    /** The device, if the caller may change it. */
    private suspend fun requireControllable(actor: InstructorPrincipal, id: UUID): Device {
        val device = get(id)
        val exerciseId = device.exerciseId
        if (exerciseId != null && !actor.isAdmin) {
            runCatching { exercises.get(actor, exerciseId) }
                .onFailure { throw ConflictException("The device is in another instructor's exercise") }
        }
        return device
    }

    private fun invalid(field: String, message: String): Nothing =
        throw ValidationException(listOf(ValidationError(null, field, message)))

    private companion object {
        const val MAX_NAME = 64
        const val STANDBY = "standby"
        const val MAX_MESSAGE = 200
        const val MAX_RAMP_SECONDS = 3600.0
    }
}

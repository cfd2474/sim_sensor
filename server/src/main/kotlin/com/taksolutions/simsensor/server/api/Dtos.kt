package com.taksolutions.simsensor.server.api

import com.taksolutions.simsensor.profile.CriteriaOverrides
import com.taksolutions.simsensor.profile.SensorProfile
import com.taksolutions.simsensor.server.exercises.Exercise
import com.taksolutions.simsensor.server.instructors.Instructor
import com.taksolutions.simsensor.server.instructors.InstructorPrincipal
import com.taksolutions.simsensor.server.profiles.StoredProfile
import kotlinx.serialization.Serializable

// Request and response bodies of the REST API (docs/openapi.yaml). Ids are UUID strings,
// times are ISO-8601 strings, roles and states are lowercase strings.

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class MeResponse(val id: String, val email: String, val name: String, val role: String)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class InstructorDto(
    val id: String,
    val email: String,
    val name: String,
    val role: String,
    val disabled: Boolean,
    val createdAt: String,
)

@Serializable
data class CreateInstructorRequest(val email: String, val name: String, val password: String, val role: String = "instructor")

@Serializable
data class UpdateInstructorRequest(
    val name: String? = null,
    val role: String? = null,
    val disabled: Boolean? = null,
    val password: String? = null,
)

@Serializable
data class ProfileDto(
    val id: String,
    val version: Int,
    val builtin: Boolean,
    val updatedAt: String,
    val definition: SensorProfile,
)

@Serializable
data class ExerciseDto(
    val id: String,
    val name: String,
    val profileId: String,
    val criteria: CriteriaOverrides,
    val state: String,
    val windDirDeg: Double?,
    val windSpeedMps: Double?,
    val o2Displacement: Boolean,
    val version: Int,
    val instructorIds: List<String>,
    val createdAt: String,
    val updatedAt: String,
    /** Exercise time: [clockElapsedS] plus the time since [clockResumedAt] while it runs. */
    val clockElapsedS: Double = 0.0,
    val clockResumedAt: String? = null,
)

@Serializable
data class CreateExerciseRequest(
    val name: String,
    val profileId: String,
    val criteria: CriteriaOverrides = CriteriaOverrides.NONE,
    val o2Displacement: Boolean = false,
)

@Serializable
data class UpdateExerciseRequest(
    val name: String? = null,
    val profileId: String? = null,
    val o2Displacement: Boolean? = null,
    val windDirDeg: Double? = null,
    val windSpeedMps: Double? = null,
)

@Serializable
data class SetInstructorsRequest(val instructorIds: List<String>)

fun InstructorPrincipal.toMe() = MeResponse(id.toString(), email, name, role.dbValue)

fun Instructor.toDto() = InstructorDto(id.toString(), email, name, role.dbValue, disabled, createdAt.toString())

fun StoredProfile.toDto() = ProfileDto(id.toString(), version, builtin, updatedAt.toString(), definition)

fun Exercise.toDto() = ExerciseDto(
    id = id.toString(),
    name = name,
    profileId = profileId.toString(),
    criteria = criteria,
    state = state.dbValue,
    windDirDeg = windDirDeg,
    windSpeedMps = windSpeedMps,
    o2Displacement = o2Displacement,
    version = version,
    instructorIds = instructorIds.map(java.util.UUID::toString),
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    clockElapsedS = clockElapsedS,
    clockResumedAt = clockResumedAt?.toString(),
)

// --- Devices, enrollment, groups, exercise control (chunk 1.2) --------------------------------

@Serializable
data class CreateEnrollmentTokenRequest(
    /** "single" (QR code) or "fleet" (MDM, admins only). */
    val kind: String = "single",
    val label: String? = null,
    /** Single: default 15, max 1440. Fleet: null = never expires. */
    val ttlMinutes: Long? = null,
)

@Serializable
data class EnrollmentTokenDto(
    val id: String,
    val kind: String,
    val label: String?,
    val createdAt: String,
    val expiresAt: String?,
    val usedAt: String?,
    val revokedAt: String?,
)

/** Returned once at creation: the secret token and the QR code content. */
@Serializable
data class CreatedEnrollmentTokenDto(val token: String, val link: String, val info: EnrollmentTokenDto)

@Serializable
data class DeviceDto(
    val id: String,
    val name: String,
    val model: String?,
    val appVersion: String?,
    val groupId: String?,
    val exerciseId: String?,
    val enrolledAt: String,
    val revokedAt: String?,
    val lastSeenAt: String?,
)

@Serializable
data class UpdateDeviceRequest(
    val name: String? = null,
    /** Group id, or "" to remove the device from its group. */
    val groupId: String? = null,
)

@Serializable
data class AssignDeviceRequest(val exerciseId: String?)

@Serializable
data class DeviceGroupDto(val id: String, val name: String)

@Serializable
data class CreateDeviceGroupRequest(val name: String)

@Serializable
data class DeviceTargetDto(
    val deviceIds: List<String>? = null,
    val groupId: String? = null,
    val all: Boolean = false,
)

@Serializable
data class SetOverrideRequest(
    val target: DeviceTargetDto,
    /** Channel id, or null for every channel. */
    val channelId: String? = null,
    /** "FIXED", "RAMP", "OFFSET" or "FAULT". */
    val mode: String,
    val value: Double? = null,
    val rampSeconds: Double? = null,
)

@Serializable
data class ReleaseOverrideRequest(
    val target: DeviceTargetDto,
    /** Channel id, or null to release every override of the targeted devices. */
    val channelId: String? = null,
)

@Serializable
data class SendCommandRequest(
    val target: DeviceTargetDto,
    /** "SILENCE_ALARMS", "SHOW_MESSAGE" or "SET_TELEMETRY_INTERVAL". */
    val kind: String,
    val text: String? = null,
    val value: Double? = null,
)

/** Which devices an override or command reached. */
@Serializable
data class AffectedDevicesDto(val deviceIds: List<String>)

fun com.taksolutions.simsensor.server.devices.EnrollmentToken.toDto() = EnrollmentTokenDto(
    id.toString(), kind.dbValue, label, createdAt.toString(), expiresAt?.toString(), usedAt?.toString(), revokedAt?.toString(),
)

fun com.taksolutions.simsensor.server.devices.Device.toDto() = DeviceDto(
    id.toString(), name, model, appVersion, groupId?.toString(), exerciseId?.toString(),
    enrolledAt.toString(), revokedAt?.toString(), lastSeenAt?.toString(),
)

fun com.taksolutions.simsensor.server.devices.DeviceGroup.toDto() = DeviceGroupDto(id.toString(), name)

// --- Console support (chunk 1.4) ----------------------------------------------------------------

@Serializable
data class ConsoleConfigDto(val version: String, val tileUrl: String, val tileAttribution: String)

@Serializable
data class EventDto(
    val id: Long,
    val ts: String,
    val type: String,
    val deviceId: String?,
    val deviceName: String?,
    val actorId: String?,
    val actorName: String?,
    val data: Map<String, String?>,
)

fun com.taksolutions.simsensor.server.events.LoggedEvent.toDto() = EventDto(
    id, ts.toString(), type, deviceId?.toString(), deviceName, actorId?.toString(), actorName, data,
)

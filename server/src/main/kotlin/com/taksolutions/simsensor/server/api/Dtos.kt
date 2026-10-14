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
)

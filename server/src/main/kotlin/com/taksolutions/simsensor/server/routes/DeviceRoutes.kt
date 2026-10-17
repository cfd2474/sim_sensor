package com.taksolutions.simsensor.server.routes

import com.taksolutions.simsensor.profile.ValidationError
import com.taksolutions.simsensor.protocol.CommandKind
import com.taksolutions.simsensor.protocol.DeviceCommand
import com.taksolutions.simsensor.protocol.EnrollRequest
import com.taksolutions.simsensor.protocol.OverrideMode
import com.taksolutions.simsensor.server.api.UpdateEnrollmentTokenRequest
import com.taksolutions.simsensor.server.api.AffectedDevicesDto
import com.taksolutions.simsensor.server.api.AssignDeviceRequest
import com.taksolutions.simsensor.server.api.CreateDeviceGroupRequest
import com.taksolutions.simsensor.server.api.CreateEnrollmentTokenRequest
import com.taksolutions.simsensor.server.api.CreatedEnrollmentTokenDto
import com.taksolutions.simsensor.server.api.DeviceTargetDto
import com.taksolutions.simsensor.server.api.ReleaseOverrideRequest
import com.taksolutions.simsensor.server.api.SendCommandRequest
import com.taksolutions.simsensor.server.api.SetMonitorRequest
import com.taksolutions.simsensor.server.api.SetOverrideRequest
import com.taksolutions.simsensor.server.api.UpdateDeviceRequest
import com.taksolutions.simsensor.server.api.ValidationException
import com.taksolutions.simsensor.server.api.toDto
import com.taksolutions.simsensor.server.auth.actor
import com.taksolutions.simsensor.server.devices.DeviceGroupRepository
import com.taksolutions.simsensor.server.devices.DeviceService
import com.taksolutions.simsensor.server.devices.DeviceTarget
import com.taksolutions.simsensor.server.devices.EnrollmentKind
import com.taksolutions.simsensor.server.devices.EnrollmentService
import com.taksolutions.simsensor.server.devices.OverrideSpec
import com.taksolutions.simsensor.server.instructors.requireAdmin
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** Unauthenticated: a phone trades an enrollment token for its device token. */
fun Route.deviceEnrollmentRoute(enrollment: EnrollmentService) {
    post("/api/devices/enroll") {
        call.respond(HttpStatusCode.Created, enrollment.enroll(call.receive<EnrollRequest>()))
    }
}

/** Instructor routes for enrollment tokens, the fleet and groups. Mount inside `authenticate(AUTH_INSTRUCTOR)`. */
fun Route.deviceRoutes(enrollment: EnrollmentService, devices: DeviceService, groups: DeviceGroupRepository) {
    route("/api/enrollment-tokens") {
        get {
            call.respond(enrollment.list(call.actor()).map { it.toDto() })
        }
        post {
            val request = call.receive<CreateEnrollmentTokenRequest>()
            val kind = EnrollmentKind.entries.firstOrNull { it.dbValue == request.kind }
                ?: throw ValidationException(listOf(ValidationError(null, "kind", "Kind must be 'single' or 'fleet'")))
            val created = enrollment.createToken(call.actor(), kind, request.label, request.ttlMinutes)
            call.respond(HttpStatusCode.Created, CreatedEnrollmentTokenDto(created.token, created.link, created.info.toDto()))
        }
        delete("/{id}") {
            enrollment.revoke(call.actor(), call.uuidParam("id"))
            call.respond(HttpStatusCode.NoContent)
        }
        patch("/{id}") {
            val raw = call.receive<UpdateEnrollmentTokenRequest>().expiresAt
            val expiresAt = raw?.let {
                runCatching { java.time.Instant.parse(it) }.getOrNull()
                    ?: throw ValidationException(listOf(ValidationError(null, "expiresAt", "Not a valid date and time")))
            }
            call.respond(enrollment.setRetirement(call.actor(), call.uuidParam("id"), expiresAt).toDto())
        }
    }

    route("/api/devices") {
        get {
            call.respond(devices.list().map { it.toDto() })
        }
        route("/{id}") {
            get {
                call.respond(devices.get(call.uuidParam("id")).toDto())
            }
            patch {
                val request = call.receive<UpdateDeviceRequest>()
                val id = call.uuidParam("id")
                var device = devices.get(id)
                request.name?.let { device = devices.rename(call.actor(), id, it) }
                request.groupId?.let { raw ->
                    device = devices.setGroup(call.actor(), id, raw.takeIf { it.isNotEmpty() }?.let { parseUuid(it, "groupId") })
                }
                call.respond(device.toDto())
            }
            put("/exercise") {
                val request = call.receive<AssignDeviceRequest>()
                val exerciseId = request.exerciseId?.let { parseUuid(it, "exerciseId") }
                call.respond(devices.assign(call.actor(), call.uuidParam("id"), exerciseId).toDto())
            }
            post("/revoke") {
                call.respond(devices.revoke(call.actor(), call.uuidParam("id")).toDto())
            }
            delete {
                devices.delete(call.actor(), call.uuidParam("id"))
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }

    route("/api/device-groups") {
        get {
            call.respond(groups.list().map { it.toDto() })
        }
        post {
            val name = call.receive<CreateDeviceGroupRequest>().name
            if (name.isBlank()) throw ValidationException(listOf(ValidationError(null, "name", "Name is required")))
            call.respond(HttpStatusCode.Created, groups.create(name).toDto())
        }
        delete("/{id}") {
            requireAdmin(call.actor())
            groups.delete(call.uuidParam("id"))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** Live control of the devices in an exercise. Mount inside `authenticate(AUTH_INSTRUCTOR)`. */
fun Route.exerciseControlRoutes(devices: DeviceService) {
    route("/api/exercises/{id}") {
        get("/devices") {
            call.respond(devices.listInExercise(call.actor(), call.uuidParam("id")).map { it.toDto() })
        }
        put("/devices/monitor") {
            val request = call.receive<SetMonitorRequest>()
            val updated = devices.setMonitor(
                call.actor(), call.uuidParam("id"),
                request.deviceIds.map { parseUuid(it, "deviceIds") },
                request.monitorId?.let { parseUuid(it, "monitorId") },
            )
            call.respond(updated.map { it.toDto() })
        }
        post("/overrides") {
            val request = call.receive<SetOverrideRequest>()
            val mode = OverrideMode.entries.firstOrNull { it.name == request.mode.uppercase() }
                ?: throw ValidationException(listOf(ValidationError(null, "mode", "Mode must be FIXED, RAMP, OFFSET or FAULT")))
            val ids = devices.setOverride(
                call.actor(), call.uuidParam("id"), request.target.toTarget(),
                OverrideSpec(request.channelId, mode, request.value, request.rampSeconds),
            )
            call.respond(AffectedDevicesDto(ids.map { it.toString() }))
        }
        post("/overrides/release") {
            val request = call.receive<ReleaseOverrideRequest>()
            val ids = devices.releaseOverrides(call.actor(), call.uuidParam("id"), request.target.toTarget(), request.channelId)
            call.respond(AffectedDevicesDto(ids.map { it.toString() }))
        }
        post("/commands") {
            val request = call.receive<SendCommandRequest>()
            val kind = CommandKind.entries.firstOrNull { it.name == request.kind.uppercase() }
                ?: throw ValidationException(listOf(ValidationError(null, "kind", "Unknown command")))
            val ids = devices.sendCommand(
                call.actor(), call.uuidParam("id"), request.target.toTarget(), DeviceCommand(kind, request.text, request.value),
            )
            call.respond(AffectedDevicesDto(ids.map { it.toString() }))
        }
    }
}

private fun DeviceTargetDto.toTarget() = DeviceTarget(
    deviceIds = deviceIds?.map { parseUuid(it, "deviceIds") },
    groupId = groupId?.let { parseUuid(it, "groupId") },
    all = all,
)

package com.taksolutions.simsensor.server.routes

import com.taksolutions.simsensor.server.auth.actor
import com.taksolutions.simsensor.server.beacons.BeaconService
import com.taksolutions.simsensor.server.beacons.ExerciseBeacons
import com.taksolutions.simsensor.server.beacons.StoredBeacon
import com.taksolutions.simsensor.source.BeaconRef
import com.taksolutions.simsensor.source.CalibrationPoint
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/** A beacon as the console sees it: its identity, calibration and notes. */
@Serializable
data class BeaconDto(
    val id: String = "",
    val label: String,
    val uuid: String,
    val major: Int,
    val minor: Int,
    val txPowerAt1m: Double? = null,
    val pathLossN: Double = BeaconRef.DEFAULT_PATH_LOSS,
    val notes: String = "",
) {
    fun toRef() = BeaconRef(id, label, uuid, major, minor, txPowerAt1m, pathLossN)
}

fun BeaconRef.toDto() = BeaconDto(id, label, uuid, major, minor, txPowerAt1m, pathLossN)

fun StoredBeacon.toDto() = BeaconDto(beacon.id, beacon.label, beacon.uuid, beacon.major, beacon.minor, beacon.txPowerAt1m, beacon.pathLossN, notes)

/** An exercise a beacon is in. */
@Serializable
data class BeaconUseDto(val exerciseId: String, val exerciseName: String, val state: String)

/** One measurement: average signal with a phone held [distanceM] from the beacon. */
@Serializable
data class CalibrationPointDto(val distanceM: Double, val rssi: Double)

@Serializable
data class CalibrateBeaconRequest(val points: List<CalibrationPointDto>)

@Serializable
data class CalibrationFitDto(val txPowerAt1m: Double, val pathLossN: Double, val rmsErrorDb: Double)

@Serializable
data class CalibrateBeaconResponse(val beacon: BeaconDto, val fit: CalibrationFitDto)

/** Adds a beacon to an exercise. */
@Serializable
data class AddExerciseBeaconRequest(val beaconId: String)

/** Beacon inventory and the beacons of each exercise. Mount inside `authenticate(AUTH_INSTRUCTOR)`. */
fun Route.beaconRoutes(service: BeaconService, exerciseBeacons: ExerciseBeacons) {
    route("/api/exercises/{id}/beacons") {
        get { call.respond(exerciseBeacons.list(call.actor(), call.uuidParam("id")).map { it.toDto() }) }
        post {
            val beaconId = parseUuid(call.receive<AddExerciseBeaconRequest>().beaconId, "beaconId")
            call.respond(exerciseBeacons.add(call.actor(), call.uuidParam("id"), beaconId).map { it.toDto() })
        }
        delete("/{beaconId}") {
            call.respond(exerciseBeacons.remove(call.actor(), call.uuidParam("id"), call.uuidParam("beaconId")).map { it.toDto() })
        }
    }

    route("/api/beacons") {
        get { call.respond(service.list().map { it.toDto() }) }
        post {
            val dto = call.receive<BeaconDto>()
            call.respond(HttpStatusCode.Created, service.create(call.actor(), dto.toRef(), dto.notes).toDto())
        }
        put("/{id}") {
            val dto = call.receive<BeaconDto>()
            call.respond(service.update(call.actor(), call.uuidParam("id"), dto.toRef(), dto.notes).toDto())
        }
        delete("/{id}") {
            service.delete(call.actor(), call.uuidParam("id"))
            call.respond(HttpStatusCode.NoContent)
        }
        get("/{id}/exercises") {
            call.respond(service.uses(call.uuidParam("id")).map { BeaconUseDto(it.exerciseId.toString(), it.exerciseName, it.state) })
        }
        post("/{id}/calibrate") {
            val points = call.receive<CalibrateBeaconRequest>().points.map { CalibrationPoint(it.distanceM, it.rssi) }
            val (beacon, fit) = service.calibrate(call.actor(), call.uuidParam("id"), points)
            call.respond(CalibrateBeaconResponse(beacon.toDto(), CalibrationFitDto(fit.txPowerAt1m, fit.pathLossN, fit.rmsErrorDb)))
        }
    }
}

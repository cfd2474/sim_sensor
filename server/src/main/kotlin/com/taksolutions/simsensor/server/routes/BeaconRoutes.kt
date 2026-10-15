package com.taksolutions.simsensor.server.routes

import com.taksolutions.simsensor.server.auth.actor
import com.taksolutions.simsensor.server.beacons.BeaconService
import com.taksolutions.simsensor.server.beacons.StoredBeacon
import com.taksolutions.simsensor.source.BeaconRef
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

fun StoredBeacon.toDto() = BeaconDto(beacon.id, beacon.label, beacon.uuid, beacon.major, beacon.minor, beacon.txPowerAt1m, beacon.pathLossN, notes)

/** Beacon inventory. Mount inside `authenticate(AUTH_INSTRUCTOR)`. */
fun Route.beaconRoutes(service: BeaconService) {
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
    }
}

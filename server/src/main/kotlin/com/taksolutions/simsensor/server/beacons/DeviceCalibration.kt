package com.taksolutions.simsensor.server.beacons

import com.taksolutions.simsensor.protocol.CalibrateBeacon
import com.taksolutions.simsensor.protocol.CalibrationResult
import com.taksolutions.simsensor.protocol.SetBeaconEdge
import com.taksolutions.simsensor.server.api.ValidationException
import com.taksolutions.simsensor.server.events.EventLog
import com.taksolutions.simsensor.server.events.EventTypes
import java.util.UUID

/**
 * Beacon calibration sent by a phone (operator decision 2026-10-15): any enrolled phone may
 * calibrate a beacon of its current exercise. Its Settings screen sits behind the MDM settings
 * PIN when one is set, and every calibration is logged with the phone.
 */
class DeviceCalibration(
    private val beacons: BeaconRepository,
    private val inventory: BeaconService,
    private val eventLog: EventLog,
) {
    suspend fun calibrate(deviceId: UUID, exerciseId: UUID?, request: CalibrateBeacon): CalibrationResult {
        val beaconId = memberOrNull(exerciseId, request.beaconId) ?: return notInExercise(request.beaconId)
        return try {
            val (stored, fit) = inventory.calibrate(beaconId, request.points)
            eventLog.record(
                EventTypes.BEACON_CALIBRATED, exerciseId = exerciseId, deviceId = deviceId,
                data = mapOf(
                    "beacon" to stored.beacon.label,
                    "txPowerAt1m" to stored.beacon.txPowerAt1m?.toString(),
                    "pathLossN" to stored.beacon.pathLossN.toString(),
                    "points" to request.points.joinToString { "${it.distanceM} m ${it.rssi} dBm" },
                ),
            )
            CalibrationResult(request.beaconId, ok = true, stored.beacon.txPowerAt1m, stored.beacon.pathLossN, fit.rmsErrorDb, edgeRssi = stored.beacon.edgeRssi)
        } catch (e: ValidationException) {
            CalibrationResult(request.beaconId, ok = false, message = e.errors.joinToString("; ") { it.message })
        }
    }

    /** The gas edge recorded on the phone (or cleared). */
    suspend fun setEdge(deviceId: UUID, exerciseId: UUID?, request: SetBeaconEdge): CalibrationResult {
        val beaconId = memberOrNull(exerciseId, request.beaconId) ?: return notInExercise(request.beaconId)
        return try {
            val b = inventory.setEdge(beaconId, request.edgeRssi).beacon
            eventLog.record(
                EventTypes.BEACON_EDGE, exerciseId = exerciseId, deviceId = deviceId,
                data = mapOf("beacon" to b.label, "edgeRssi" to b.edgeRssi?.toString()),
            )
            CalibrationResult(request.beaconId, ok = true, b.txPowerAt1m, b.pathLossN, edgeRssi = b.edgeRssi)
        } catch (e: ValidationException) {
            CalibrationResult(request.beaconId, ok = false, message = e.errors.joinToString("; ") { it.message })
        }
    }

    /** The beacon's id when it is in the phone's current exercise. */
    private suspend fun memberOrNull(exerciseId: UUID?, beaconId: String): UUID? {
        val id = runCatching { UUID.fromString(beaconId) }.getOrNull() ?: return null
        if (exerciseId == null || beacons.inExercise(exerciseId).none { it.id == id.toString() }) return null
        return id
    }

    private fun notInExercise(beaconId: String) =
        CalibrationResult(beaconId, ok = false, message = "This beacon isn't in the phone's exercise")
}

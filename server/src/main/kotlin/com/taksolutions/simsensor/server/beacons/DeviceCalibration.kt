package com.taksolutions.simsensor.server.beacons

import com.taksolutions.simsensor.protocol.CalibrateBeacon
import com.taksolutions.simsensor.protocol.CalibrationResult
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
        val beaconId = runCatching { UUID.fromString(request.beaconId) }.getOrNull()
        if (exerciseId == null || beaconId == null || beacons.inExercise(exerciseId).none { it.id == beaconId.toString() }) {
            return CalibrationResult(request.beaconId, ok = false, message = "This beacon isn't in the phone's exercise")
        }
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
            CalibrationResult(request.beaconId, ok = true, stored.beacon.txPowerAt1m, stored.beacon.pathLossN, fit.rmsErrorDb)
        } catch (e: ValidationException) {
            CalibrationResult(request.beaconId, ok = false, message = e.errors.joinToString("; ") { it.message })
        }
    }
}

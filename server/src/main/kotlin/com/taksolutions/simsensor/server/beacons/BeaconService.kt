package com.taksolutions.simsensor.server.beacons

import com.taksolutions.simsensor.profile.ValidationError
import com.taksolutions.simsensor.server.api.ConflictException
import com.taksolutions.simsensor.server.api.NotFoundException
import com.taksolutions.simsensor.server.api.ValidationException
import com.taksolutions.simsensor.server.exercises.ExerciseRepository
import com.taksolutions.simsensor.server.instructors.InstructorPrincipal
import com.taksolutions.simsensor.server.instructors.requireAdmin
import com.taksolutions.simsensor.server.realtime.ChangeNotifier
import com.taksolutions.simsensor.source.BeaconRef
import java.util.UUID

/**
 * The beacon inventory (docs/DESIGN.md §8.2). Everyone reads it; admins change it. Which
 * exercises use a beacon is [ExerciseBeacons]' business; an edit still reaches their phones,
 * which listen for the beacon's identity and range with its calibration.
 */
class BeaconService(
    private val beacons: BeaconRepository,
    private val exercises: ExerciseRepository,
    private val notifier: ChangeNotifier,
) {

    suspend fun list(): List<StoredBeacon> = beacons.list()

    suspend fun create(actor: InstructorPrincipal, beacon: BeaconRef, notes: String): StoredBeacon {
        requireAdmin(actor)
        return beacons.create(validated(beacon), notes.trim())
    }

    suspend fun update(actor: InstructorPrincipal, id: UUID, beacon: BeaconRef, notes: String): StoredBeacon {
        requireAdmin(actor)
        val updated = beacons.update(id, validated(beacon), notes.trim()) ?: throw NotFoundException("Beacon not found")
        for (use in beacons.exercisesWith(id)) {
            exercises.bumpVersion(use.exerciseId)
            notifier.exerciseChanged(use.exerciseId)
        }
        return updated
    }

    suspend fun delete(actor: InstructorPrincipal, id: UUID) {
        requireAdmin(actor)
        val users = beacons.exercisesWith(id)
        if (users.isNotEmpty()) throw ConflictException("The beacon is in these exercises: ${users.joinToString { it.exerciseName }}. Remove it there first.")
        if (!beacons.delete(id)) throw NotFoundException("Beacon not found")
    }

    /** 404 unless the beacon exists. */
    suspend fun require(id: UUID): StoredBeacon = beacons.find(id) ?: throw NotFoundException("Beacon not found")

    private fun validated(b: BeaconRef): BeaconRef {
        val errors = buildList {
            if (b.label.isBlank() || b.label.length > MAX_LABEL) add(ValidationError(null, "label", "Label must be 1–$MAX_LABEL characters"))
            if (canonicalUuid(b.uuid) == null) {
                add(ValidationError(null, "uuid", "UUID must have 32 hex digits, with or without dashes (e.g. EB14B62F218B4EEE8F15B6322ADBB54E)"))
            }
            if (b.major !in 0..65535) add(ValidationError(null, "major", "Major must be 0–65535"))
            if (b.minor !in 0..65535) add(ValidationError(null, "minor", "Minor must be 0–65535"))
            b.txPowerAt1m?.let { if (!it.isFinite() || it !in -110.0..0.0) add(ValidationError(null, "txPowerAt1m", "Power at 1 m must be −110 to 0 dBm")) }
            if (!b.pathLossN.isFinite() || b.pathLossN !in 1.5..4.5) add(ValidationError(null, "pathLossN", "Path loss must be 1.5–4.5"))
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
        return b.copy(label = b.label.trim(), uuid = canonicalUuid(b.uuid)!!)
    }

    private fun invalid(field: String, message: String): Nothing =
        throw ValidationException(listOf(ValidationError(null, field, message)))

    companion object {
        const val MAX_LABEL = 64
        private val HEX32 = Regex("^[0-9a-fA-F]{32}$")

        /**
         * The UUID in its standard form (lower case, 8-4-4-4-12 dashes), from what a beacon app
         * shows: with or without dashes, any case, braces or spaces around it. Null if it isn't
         * 32 hex digits.
         */
        fun canonicalUuid(raw: String): String? {
            val hex = raw.trim().removePrefix("{").removeSuffix("}").replace("-", "").replace(" ", "")
            if (!HEX32.matches(hex)) return null
            val h = hex.lowercase()
            return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
        }
    }
}

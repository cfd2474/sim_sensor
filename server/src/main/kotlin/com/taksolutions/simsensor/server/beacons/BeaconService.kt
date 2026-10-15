package com.taksolutions.simsensor.server.beacons

import com.taksolutions.simsensor.profile.ValidationError
import com.taksolutions.simsensor.server.api.ConflictException
import com.taksolutions.simsensor.server.api.NotFoundException
import com.taksolutions.simsensor.server.api.ValidationException
import com.taksolutions.simsensor.server.instructors.InstructorPrincipal
import com.taksolutions.simsensor.server.instructors.requireAdmin
import com.taksolutions.simsensor.source.BeaconRef
import java.util.UUID

/**
 * The beacon inventory (docs/DESIGN.md §8.2). Everyone reads it; admins change it. Which
 * exercises use a beacon is [ExerciseBeacons]' business.
 */
class BeaconService(private val beacons: BeaconRepository) {

    suspend fun list(): List<StoredBeacon> = beacons.list()

    suspend fun create(actor: InstructorPrincipal, beacon: BeaconRef, notes: String): StoredBeacon {
        requireAdmin(actor)
        return beacons.create(validated(beacon), notes.trim())
    }

    suspend fun update(actor: InstructorPrincipal, id: UUID, beacon: BeaconRef, notes: String): StoredBeacon {
        requireAdmin(actor)
        return beacons.update(id, validated(beacon), notes.trim()) ?: throw NotFoundException("Beacon not found")
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
            if (!UUID_PATTERN.matches(b.uuid.trim())) add(ValidationError(null, "uuid", "UUID must look like fda50693-a4e2-4fb1-afcf-c6eb07647825"))
            if (b.major !in 0..65535) add(ValidationError(null, "major", "Major must be 0–65535"))
            if (b.minor !in 0..65535) add(ValidationError(null, "minor", "Minor must be 0–65535"))
            b.txPowerAt1m?.let { if (!it.isFinite() || it !in -110.0..0.0) add(ValidationError(null, "txPowerAt1m", "Power at 1 m must be −110 to 0 dBm")) }
            if (!b.pathLossN.isFinite() || b.pathLossN !in 1.5..4.5) add(ValidationError(null, "pathLossN", "Path loss must be 1.5–4.5"))
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
        return b.copy(label = b.label.trim(), uuid = b.uuid.trim().lowercase())
    }

    private fun invalid(field: String, message: String): Nothing =
        throw ValidationException(listOf(ValidationError(null, field, message)))

    private companion object {
        const val MAX_LABEL = 64
        val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}

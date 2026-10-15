package com.taksolutions.simsensor.server.beacons

import com.taksolutions.simsensor.profile.ValidationError
import com.taksolutions.simsensor.server.api.ConflictException
import com.taksolutions.simsensor.server.api.NotFoundException
import com.taksolutions.simsensor.server.api.ValidationException
import com.taksolutions.simsensor.server.instructors.InstructorPrincipal
import com.taksolutions.simsensor.server.instructors.requireAdmin
import com.taksolutions.simsensor.source.BeaconRef
import com.taksolutions.simsensor.source.BeaconShape
import com.taksolutions.simsensor.source.GasSource
import java.util.UUID

/**
 * The beacon inventory (docs/DESIGN.md §8.2). Everyone reads it; admins change it. A beacon
 * serves one running exercise at a time, so two exercises at the same site don't interfere.
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
        val users = beacons.uses(listOf(id))
        if (users.isNotEmpty()) throw ConflictException("Leaks in these exercises use the beacon: ${users.joinToString { it.exerciseName }}")
        if (!beacons.delete(id)) throw NotFoundException("Beacon not found")
    }

    /** The beacons [sources] refer to (for the phones' exercise snapshot). */
    suspend fun usedBy(sources: List<GasSource>): List<BeaconRef> = beacons.findMany(beaconIds(sources))

    /** 422 unless the beacon of a beacon leak exists. */
    suspend fun requireExists(beaconId: String) {
        val id = runCatching { UUID.fromString(beaconId) }.getOrNull()
        if (id == null || beacons.find(id) == null) invalid("beaconId", "Unknown beacon")
    }

    /**
     * 409 when another exercise that is running or paused already uses one of [sources]'
     * beacons: phones of both would react to the same physical beacon.
     */
    suspend fun ensureFree(exerciseId: UUID, sources: List<GasSource>) {
        val busy = beacons.uses(beaconIds(sources))
            .filter { it.exerciseId != exerciseId && (it.state == "running" || it.state == "paused") }
        if (busy.isNotEmpty()) {
            throw ConflictException("A beacon is in use by a running exercise: ${busy.joinToString { it.exerciseName }}. End it first.")
        }
    }

    private fun beaconIds(sources: List<GasSource>): List<UUID> =
        sources.mapNotNull { (it.shape as? BeaconShape)?.beaconId?.let { id -> runCatching { UUID.fromString(id) }.getOrNull() } }.distinct()

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

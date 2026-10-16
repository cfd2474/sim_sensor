package com.taksolutions.simsensor.server.beacons

import com.taksolutions.simsensor.server.api.ConflictException
import com.taksolutions.simsensor.server.db.Database
import com.taksolutions.simsensor.server.db.doubleOrNull
import com.taksolutions.simsensor.server.db.execute
import com.taksolutions.simsensor.server.db.isUniqueViolation
import com.taksolutions.simsensor.server.db.query
import com.taksolutions.simsensor.server.db.queryOne
import com.taksolutions.simsensor.server.db.uuid
import com.taksolutions.simsensor.source.BeaconRef
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/** A beacon in the inventory, with the admin's notes. */
data class StoredBeacon(val beacon: BeaconRef, val notes: String)

/** An exercise a beacon belongs to. */
data class BeaconUse(val exerciseId: UUID, val exerciseName: String, val state: String)

interface BeaconRepository {
    suspend fun list(): List<StoredBeacon>
    suspend fun find(id: UUID): StoredBeacon?
    suspend fun findMany(ids: Collection<UUID>): List<BeaconRef>

    /** Throws [ConflictException] when another beacon has the same UUID / major / minor. */
    suspend fun create(beacon: BeaconRef, notes: String): StoredBeacon
    suspend fun update(id: UUID, beacon: BeaconRef, notes: String): StoredBeacon?
    suspend fun delete(id: UUID): Boolean

    /** Exercises [beaconId] belongs to. */
    suspend fun exercisesWith(beaconId: UUID): List<BeaconUse>

    /** The beacons of an exercise (its Devices tab; what its phones listen for). */
    suspend fun inExercise(exerciseId: UUID): List<BeaconRef>

    /** Returns false when it was already there. */
    suspend fun addToExercise(exerciseId: UUID, beaconId: UUID): Boolean

    /** Returns false when it wasn't there. */
    suspend fun removeFromExercise(exerciseId: UUID, beaconId: UUID): Boolean
}

class JdbcBeaconRepository(private val db: Database) : BeaconRepository {

    override suspend fun list(): List<StoredBeacon> = db.transaction {
        query("SELECT * FROM beacons ORDER BY lower(label)", map = ::toBeacon)
    }

    override suspend fun find(id: UUID): StoredBeacon? = db.transaction {
        queryOne("SELECT * FROM beacons WHERE id = ?", id, map = ::toBeacon)
    }

    override suspend fun findMany(ids: Collection<UUID>): List<BeaconRef> = if (ids.isEmpty()) emptyList() else db.transaction {
        query("SELECT * FROM beacons WHERE id = ANY(?)", createArrayOf("uuid", ids.toTypedArray())) { toBeacon(it).beacon }
    }

    override suspend fun create(beacon: BeaconRef, notes: String): StoredBeacon = unique {
        db.transaction {
            queryOne(
                "INSERT INTO beacons (label, uuid, major, minor, tx_power_1m, path_loss_n, edge_rssi, notes) VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING *",
                beacon.label.trim(), beacon.uuid, beacon.major, beacon.minor, beacon.txPowerAt1m, beacon.pathLossN, beacon.edgeRssi, notes,
                map = ::toBeacon,
            )!!
        }
    }

    override suspend fun update(id: UUID, beacon: BeaconRef, notes: String): StoredBeacon? = unique {
        db.transaction {
            queryOne(
                """
                UPDATE beacons SET label = ?, uuid = ?, major = ?, minor = ?, tx_power_1m = ?, path_loss_n = ?, edge_rssi = ?, notes = ?, updated_at = now()
                 WHERE id = ? RETURNING *
                """.trimIndent(),
                beacon.label.trim(), beacon.uuid, beacon.major, beacon.minor, beacon.txPowerAt1m, beacon.pathLossN, beacon.edgeRssi, notes, id,
                map = ::toBeacon,
            )
        }
    }

    override suspend fun delete(id: UUID): Boolean = db.transaction {
        execute("DELETE FROM beacons WHERE id = ?", id) > 0
    }

    override suspend fun exercisesWith(beaconId: UUID): List<BeaconUse> = db.transaction {
        query(
            "SELECT e.id, e.name, e.state FROM exercise_beacons eb JOIN exercises e ON e.id = eb.exercise_id WHERE eb.beacon_id = ? ORDER BY e.name",
            beaconId,
        ) { BeaconUse(it.uuid("id"), it.getString("name"), it.getString("state")) }
    }

    override suspend fun inExercise(exerciseId: UUID): List<BeaconRef> = db.transaction {
        query(
            "SELECT b.* FROM exercise_beacons eb JOIN beacons b ON b.id = eb.beacon_id WHERE eb.exercise_id = ? ORDER BY lower(b.label)",
            exerciseId,
        ) { toBeacon(it).beacon }
    }

    override suspend fun addToExercise(exerciseId: UUID, beaconId: UUID): Boolean = db.transaction {
        execute("INSERT INTO exercise_beacons (exercise_id, beacon_id) VALUES (?, ?) ON CONFLICT DO NOTHING", exerciseId, beaconId) > 0
    }

    override suspend fun removeFromExercise(exerciseId: UUID, beaconId: UUID): Boolean = db.transaction {
        execute("DELETE FROM exercise_beacons WHERE exercise_id = ? AND beacon_id = ?", exerciseId, beaconId) > 0
    }

    private suspend fun <T> unique(block: suspend () -> T): T =
        try {
            block()
        } catch (e: SQLException) {
            if (e.isUniqueViolation()) throw ConflictException("Another beacon already has this UUID, major and minor") else throw e
        }

    private fun toBeacon(rs: ResultSet) = StoredBeacon(
        BeaconRef(
            id = rs.uuid("id").toString(),
            label = rs.getString("label"),
            uuid = rs.getString("uuid"),
            major = rs.getInt("major"),
            minor = rs.getInt("minor"),
            txPowerAt1m = rs.doubleOrNull("tx_power_1m"),
            pathLossN = rs.getDouble("path_loss_n"),
            edgeRssi = rs.doubleOrNull("edge_rssi"),
        ),
        notes = rs.getString("notes"),
    )
}

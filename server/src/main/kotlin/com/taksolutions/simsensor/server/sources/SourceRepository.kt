package com.taksolutions.simsensor.server.sources

import com.taksolutions.simsensor.server.db.Database
import com.taksolutions.simsensor.server.db.DbJson
import com.taksolutions.simsensor.server.db.JsonbParam
import com.taksolutions.simsensor.server.db.doubleOrNull
import com.taksolutions.simsensor.server.db.execute
import com.taksolutions.simsensor.server.db.query
import com.taksolutions.simsensor.server.db.queryOne
import com.taksolutions.simsensor.server.db.uuid
import com.taksolutions.simsensor.source.GasSource
import java.sql.ResultSet
import java.util.UUID

/** A stored leak and the exercise it belongs to. */
data class StoredSource(val exerciseId: UUID, val source: GasSource)

interface SourceRepository {
    suspend fun list(exerciseId: UUID): List<GasSource>
    suspend fun find(id: UUID): StoredSource?
    suspend fun create(exerciseId: UUID, source: GasSource): GasSource
    suspend fun update(id: UUID, source: GasSource): GasSource?
    suspend fun delete(id: UUID): Boolean
    suspend fun activate(id: UUID, atS: Double): GasSource?
}

/** The definition column holds the shared JSON; the id and activation time are columns. */
class JdbcSourceRepository(private val db: Database) : SourceRepository {

    override suspend fun list(exerciseId: UUID): List<GasSource> = db.transaction {
        query("SELECT * FROM gas_sources WHERE exercise_id = ? ORDER BY created_at", exerciseId) { toSource(it) }
    }

    override suspend fun find(id: UUID): StoredSource? = db.transaction {
        queryOne("SELECT * FROM gas_sources WHERE id = ?", id) { StoredSource(it.uuid("exercise_id"), toSource(it)) }
    }

    override suspend fun create(exerciseId: UUID, source: GasSource): GasSource = db.transaction {
        queryOne(
            "INSERT INTO gas_sources (exercise_id, definition) VALUES (?, ?) RETURNING *",
            exerciseId, json(source),
        ) { toSource(it) }!!
    }

    override suspend fun update(id: UUID, source: GasSource): GasSource? = db.transaction {
        queryOne(
            "UPDATE gas_sources SET definition = ?, updated_at = now() WHERE id = ? RETURNING *",
            json(source), id,
        ) { toSource(it) }
    }

    override suspend fun delete(id: UUID): Boolean = db.transaction {
        execute("DELETE FROM gas_sources WHERE id = ?", id) > 0
    }

    override suspend fun activate(id: UUID, atS: Double): GasSource? = db.transaction {
        queryOne("UPDATE gas_sources SET activated_at_s = ?, updated_at = now() WHERE id = ? RETURNING *", atS, id) { toSource(it) }
    }

    /** Stored without id and activation time, which have their own columns. */
    private fun json(source: GasSource) =
        JsonbParam(DbJson.encodeToString(GasSource.serializer(), source.copy(id = "", activatedAtS = null)))

    private fun toSource(rs: ResultSet): GasSource =
        DbJson.decodeFromString(GasSource.serializer(), rs.getString("definition"))
            .copy(id = rs.uuid("id").toString(), activatedAtS = rs.doubleOrNull("activated_at_s"))
}

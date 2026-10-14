package com.taksolutions.simsensor.server.profiles

import com.taksolutions.simsensor.profile.SensorProfile
import com.taksolutions.simsensor.server.api.ConflictException
import com.taksolutions.simsensor.server.db.Database
import com.taksolutions.simsensor.server.db.DbJson
import com.taksolutions.simsensor.server.db.JsonbParam
import com.taksolutions.simsensor.server.db.execute
import com.taksolutions.simsensor.server.db.instant
import com.taksolutions.simsensor.server.db.isUniqueViolation
import com.taksolutions.simsensor.server.db.query
import com.taksolutions.simsensor.server.db.queryOne
import com.taksolutions.simsensor.server.db.uuid
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/** A profile template as stored. */
data class StoredProfile(
    val id: UUID,
    val definition: SensorProfile,
    val version: Int,
    val builtin: Boolean,
    val updatedAt: Instant,
)

interface ProfileRepository {
    suspend fun list(): List<StoredProfile>
    suspend fun find(id: UUID): StoredProfile?
    suspend fun countBuiltin(): Long

    /** Throws [ConflictException] when the name is taken. */
    suspend fun create(definition: SensorProfile, builtin: Boolean = false): StoredProfile

    /**
     * Replaces the definition and raises its version. Exercises using the profile get their
     * version raised in the same transaction, because their effective profile changed.
     */
    suspend fun update(id: UUID, definition: SensorProfile): StoredProfile?

    /** Returns false when the profile doesn't exist. */
    suspend fun delete(id: UUID): Boolean
}

class JdbcProfileRepository(private val db: Database) : ProfileRepository {

    override suspend fun list(): List<StoredProfile> = db.transaction {
        query("SELECT * FROM sensor_profiles ORDER BY lower(name)", map = ::toProfile)
    }

    override suspend fun find(id: UUID): StoredProfile? = db.transaction {
        queryOne("SELECT * FROM sensor_profiles WHERE id = ?", id, map = ::toProfile)
    }

    override suspend fun countBuiltin(): Long = db.transaction {
        queryOne("SELECT count(*) FROM sensor_profiles WHERE builtin") { it.getLong(1) } ?: 0
    }

    override suspend fun create(definition: SensorProfile, builtin: Boolean): StoredProfile = uniqueName {
        db.transaction {
            queryOne(
                "INSERT INTO sensor_profiles (name, definition, builtin) VALUES (?, ?, ?) RETURNING *",
                definition.name.trim(), json(definition), builtin,
                map = ::toProfile,
            )!!
        }
    }

    override suspend fun update(id: UUID, definition: SensorProfile): StoredProfile? = uniqueName {
        db.transaction {
            val updated = queryOne(
                """
                UPDATE sensor_profiles
                   SET name = ?, definition = ?, version = version + 1, updated_at = now()
                 WHERE id = ?
                RETURNING *
                """.trimIndent(),
                definition.name.trim(), json(definition), id,
                map = ::toProfile,
            )
            if (updated != null) {
                execute("UPDATE exercises SET version = version + 1, updated_at = now() WHERE profile_id = ?", id)
            }
            updated
        }
    }

    override suspend fun delete(id: UUID): Boolean = db.transaction {
        execute("DELETE FROM sensor_profiles WHERE id = ?", id) > 0
    }

    private suspend fun <T> uniqueName(block: suspend () -> T): T =
        try {
            block()
        } catch (e: SQLException) {
            if (e.isUniqueViolation()) throw ConflictException("A profile with that name already exists") else throw e
        }

    private fun json(definition: SensorProfile) = JsonbParam(DbJson.encodeToString(SensorProfile.serializer(), definition))

    private fun toProfile(rs: ResultSet) = StoredProfile(
        id = rs.uuid("id"),
        definition = DbJson.decodeFromString(SensorProfile.serializer(), rs.getString("definition")),
        version = rs.getInt("version"),
        builtin = rs.getBoolean("builtin"),
        updatedAt = rs.instant("updated_at"),
    )
}

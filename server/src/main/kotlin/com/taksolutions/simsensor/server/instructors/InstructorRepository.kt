package com.taksolutions.simsensor.server.instructors

import com.taksolutions.simsensor.server.api.ConflictException
import com.taksolutions.simsensor.server.db.Database
import com.taksolutions.simsensor.server.db.execute
import com.taksolutions.simsensor.server.db.instant
import com.taksolutions.simsensor.server.db.isUniqueViolation
import com.taksolutions.simsensor.server.db.query
import com.taksolutions.simsensor.server.db.queryOne
import com.taksolutions.simsensor.server.db.uuid
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

interface InstructorRepository {
    suspend fun count(): Long
    suspend fun list(): List<Instructor>
    suspend fun findById(id: UUID): Instructor?
    suspend fun findByIds(ids: Collection<UUID>): List<Instructor>

    /** Instructor plus stored password hash, for login only. */
    suspend fun findCredentials(email: String): Pair<Instructor, String>?
    suspend fun passwordHash(id: UUID): String?

    /** Throws [ConflictException] when the email is taken. */
    suspend fun create(email: String, name: String, passwordHash: String, role: Role): Instructor
    suspend fun update(id: UUID, name: String?, role: Role?, disabled: Boolean?): Instructor?
    suspend fun setPasswordHash(id: UUID, passwordHash: String)
}

class JdbcInstructorRepository(private val db: Database) : InstructorRepository {

    override suspend fun count(): Long = db.transaction {
        queryOne("SELECT count(*) FROM instructors") { it.getLong(1) } ?: 0
    }

    override suspend fun list(): List<Instructor> = db.transaction {
        query("SELECT * FROM instructors ORDER BY lower(name)", map = { it.toInstructor() })
    }

    override suspend fun findById(id: UUID): Instructor? = db.transaction {
        queryOne("SELECT * FROM instructors WHERE id = ?", id, map = { it.toInstructor() })
    }

    override suspend fun findByIds(ids: Collection<UUID>): List<Instructor> = db.transaction {
        if (ids.isEmpty()) {
            emptyList()
        } else {
            query("SELECT * FROM instructors WHERE id = ANY(?)", createArrayOf("uuid", ids.toTypedArray()), map = { it.toInstructor() })
        }
    }

    override suspend fun findCredentials(email: String): Pair<Instructor, String>? = db.transaction {
        queryOne("SELECT * FROM instructors WHERE lower(email) = lower(?)", email.trim()) {
            it.toInstructor() to it.getString("password_hash")
        }
    }

    override suspend fun passwordHash(id: UUID): String? = db.transaction {
        queryOne("SELECT password_hash FROM instructors WHERE id = ?", id) { it.getString(1) }
    }

    override suspend fun create(email: String, name: String, passwordHash: String, role: Role): Instructor =
        try {
            db.transaction {
                queryOne(
                    "INSERT INTO instructors (email, name, password_hash, role) VALUES (?, ?, ?, ?) RETURNING *",
                    email.trim(), name.trim(), passwordHash, role.dbValue,
                    map = { it.toInstructor() },
                )!!
            }
        } catch (e: SQLException) {
            if (e.isUniqueViolation()) throw ConflictException("An instructor with that email already exists") else throw e
        }

    override suspend fun update(id: UUID, name: String?, role: Role?, disabled: Boolean?): Instructor? = db.transaction {
        queryOne(
            """
            UPDATE instructors
               SET name = COALESCE(?, name), role = COALESCE(?, role), disabled = COALESCE(?, disabled)
             WHERE id = ?
            RETURNING *
            """.trimIndent(),
            name?.trim(), role?.dbValue, disabled, id,
            map = { it.toInstructor() },
        )
    }

    override suspend fun setPasswordHash(id: UUID, passwordHash: String) {
        db.transaction { execute("UPDATE instructors SET password_hash = ? WHERE id = ?", passwordHash, id) }
    }

}

/** Maps an `instructors` row (also used by queries that join it). */
fun ResultSet.toInstructor() = Instructor(
    id = uuid("id"),
    email = getString("email"),
    name = getString("name"),
    role = Role.fromDb(getString("role")),
    disabled = getBoolean("disabled"),
    createdAt = instant("created_at"),
)

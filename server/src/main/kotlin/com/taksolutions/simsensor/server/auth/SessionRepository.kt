package com.taksolutions.simsensor.server.auth

import com.taksolutions.simsensor.server.db.Database
import com.taksolutions.simsensor.server.db.execute
import com.taksolutions.simsensor.server.db.queryOne
import com.taksolutions.simsensor.server.instructors.Instructor
import com.taksolutions.simsensor.server.instructors.toInstructor
import java.time.Instant
import java.util.UUID

/** Instructor login sessions, stored in the database so any server instance can check them. */
interface SessionRepository {
    suspend fun create(tokenHash: String, instructorId: UUID, expiresAt: Instant)

    /** The session's instructor, if the session exists, hasn't expired, and the account is enabled. */
    suspend fun findActive(tokenHash: String, now: Instant): Instructor?
    suspend fun delete(tokenHash: String)
    suspend fun deleteForInstructor(instructorId: UUID)
    suspend fun deleteExpired(now: Instant)
}

class JdbcSessionRepository(private val db: Database) : SessionRepository {

    override suspend fun create(tokenHash: String, instructorId: UUID, expiresAt: Instant) {
        db.transaction {
            execute(
                "INSERT INTO instructor_sessions (token_hash, instructor_id, expires_at) VALUES (?, ?, ?)",
                tokenHash, instructorId, expiresAt,
            )
        }
    }

    override suspend fun findActive(tokenHash: String, now: Instant): Instructor? = db.transaction {
        queryOne(
            """
            SELECT i.* FROM instructor_sessions s JOIN instructors i ON i.id = s.instructor_id
             WHERE s.token_hash = ? AND s.expires_at > ? AND NOT i.disabled
            """.trimIndent(),
            tokenHash, now,
        ) { it.toInstructor() }
    }

    override suspend fun delete(tokenHash: String) {
        db.transaction { execute("DELETE FROM instructor_sessions WHERE token_hash = ?", tokenHash) }
    }

    override suspend fun deleteForInstructor(instructorId: UUID) {
        db.transaction { execute("DELETE FROM instructor_sessions WHERE instructor_id = ?", instructorId) }
    }

    override suspend fun deleteExpired(now: Instant) {
        db.transaction { execute("DELETE FROM instructor_sessions WHERE expires_at <= ?", now) }
    }
}

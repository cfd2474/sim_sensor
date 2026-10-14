package com.taksolutions.simsensor.server.devices

import com.taksolutions.simsensor.server.db.Database
import com.taksolutions.simsensor.server.db.execute
import com.taksolutions.simsensor.server.db.instant
import com.taksolutions.simsensor.server.db.query
import com.taksolutions.simsensor.server.db.queryOne
import com.taksolutions.simsensor.server.db.uuid
import com.taksolutions.simsensor.server.db.uuidOrNull
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

enum class EnrollmentKind(val dbValue: String) {
    /** QR code: one device, short-lived. */
    SINGLE("single"),

    /** MDM managed configuration: many devices, long-lived. */
    FLEET("fleet"),
    ;

    companion object {
        fun fromDb(value: String) = entries.first { it.dbValue == value }
    }
}

/** An enrollment token as listed. The token itself is never stored. */
data class EnrollmentToken(
    val id: UUID,
    val kind: EnrollmentKind,
    val label: String?,
    val createdBy: UUID?,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val usedAt: Instant?,
    val revokedAt: Instant?,
)

interface EnrollmentRepository {
    suspend fun create(tokenHash: String, kind: EnrollmentKind, label: String?, createdBy: UUID, expiresAt: Instant?): EnrollmentToken
    suspend fun list(): List<EnrollmentToken>
    suspend fun find(id: UUID): EnrollmentToken?
    suspend fun revoke(id: UUID, now: Instant): Boolean

    /**
     * In one transaction: checks the enrollment token (exists, not revoked, not expired, a
     * single-use token not yet used), marks a single-use token used, and creates the device.
     * Returns null when the token is not valid.
     */
    suspend fun enroll(
        enrollmentTokenHash: String,
        deviceId: UUID,
        deviceTokenHash: String,
        name: String,
        model: String?,
        appVersion: String,
        now: Instant,
    ): Device?
}

class JdbcEnrollmentRepository(private val db: Database) : EnrollmentRepository {

    override suspend fun create(
        tokenHash: String,
        kind: EnrollmentKind,
        label: String?,
        createdBy: UUID,
        expiresAt: Instant?,
    ): EnrollmentToken = db.transaction {
        queryOne(
            "INSERT INTO enrollment_tokens (token_hash, kind, label, created_by, expires_at) VALUES (?, ?, ?, ?, ?) RETURNING *",
            tokenHash, kind.dbValue, label?.trim(), createdBy, expiresAt,
            map = ::toToken,
        )!!
    }

    override suspend fun list(): List<EnrollmentToken> = db.transaction {
        query("SELECT * FROM enrollment_tokens ORDER BY created_at DESC", map = ::toToken)
    }

    override suspend fun find(id: UUID): EnrollmentToken? = db.transaction {
        queryOne("SELECT * FROM enrollment_tokens WHERE id = ?", id, map = ::toToken)
    }

    override suspend fun revoke(id: UUID, now: Instant): Boolean = db.transaction {
        execute("UPDATE enrollment_tokens SET revoked_at = COALESCE(revoked_at, ?) WHERE id = ?", now, id) > 0
    }

    override suspend fun enroll(
        enrollmentTokenHash: String,
        deviceId: UUID,
        deviceTokenHash: String,
        name: String,
        model: String?,
        appVersion: String,
        now: Instant,
    ): Device? = db.transaction {
        // FOR UPDATE: two phones scanning the same QR code at once can't both use it.
        val token = queryOne(
            "SELECT * FROM enrollment_tokens WHERE token_hash = ? FOR UPDATE",
            enrollmentTokenHash,
            map = ::toToken,
        ) ?: return@transaction null
        val valid = token.revokedAt == null &&
            (token.expiresAt == null || token.expiresAt.isAfter(now)) &&
            (token.kind == EnrollmentKind.FLEET || token.usedAt == null)
        if (!valid) return@transaction null

        if (token.kind == EnrollmentKind.SINGLE) {
            execute("UPDATE enrollment_tokens SET used_at = ? WHERE id = ?", now, token.id)
        }
        queryOne(
            """
            INSERT INTO devices (id, name, token_hash, model, app_version, enrolled_at, last_seen_at)
            VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING *
            """.trimIndent(),
            deviceId, name, deviceTokenHash, model, appVersion, now, now,
            map = ::toDevice,
        )
    }

    private fun toToken(rs: ResultSet) = EnrollmentToken(
        id = rs.uuid("id"),
        kind = EnrollmentKind.fromDb(rs.getString("kind")),
        label = rs.getString("label"),
        createdBy = rs.uuidOrNull("created_by"),
        createdAt = rs.instant("created_at"),
        expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java)?.toInstant(),
        usedAt = rs.getObject("used_at", OffsetDateTime::class.java)?.toInstant(),
        revokedAt = rs.getObject("revoked_at", OffsetDateTime::class.java)?.toInstant(),
    )
}

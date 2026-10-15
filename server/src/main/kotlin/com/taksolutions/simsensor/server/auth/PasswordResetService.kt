package com.taksolutions.simsensor.server.auth

import com.taksolutions.simsensor.profile.ValidationError
import com.taksolutions.simsensor.server.api.ConflictException
import com.taksolutions.simsensor.server.api.NotFoundException
import com.taksolutions.simsensor.server.api.ValidationException
import com.taksolutions.simsensor.server.db.Database
import com.taksolutions.simsensor.server.db.execute
import com.taksolutions.simsensor.server.db.instant
import com.taksolutions.simsensor.server.db.queryOne
import com.taksolutions.simsensor.server.db.uuid
import com.taksolutions.simsensor.server.events.EventLog
import com.taksolutions.simsensor.server.events.EventTypes
import com.taksolutions.simsensor.server.instructors.Instructor
import com.taksolutions.simsensor.server.instructors.InstructorPrincipal
import com.taksolutions.simsensor.server.instructors.InstructorRepository
import com.taksolutions.simsensor.server.instructors.InstructorService
import com.taksolutions.simsensor.server.instructors.requireAdmin
import com.taksolutions.simsensor.server.mail.MailService
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class TokenPurpose(val dbValue: String) { RESET("reset"), INVITE("invite") }

/** A valid (unused, unexpired) token. */
data class PasswordToken(val id: UUID, val instructorId: UUID, val purpose: TokenPurpose, val expiresAt: Instant)

interface PasswordTokenRepository {
    /** Replaces the instructor's open tokens: only the newest link works. */
    suspend fun create(instructorId: UUID, tokenHash: String, purpose: TokenPurpose, expiresAt: Instant)
    suspend fun findValid(tokenHash: String, now: Instant): PasswordToken?
    suspend fun markUsed(id: UUID, now: Instant)
}

class JdbcPasswordTokenRepository(private val db: Database) : PasswordTokenRepository {
    override suspend fun create(instructorId: UUID, tokenHash: String, purpose: TokenPurpose, expiresAt: Instant) {
        db.transaction {
            execute("DELETE FROM password_tokens WHERE instructor_id = ? AND used_at IS NULL", instructorId)
            execute(
                "INSERT INTO password_tokens (instructor_id, token_hash, purpose, expires_at) VALUES (?, ?, ?, ?)",
                instructorId, tokenHash, purpose.dbValue, expiresAt,
            )
        }
    }

    override suspend fun findValid(tokenHash: String, now: Instant): PasswordToken? = db.transaction {
        queryOne(
            "SELECT * FROM password_tokens WHERE token_hash = ? AND used_at IS NULL AND expires_at > ?",
            tokenHash, now,
        ) { rs ->
            PasswordToken(
                rs.uuid("id"), rs.uuid("instructor_id"),
                TokenPurpose.entries.first { it.dbValue == rs.getString("purpose") }, rs.instant("expires_at"),
            )
        }
    }

    override suspend fun markUsed(id: UUID, now: Instant) {
        db.transaction { execute("UPDATE password_tokens SET used_at = ? WHERE id = ?", now, id) }
    }
}

/** What the reset page shows before the new password is entered. */
@Serializable
data class TokenInfo(val purpose: String, val name: String, val email: String)

/**
 * Password links by email: "Forgot password?", an admin's "email reset link", and the invite a
 * new instructor gets. Tokens are 256-bit random, stored hashed, single use, and short-lived.
 * "Forgot password" answers the same whether or not the address exists, so it can't be used to
 * find accounts, and is rate limited.
 */
class PasswordResetService(
    private val instructors: InstructorRepository,
    private val tokens: PasswordTokenRepository,
    private val sessions: SessionRepository,
    private val hasher: PasswordHasher,
    private val mail: MailService,
    private val eventLog: EventLog,
    private val clock: Clock = Clock.systemUTC(),
    private val limiter: LoginRateLimiter = InMemoryLoginRateLimiter(maxFailures = 5, window = Duration.ofMinutes(15)),
) {
    suspend fun emailAvailable(): Boolean = mail.isConfigured()

    /** Always succeeds from the caller's point of view. */
    suspend fun forgot(email: String, clientKey: String) {
        val key = "forgot:${email.trim().lowercase()}"
        if (limiter.isBlocked(key) || limiter.isBlocked("forgot-ip:$clientKey")) return
        limiter.recordFailure(key)
        limiter.recordFailure("forgot-ip:$clientKey")
        val instructor = instructors.findCredentials(email.trim())?.first ?: return
        if (instructor.disabled) return
        sendReset(instructor)
    }

    /** Admin: email an instructor a reset link. */
    suspend fun sendResetLink(actor: InstructorPrincipal, id: UUID) {
        requireAdmin(actor)
        if (!mail.isConfigured()) throw ConflictException("Set up email first (Settings → Email)")
        val instructor = instructors.findById(id) ?: throw NotFoundException("Instructor not found")
        if (instructor.disabled) throw ConflictException("The account is disabled")
        if (!sendReset(instructor)) throw ConflictException("The email could not be sent; check Settings → Email")
    }

    /** A new account: email a link to set the first password. */
    suspend fun invite(actor: InstructorPrincipal, instructor: Instructor): Boolean {
        val token = issue(instructor.id, TokenPurpose.INVITE, INVITE_VALID)
        val sent = mail.sendInvite(instructor.email, instructor.name, actor.name, token, INVITE_VALID.toHours())
        eventLog.record(EventTypes.ACCOUNT_INVITED, actorId = actor.id, data = mapOf("email" to instructor.email, "sent" to sent.toString()))
        return sent
    }

    suspend fun info(token: String): TokenInfo {
        val t = tokens.findValid(SecureTokens.sha256(token), clock.instant()) ?: throw NotFoundException("This link has expired or was already used")
        val instructor = instructors.findById(t.instructorId) ?: throw NotFoundException("This link has expired or was already used")
        return TokenInfo(t.purpose.dbValue, instructor.name, instructor.email)
    }

    /** Sets the new password, uses up the link and signs the account out everywhere. */
    suspend fun reset(token: String, newPassword: String) {
        if (newPassword.length < InstructorService.MIN_PASSWORD_LENGTH) {
            throw ValidationException(listOf(ValidationError(null, "password", "Password must be at least ${InstructorService.MIN_PASSWORD_LENGTH} characters")))
        }
        val now = clock.instant()
        val t = tokens.findValid(SecureTokens.sha256(token), now) ?: throw NotFoundException("This link has expired or was already used")
        val instructor = instructors.findById(t.instructorId)
        if (instructor == null || instructor.disabled) throw NotFoundException("This link has expired or was already used")
        instructors.setPasswordHash(instructor.id, hasher.hash(newPassword))
        tokens.markUsed(t.id, now)
        sessions.deleteForInstructor(instructor.id)
        eventLog.record(EventTypes.PASSWORD_RESET, actorId = instructor.id, data = mapOf("via" to t.purpose.dbValue))
    }

    private suspend fun sendReset(instructor: Instructor): Boolean {
        val token = issue(instructor.id, TokenPurpose.RESET, RESET_VALID)
        return mail.sendReset(instructor.email, instructor.name, token, RESET_VALID.toMinutes())
    }

    private suspend fun issue(instructorId: UUID, purpose: TokenPurpose, valid: Duration): String {
        val token = SecureTokens.generate()
        tokens.create(instructorId, SecureTokens.sha256(token), purpose, clock.instant().plus(valid))
        return token
    }

    companion object {
        val RESET_VALID: Duration = Duration.ofHours(1)
        val INVITE_VALID: Duration = Duration.ofHours(72)
    }
}

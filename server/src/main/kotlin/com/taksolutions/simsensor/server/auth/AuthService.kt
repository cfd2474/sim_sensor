package com.taksolutions.simsensor.server.auth

import com.taksolutions.simsensor.server.api.TooManyRequestsException
import com.taksolutions.simsensor.server.api.UnauthorizedException
import com.taksolutions.simsensor.server.instructors.InstructorPrincipal
import com.taksolutions.simsensor.server.instructors.InstructorRepository
import com.taksolutions.simsensor.server.instructors.toPrincipal
import java.time.Clock
import java.time.Duration

/** A successful login: the cookie token to hand back, and who logged in. */
data class LoginResult(val token: String, val instructor: InstructorPrincipal)

/** Instructor login, logout and session lookup. */
class AuthService(
    private val instructors: InstructorRepository,
    private val sessions: SessionRepository,
    private val hasher: PasswordHasher,
    private val rateLimiter: LoginRateLimiter,
    private val sessionTtl: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Hash compared against when the email is unknown, so timing doesn't reveal which emails exist. */
    private val dummyHash: String by lazy { hasher.hash("timing-equalizer-not-a-password") }

    /**
     * @param clientKey identifies the caller for rate limiting (client address).
     * @throws UnauthorizedException for any bad credential or disabled account, with one message.
     * @throws TooManyRequestsException after too many recent failures.
     */
    suspend fun login(email: String, password: String, clientKey: String): LoginResult {
        val limitKey = "$clientKey|${email.trim().lowercase()}"
        if (rateLimiter.isBlocked(limitKey)) {
            throw TooManyRequestsException("Too many failed logins. Try again in a few minutes.")
        }
        val credentials = instructors.findCredentials(email)
        val passwordOk = hasher.verify(password, credentials?.second ?: dummyHash)
        val instructor = credentials?.first
        if (instructor == null || !passwordOk || instructor.disabled) {
            rateLimiter.recordFailure(limitKey)
            throw UnauthorizedException("Wrong email or password")
        }
        rateLimiter.reset(limitKey)

        val now = clock.instant()
        sessions.deleteExpired(now)
        val token = SecureTokens.generate()
        sessions.create(SecureTokens.sha256(token), instructor.id, now.plus(sessionTtl))
        return LoginResult(token, instructor.toPrincipal())
    }

    suspend fun resolve(token: String): InstructorPrincipal? =
        sessions.findActive(SecureTokens.sha256(token), clock.instant())?.toPrincipal()

    suspend fun logout(token: String) {
        sessions.delete(SecureTokens.sha256(token))
    }
}

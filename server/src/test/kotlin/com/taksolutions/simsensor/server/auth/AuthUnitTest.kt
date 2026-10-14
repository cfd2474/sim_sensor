package com.taksolutions.simsensor.server.auth

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuthUnitTest {
    private val hasher = Argon2PasswordHasher(memoryKib = 64, iterations = 1)

    @Test
    fun hashVerifiesOnlyTheRightPassword() {
        val encoded = hasher.hash("correct horse battery")
        assertTrue(encoded.startsWith("\$argon2id\$v=19\$m=64,t=1,p=1\$"))
        assertTrue(hasher.verify("correct horse battery", encoded))
        assertFalse(hasher.verify("correct horse batterY", encoded))
    }

    @Test
    fun samePasswordGetsDifferentSalts() {
        assertNotEquals(hasher.hash("same password"), hasher.hash("same password"))
    }

    @Test
    fun hashesWithOtherParametersStillVerify() {
        val strong = Argon2PasswordHasher(memoryKib = 128, iterations = 2).hash("pw-with-other-params")
        assertTrue(hasher.verify("pw-with-other-params", strong))
    }

    @Test
    fun malformedHashesNeverVerify() {
        listOf("", "plain", "\$argon2i\$v=19\$m=64,t=1,p=1\$AAAA\$AAAA", "\$argon2id\$v=19\$m=x\$AAAA\$AAAA").forEach {
            assertFalse(hasher.verify("anything", it), it)
        }
    }

    @Test
    fun rateLimiterBlocksAfterMaxFailuresAndForgetsAfterTheWindow() {
        val clock = MutableClock(Instant.parse("2026-10-14T12:00:00Z"))
        val limiter = InMemoryLoginRateLimiter(maxFailures = 3, window = Duration.ofMinutes(15), clock = clock)
        repeat(3) { limiter.recordFailure("k") }
        assertTrue(limiter.isBlocked("k"))
        assertFalse(limiter.isBlocked("other"))
        clock.now = clock.now.plus(Duration.ofMinutes(16))
        assertFalse(limiter.isBlocked("k"))
    }

    @Test
    fun tokensAreUniqueAndHashedDeterministically() {
        val a = SecureTokens.generate()
        assertNotEquals(a, SecureTokens.generate())
        assertTrue(SecureTokens.sha256(a) == SecureTokens.sha256(a))
        assertTrue(SecureTokens.sha256(a).length == 64)
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }
}

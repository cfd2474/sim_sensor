package com.taksolutions.simsensor.server.auth

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Limits failed logins per key (client address + email). */
interface LoginRateLimiter {
    fun isBlocked(key: String): Boolean
    fun recordFailure(key: String)
    fun reset(key: String)
}

/**
 * Per-instance limiter: at most [maxFailures] failures per [window].
 * With several server instances each counts separately; move it to Redis when scaling out.
 */
class InMemoryLoginRateLimiter(
    private val maxFailures: Int = 5,
    private val window: Duration = Duration.ofMinutes(15),
    private val clock: Clock = Clock.systemUTC(),
) : LoginRateLimiter {

    private val failures = ConcurrentHashMap<String, ArrayDeque<Instant>>()

    override fun isBlocked(key: String): Boolean {
        val recent = failures[key] ?: return false
        synchronized(recent) {
            prune(recent)
            return recent.size >= maxFailures
        }
    }

    override fun recordFailure(key: String) {
        val recent = failures.computeIfAbsent(key) { ArrayDeque() }
        synchronized(recent) {
            prune(recent)
            recent.addLast(clock.instant())
        }
    }

    override fun reset(key: String) {
        failures.remove(key)
    }

    private fun prune(recent: ArrayDeque<Instant>) {
        val cutoff = clock.instant().minus(window)
        while (recent.isNotEmpty() && recent.first().isBefore(cutoff)) recent.removeFirst()
    }
}

package com.taksolutions.simsensor.connection

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BackoffTest {

    @Test
    fun delaysGrowThenStayCappedWithJitter() {
        val backoff = Backoff(baseMillis = 1_000, maxMillis = 30_000, random = Random(42))
        val delays = List(12) { backoff.next() }
        delays.forEach { assertTrue("$it out of range", it in 500..30_000) }
        // The upper bound doubles: after a few attempts some delays exceed the base.
        assertTrue(delays.drop(4).any { it > 4_000 })
    }

    @Test
    fun resetStartsOverFromTheBase() {
        val backoff = Backoff(baseMillis = 1_000, maxMillis = 30_000, random = Random(1))
        repeat(10) { backoff.next() }
        backoff.reset()
        assertTrue(backoff.next() <= 1_000)
    }

    @Test
    fun aFleetDoesNotReconnectInLockstep() {
        val first = List(50) { Backoff(random = Random(it)).apply { repeat(5) { next() } }.next() }
        assertTrue(first.toSet().size > 40)
    }
}

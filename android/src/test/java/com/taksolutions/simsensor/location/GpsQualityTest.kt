package com.taksolutions.simsensor.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpsQualityTest {
    private fun fix(lat: Double, accuracy: Float?, time: Long) = GeoFix(lat, -117.0, accuracy, null, null, time)

    @Test
    fun noFixMeansNoPosition() {
        val result = GpsQuality().update(null, 1_000)
        assertNull(result.position)
        assertEquals(GpsStatus.NONE, result.status)
    }

    @Test
    fun anInaccurateFirstFixIsNotUsed() {
        val result = GpsQuality(maxAccuracyM = 30f).update(fix(33.0, 100f, 1_000), 1_000)
        assertNull(result.position)
        assertEquals(GpsStatus.NONE, result.status)
    }

    @Test
    fun goodFixesAreUsed() {
        val gps = GpsQuality()
        val result = gps.update(fix(33.0, 5f, 1_000), 1_500)
        assertEquals(33.0, result.position!!.lat, 0.0)
        assertEquals(GpsStatus.GOOD, result.status)
    }

    @Test
    fun aPoorFixHoldsTheLastGoodPosition() {
        val gps = GpsQuality(maxAccuracyM = 30f)
        gps.update(fix(33.0, 5f, 1_000), 1_000)
        val result = gps.update(fix(33.5, 80f, 2_000), 2_000)
        assertEquals(33.0, result.position!!.lat, 0.0)
        assertEquals(GpsStatus.WEAK, result.status)
    }

    @Test
    fun noFixForAWhileIsStale() {
        val gps = GpsQuality(staleAfterMillis = 15_000)
        val first = fix(33.0, 5f, 1_000)
        gps.update(first, 1_000)
        val result = gps.update(first, 20_000)
        assertEquals(33.0, result.position!!.lat, 0.0)
        assertEquals(GpsStatus.STALE, result.status)
    }

    @Test
    fun recoveryAfterWeakGps() {
        val gps = GpsQuality()
        gps.update(fix(33.0, 5f, 1_000), 1_000)
        gps.update(fix(33.1, 90f, 2_000), 2_000)
        val result = gps.update(fix(33.2, 8f, 3_000), 3_000)
        assertEquals(33.2, result.position!!.lat, 0.0)
        assertEquals(GpsStatus.GOOD, result.status)
    }
}

package com.taksolutions.simsensor.ui

import com.taksolutions.simsensor.engine.AlarmLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Chunk 4.15: the header above the meter names every exercise state; over the limit reads OL. */
class ExerciseStateLookTest {
    @Test
    fun everyStateHasItsHeader() {
        assertEquals("EXERCISE NOT STARTED", exerciseStateLook("draft")!!.title)
        assertEquals("EXERCISE ACTIVE", exerciseStateLook("running")!!.title)
        assertEquals("EXERCISE PAUSED", exerciseStateLook("paused")!!.title)
        assertEquals("EXERCISE ENDED", exerciseStateLook("ended")!!.title)
        assertEquals("EXERCISE ENDED", exerciseStateLook("archived")!!.title)
        assertEquals(DetectorColors.ok, exerciseStateLook("running")!!.color)
        assertNull("a state from a newer server shows nothing", exerciseStateLook("rehearsal"))
        assertNull(exerciseStateLook(null))
    }

    @Test
    fun overTheLimitReadsOl() {
        assertEquals("OL", AlarmLevel.OVER_RANGE.label)
    }
}

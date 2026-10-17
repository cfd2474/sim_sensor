package com.taksolutions.simsensor.enrollment

import com.taksolutions.simsensor.config.ManagedSettings
import com.taksolutions.simsensor.settings.Enrollment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedEnrollmentPolicyTest {
    private val mdm = ManagedSettings(serverUrl = "https://sim.example.org", enrollmentToken = "fleet-1")
    private val enrolled = EnrollmentState.Enrolled(Enrollment("https://sim.example.org", "d1", "Phone", "t"))

    private fun should(state: EnrollmentState, settings: ManagedSettings = mdm, refused: String? = null, last: Long? = null, now: Long = 100_000) =
        ManagedEnrollmentPolicy.shouldEnroll(settings, state, refused, last, now)

    @Test
    fun enrollsWheneverNotEnrolledWithAToken() {
        assertTrue("after Remove enrollment or a console revoke", should(EnrollmentState.NotEnrolled))
        assertFalse("no MDM settings", should(EnrollmentState.NotEnrolled, ManagedSettings()))
        assertFalse(should(EnrollmentState.NotEnrolled, mdm.copy(enrollmentToken = null)))
        assertFalse(should(enrolled))
        assertFalse(should(EnrollmentState.InProgress))
    }

    @Test
    fun aRefusedTokenWaitsForANewOne() {
        val refused = EnrollmentState.Failed("not valid", tokenRefused = true)
        assertFalse("retired token: don't hammer the server", should(refused, refused = "fleet-1", last = 0))
        assertFalse(should(EnrollmentState.NotEnrolled, refused = "fleet-1"))
        assertTrue("the MDM pushed a new token", should(refused, mdm.copy(enrollmentToken = "fleet-2"), refused = "fleet-1", last = 99_000))
    }

    @Test
    fun otherFailuresRetryEvery30Seconds() {
        val offline = EnrollmentState.Failed("Can't reach the server")
        assertFalse(should(offline, last = 80_000, now = 100_000))
        assertTrue(should(offline, last = 70_000, now = 100_000))
        assertTrue(should(offline, last = null))
    }
}

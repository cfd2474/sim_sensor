package com.taksolutions.simsensor.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnrollmentLinkTest {

    @Test
    fun buildAndParseRoundTrip() {
        val link = EnrollmentLink.build("https://simsensor.tak-solutions.com", "abc_DEF-123")
        assertEquals("simsensor://enroll?server=https%3A%2F%2Fsimsensor.tak-solutions.com&token=abc_DEF-123", link)
        assertEquals(EnrollmentLink.Parsed("https://simsensor.tak-solutions.com", "abc_DEF-123"), EnrollmentLink.parse(link))
    }

    @Test
    fun nonAsciiSurvives() {
        val link = EnrollmentLink.build("https://example.org/ü", "t")
        assertEquals("https://example.org/ü", EnrollmentLink.parse(link)?.serverUrl)
    }

    @Test
    fun rejectsOtherOrIncompleteLinks() {
        assertNull(EnrollmentLink.parse("https://example.org"))
        assertNull(EnrollmentLink.parse("simsensor://enroll?server=x"))
        assertNull(EnrollmentLink.parse("simsensor://enroll?server=x&token=%G1"))
    }
}

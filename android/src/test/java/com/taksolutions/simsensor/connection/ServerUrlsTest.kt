package com.taksolutions.simsensor.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlsTest {
    @Test
    fun httpsBecomesWss() = assertEquals(
        "wss://simsensor.tak-solutions.com/ws/device",
        ServerUrls.deviceSocketUrl("https://simsensor.tak-solutions.com/"),
    )

    @Test
    fun httpBecomesWsAndKeepsPort() = assertEquals(
        "ws://10.0.2.2:8080/ws/device",
        ServerUrls.deviceSocketUrl("http://10.0.2.2:8080"),
    )

    @Test
    fun bareHostDefaultsToHttps() {
        assertEquals("wss://example.org/ws/device", ServerUrls.deviceSocketUrl("  example.org "))
        assertEquals("https://example.org/api/devices/enroll", ServerUrls.apiUrl("example.org", "/api/devices/enroll"))
    }

    @Test
    fun rejectsBlankAndOtherSchemes() {
        assertNull(ServerUrls.deviceSocketUrl(""))
        assertNull(ServerUrls.deviceSocketUrl("ftp://example.org"))
        assertNull(ServerUrls.apiUrl("ftp://example.org", "/x"))
    }
}

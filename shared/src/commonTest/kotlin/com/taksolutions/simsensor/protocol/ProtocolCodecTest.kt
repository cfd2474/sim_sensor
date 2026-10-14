package com.taksolutions.simsensor.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolCodecTest {

    @Test
    fun helloRoundTrips() {
        val hello = Hello(deviceId = "dev-1", appVersion = "0.1.0")
        assertEquals(hello, ProtocolCodec.decodeOrNull(ProtocolCodec.encode(hello)))
    }

    @Test
    fun encodedMessageCarriesTypeDiscriminator() {
        val text = ProtocolCodec.encode(Welcome(serverTimeMillis = 1, serverVersion = "0.1.0"))
        assertTrue(text.contains("\"type\":\"welcome\""), text)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val text = """{"type":"hello","appVersion":"9.9.9","protocolVersion":1,"futureField":true}"""
        assertEquals(Hello(appVersion = "9.9.9"), ProtocolCodec.decodeOrNull(text))
    }

    @Test
    fun garbageDecodesToNull() {
        assertNull(ProtocolCodec.decodeOrNull("not json"))
        assertNull(ProtocolCodec.decodeOrNull("""{"type":"no-such-message"}"""))
    }
}

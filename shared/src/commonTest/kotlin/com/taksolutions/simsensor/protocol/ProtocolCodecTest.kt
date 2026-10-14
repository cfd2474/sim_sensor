package com.taksolutions.simsensor.protocol

import com.taksolutions.simsensor.profile.DefaultProfiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolCodecTest {

    @Test
    fun helloRoundTrips() {
        val hello = Hello(appVersion = "0.1.0", model = "SM-G736U1")
        assertEquals(hello, ProtocolCodec.decodeOrNull(ProtocolCodec.encode(hello)))
    }

    @Test
    fun encodedMessageCarriesTypeDiscriminator() {
        val text = ProtocolCodec.encode(ErrorMessage("x", "y"))
        assertTrue(text.contains("\"type\":\"error\""), text)
    }

    @Test
    fun welcomeWithExerciseAndOverridesRoundTrips() {
        val welcome = Welcome(
            serverTimeMillis = 1,
            serverVersion = "0.3.0",
            deviceId = "d",
            deviceName = "Entry 1",
            exercise = ExerciseSnapshot("e", "Drill", 3, "running", DefaultProfiles.FIVE_GAS),
            overrides = OverridesUpdate(
                2,
                listOf(ReadingOverride("CO", OverrideMode.RAMP, value = 250.0, rampSeconds = 30.0, startedAtMillis = 5)),
            ),
        )
        assertEquals(welcome, ProtocolCodec.decodeOrNull(ProtocolCodec.encode(welcome)))
    }

    @Test
    fun telemetryBatchRoundTrips() {
        val telemetry = Telemetry(
            listOf(
                TelemetrySample(ts = 1, lat = 33.1, lon = -117.2, readings = mapOf("O2" to 20.9, "CO" to 0.0)),
                TelemetrySample(ts = 2, readings = mapOf("CO" to 40.0), alarms = listOf("CO:LOW")),
            ),
        )
        assertEquals(telemetry, ProtocolCodec.decodeOrNull(ProtocolCodec.encode(telemetry)))
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

    @Test
    fun consoleMessagesRoundTrip() {
        val watch = WatchExercise("e1")
        assertEquals(watch, ConsoleCodec.decodeClientOrNull(ConsoleCodec.encodeClient(watch)))
        assertEquals(UnwatchExercise, ConsoleCodec.decodeClientOrNull("""{"type":"unwatch"}"""))
        val status = DeviceStatus("d1", online = true)
        assertEquals(status, ConsoleCodec.decodeServerOrNull(ConsoleCodec.encode(status)))
    }
}

package com.taksolutions.simsensor.server.support

import com.taksolutions.simsensor.protocol.ConsoleCodec
import com.taksolutions.simsensor.protocol.ConsoleServerMessage
import com.taksolutions.simsensor.protocol.EnrollRequest
import com.taksolutions.simsensor.protocol.EnrollResponse
import com.taksolutions.simsensor.protocol.Hello
import com.taksolutions.simsensor.protocol.ProtocolCodec
import com.taksolutions.simsensor.protocol.ProtocolMessage
import com.taksolutions.simsensor.protocol.Welcome
import com.taksolutions.simsensor.server.api.AssignDeviceRequest
import com.taksolutions.simsensor.server.api.CreateEnrollmentTokenRequest
import com.taksolutions.simsensor.server.api.CreatedEnrollmentTokenDto
import com.taksolutions.simsensor.server.routes.DEVICE_SOCKET_PATH
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

/** A clock tests can move. */
class MutableClock(@Volatile var now: Instant = Instant.parse("2026-10-14T12:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
}

suspend fun HttpClient.createQrToken(ttlMinutes: Long? = null): CreatedEnrollmentTokenDto {
    val response = postJson("/api/enrollment-tokens", CreateEnrollmentTokenRequest(kind = "single", ttlMinutes = ttlMinutes))
    assertEquals(HttpStatusCode.Created, response.status)
    return response.body()
}

suspend fun ApplicationTestBuilder.enroll(token: String, name: String? = null): EnrollResponse {
    val response = browser().postJson("/api/devices/enroll", EnrollRequest(token, appVersion = "0.3.0", deviceName = name))
    assertEquals(HttpStatusCode.Created, response.status)
    return response.body()
}

/** Enrolls a new device with a fresh QR token from [instructor]. */
suspend fun ApplicationTestBuilder.enrollDevice(instructor: HttpClient, name: String): EnrollResponse =
    enroll(instructor.createQrToken().token, name)

suspend fun HttpClient.assign(deviceId: String, exerciseId: String?) {
    assertEquals(HttpStatusCode.OK, putJson("/api/devices/$deviceId/exercise", AssignDeviceRequest(exerciseId)).status)
}

/** Opens the device socket, says hello and hands the welcome to [block]. */
suspend fun ApplicationTestBuilder.asDevice(
    device: EnrollResponse,
    block: suspend DefaultClientWebSocketSession.(Welcome) -> Unit,
) {
    browser().webSocket(DEVICE_SOCKET_PATH, request = { bearerAuth(device.deviceToken) }) {
        send(Frame.Text(ProtocolCodec.encode(Hello(appVersion = "0.3.0", model = "Test"))))
        block(nextDevice<Welcome>())
    }
}

/** The next device message, which must be a [T]. */
suspend inline fun <reified T : ProtocolMessage> DefaultClientWebSocketSession.nextDevice(): T {
    val frame = withTimeout(5_000) { incoming.receive() }
    val message = ProtocolCodec.decodeOrNull((frame as Frame.Text).readText())
    return assertIs<T>(message)
}

/** The next console message, which must be a [T]. */
suspend inline fun <reified T : ConsoleServerMessage> DefaultClientWebSocketSession.nextConsole(): T {
    val frame = withTimeout(5_000) { incoming.receive() }
    val message = ConsoleCodec.decodeServerOrNull((frame as Frame.Text).readText())
    return assertIs<T>(message)
}

/** Skips console messages until a [T] arrives. */
suspend inline fun <reified T : ConsoleServerMessage> DefaultClientWebSocketSession.awaitConsole(): T =
    withTimeout(5_000) {
        var found: T? = null
        while (found == null) {
            found = ConsoleCodec.decodeServerOrNull((incoming.receive() as Frame.Text).readText()) as? T
        }
        found
    }

/** Asserts nothing arrives within [millis]. */
suspend fun DefaultClientWebSocketSession.expectSilence(millis: Long = 750) {
    val frame = withTimeoutOrNull(millis) { incoming.receive() }
    if (frame != null) fail("Expected no message, got ${(frame as? Frame.Text)?.readText() ?: frame}")
}

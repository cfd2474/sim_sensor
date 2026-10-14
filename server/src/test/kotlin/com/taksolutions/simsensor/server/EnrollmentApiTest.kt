package com.taksolutions.simsensor.server

import com.taksolutions.simsensor.protocol.EnrollRequest
import com.taksolutions.simsensor.protocol.EnrollmentLink
import com.taksolutions.simsensor.server.api.CreateEnrollmentTokenRequest
import com.taksolutions.simsensor.server.api.CreatedEnrollmentTokenDto
import com.taksolutions.simsensor.server.api.DeviceDto
import com.taksolutions.simsensor.server.api.EnrollmentTokenDto
import com.taksolutions.simsensor.server.routes.DEVICE_SOCKET_PATH
import com.taksolutions.simsensor.server.support.MutableClock
import com.taksolutions.simsensor.server.support.TEST_CONFIG
import com.taksolutions.simsensor.server.support.adminBrowser
import com.taksolutions.simsensor.server.support.asDevice
import com.taksolutions.simsensor.server.support.browser
import com.taksolutions.simsensor.server.support.createQrToken
import com.taksolutions.simsensor.server.support.enroll
import com.taksolutions.simsensor.server.support.instructorBrowser
import com.taksolutions.simsensor.server.support.postJson
import com.taksolutions.simsensor.server.support.withApp
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EnrollmentApiTest {

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.enrollStatus(token: String) =
        browser().postJson("/api/devices/enroll", EnrollRequest(token, appVersion = "0.3.0")).status

    @Test
    fun qrTokenEnrollsOneDeviceOnly() = withApp {
        val admin = adminBrowser()
        val created = admin.createQrToken()
        assertEquals(EnrollmentLink.Parsed(TEST_CONFIG.publicUrl, created.token), EnrollmentLink.parse(created.link))

        val device = enroll(created.token, name = "Entry 1")
        assertEquals("Entry 1", device.deviceName)
        assertEquals(HttpStatusCode.Unauthorized, enrollStatus(created.token))

        val listed = admin.get("/api/devices").body<List<DeviceDto>>()
        assertEquals(listOf("Entry 1"), listed.map { it.name })
        val token = admin.get("/api/enrollment-tokens").body<List<EnrollmentTokenDto>>().single()
        assertNotNull(token.usedAt)
    }

    @Test
    fun expiredAndRevokedTokensAreRejected() {
        val clock = MutableClock()
        withApp(clock = clock) {
            val admin = adminBrowser()
            val expiring = admin.createQrToken(ttlMinutes = 5)
            clock.now = clock.now.plus(Duration.ofMinutes(6))
            assertEquals(HttpStatusCode.Unauthorized, enrollStatus(expiring.token))

            val revoked = admin.createQrToken()
            assertEquals(HttpStatusCode.NoContent, admin.delete("/api/enrollment-tokens/${revoked.info.id}").status)
            assertEquals(HttpStatusCode.Unauthorized, enrollStatus(revoked.token))

            assertEquals(HttpStatusCode.Unauthorized, enrollStatus("made-up-token"))
        }
    }

    @Test
    fun fleetTokensAreAdminOnlyAndReusable() = withApp {
        val admin = adminBrowser()
        val (instructor, _) = instructorBrowser(admin, "a@example.org")
        val fleet = CreateEnrollmentTokenRequest(kind = "fleet", label = "MDM")
        assertEquals(HttpStatusCode.Forbidden, instructor.postJson("/api/enrollment-tokens", fleet).status)

        val created = admin.postJson("/api/enrollment-tokens", fleet).body<CreatedEnrollmentTokenDto>()
        val first = enroll(created.token, name = "Phone 1")
        val second = enroll(created.token)
        assertTrue(first.deviceId != second.deviceId)
        assertTrue(second.deviceName.startsWith("Device "), second.deviceName)
    }

    @Test
    fun instructorsOnlySeeTheirOwnTokens() = withApp {
        val admin = adminBrowser()
        val (instructor, _) = instructorBrowser(admin, "a@example.org")
        admin.createQrToken()
        val mine = instructor.createQrToken()
        assertEquals(listOf(mine.info.id), instructor.get("/api/enrollment-tokens").body<List<EnrollmentTokenDto>>().map { it.id })
        assertEquals(2, admin.get("/api/enrollment-tokens").body<List<EnrollmentTokenDto>>().size)
        val adminsToken = admin.get("/api/enrollment-tokens").body<List<EnrollmentTokenDto>>().first { it.id != mine.info.id }
        assertEquals(HttpStatusCode.NotFound, instructor.delete("/api/enrollment-tokens/${adminsToken.id}").status)
    }

    @Test
    fun deviceSocketNeedsAValidToken() = withApp {
        val result = runCatching {
            browser().webSocket(DEVICE_SOCKET_PATH, request = { bearerAuth("not-a-device-token") }) {}
        }
        assertTrue(result.isFailure, "Socket opened with a bad token")
    }

    @Test
    fun revokedDeviceIsDisconnectedAndLockedOut() = withApp {
        val admin = adminBrowser()
        val device = enroll(admin.createQrToken().token, name = "Lost phone")
        asDevice(device) {
            assertEquals(HttpStatusCode.OK, admin.post("/api/devices/${device.deviceId}/revoke").status)
            // The server closes the socket.
            val closed = runCatching { kotlinx.coroutines.withTimeout(5_000) { incoming.receive() } }
            assertTrue(closed.isFailure, "Socket stayed open after revocation")
        }
        val reconnect = runCatching {
            browser().webSocket(DEVICE_SOCKET_PATH, request = { bearerAuth(device.deviceToken) }) {}
        }
        assertTrue(reconnect.isFailure, "Revoked device could reconnect")
    }
}

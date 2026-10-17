package com.taksolutions.simsensor.server

import com.taksolutions.simsensor.server.api.MeResponse
import com.taksolutions.simsensor.server.mail.SmtpSecurity
import com.taksolutions.simsensor.server.mail.SmtpSettingsInput
import com.taksolutions.simsensor.server.support.RecordingMailSender
import com.taksolutions.simsensor.server.support.TEST_CONFIG
import com.taksolutions.simsensor.server.support.adminBrowser
import com.taksolutions.simsensor.server.support.instructorBrowser
import com.taksolutions.simsensor.server.support.putJson
import com.taksolutions.simsensor.server.support.withApp
import com.taksolutions.simsensor.server.system.CertificateProbe
import com.taksolutions.simsensor.server.system.CertificateStatus
import com.taksolutions.simsensor.server.system.Validity
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Chunk 4.8: the server watches its own HTTPS certificate. */
class CertificateWatchTest {
    /** A 90-day certificate with [days] left (null: the handshake fails). */
    private class Expiring(var days: Long?) : CertificateProbe {
        override suspend fun validity(host: String, port: Int): Validity {
            val notAfter = days?.let { Instant.now().plus(Duration.ofDays(it)).plusSeconds(60) } ?: throw javax.net.ssl.SSLHandshakeException("certificate expired")
            return Validity(notAfter.minus(Duration.ofDays(90)), notAfter)
        }
    }

    private val https = TEST_CONFIG.copy(publicUrl = "https://sim.example.org")

    @Test
    fun aHealthyCertificateNeedsNothing() {
        val probe = Expiring(60)
        withApp(config = https, certificateProbe = probe) {
            val admin = adminBrowser()
            val status = admin.get("/api/system/certificate").body<CertificateStatus>()
            assertEquals("sim.example.org", status.host)
            assertEquals(60L, status.daysLeft)
            assertNull(status.warning)
            assertNull(admin.get("/api/auth/me").body<MeResponse>().certificateWarning)
        }
    }

    @Test
    fun aFailingRenewalWarnsAdminsInTheConsoleAndByEmail() {
        val mail = RecordingMailSender()
        val probe = Expiring(9)
        withApp(config = https, certificateProbe = probe, mail = mail) { services ->
            val admin = adminBrowser()
            admin.putJson("/api/settings/smtp", SmtpSettingsInput(host = "smtp.example.org", port = 587, security = SmtpSecurity.STARTTLS,
                username = "u", password = "p", fromAddress = "sim@example.org", fromName = "Sim"))
            val status = admin.get("/api/system/certificate").body<CertificateStatus>()
            assertTrue(status.warning!!.contains("expires in 9 days"), status.warning)
            assertTrue(admin.get("/api/auth/me").body<MeResponse>().certificateWarning!!.contains("9 days"), "console banner")
            assertTrue(mail.sent.any { it.subject.contains("HTTPS certificate") }, "admins emailed")
            val before = mail.sent.size
            services.certificateService.checkNow()
            assertEquals(before, mail.sent.size, "at most one email a day")
            val (instructor, _) = instructorBrowser(admin, "i@example.org")
            assertEquals(HttpStatusCode.Forbidden, instructor.get("/api/system/certificate").status)
            assertNull(instructor.get("/api/auth/me").body<MeResponse>().certificateWarning, "admins only")

            probe.days = null // can't connect / expired
            assertTrue(services.certificateService.checkNow()!!.warning!!.contains("couldn't be checked"))
        }
    }

    @Test
    fun localInstallsHaveNothingToWatch() = withApp {
        assertEquals(HttpStatusCode.NoContent, adminBrowser().get("/api/system/certificate").status)
    }
}

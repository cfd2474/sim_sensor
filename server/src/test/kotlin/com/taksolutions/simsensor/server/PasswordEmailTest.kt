package com.taksolutions.simsensor.server

import com.taksolutions.simsensor.server.api.CreateInstructorRequest
import com.taksolutions.simsensor.server.api.ErrorResponse
import com.taksolutions.simsensor.server.mail.SmtpSecurity
import com.taksolutions.simsensor.server.mail.SmtpSettingsInput
import com.taksolutions.simsensor.server.auth.TokenInfo
import com.taksolutions.simsensor.server.mail.SmtpSettingsView
import com.taksolutions.simsensor.server.routes.AuthOptions
import com.taksolutions.simsensor.server.support.ADMIN_EMAIL
import com.taksolutions.simsensor.server.support.ADMIN_PASSWORD
import com.taksolutions.simsensor.server.support.MutableClock
import com.taksolutions.simsensor.server.support.RecordingMailSender
import com.taksolutions.simsensor.server.support.adminBrowser
import com.taksolutions.simsensor.server.support.browser
import com.taksolutions.simsensor.server.support.instructorBrowser
import com.taksolutions.simsensor.server.support.login
import com.taksolutions.simsensor.server.support.postJson
import com.taksolutions.simsensor.server.support.putJson
import com.taksolutions.simsensor.server.support.withApp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Chunk 4.1: SMTP settings, forgot password, invites. */
class PasswordEmailTest {
    private val brevo = SmtpSettingsInput(
        host = "smtp-relay.brevo.com", port = 587, security = SmtpSecurity.STARTTLS,
        username = "8a1b2c@smtp-brevo.com", password = "xsmtpsib-secret", fromAddress = "noreply@tak-solutions.com",
    )

    private suspend fun HttpClient.configureMail() =
        assertEquals(HttpStatusCode.OK, putJson("/api/settings/smtp", brevo).status)

    @Test
    fun smtpSettingsAreAdminOnlyAndThePasswordIsWriteOnly() = withApp {
        val admin = adminBrowser()
        assertFalse(admin.get("/api/settings/smtp").body<SmtpSettingsView>().configured)
        admin.configureMail()
        val raw = admin.get("/api/settings/smtp").bodyAsText()
        assertFalse(raw.contains("xsmtpsib-secret"), "the password never comes back")
        assertTrue(admin.get("/api/settings/smtp").body<SmtpSettingsView>().passwordSet)
        // Saving without a password keeps the stored one.
        admin.putJson("/api/settings/smtp", brevo.copy(password = null, fromName = "Training"))
        assertTrue(admin.get("/api/settings/smtp").body<SmtpSettingsView>().passwordSet)

        val (bob, _) = instructorBrowser(admin, "bob@example.org")
        assertEquals(HttpStatusCode.Forbidden, bob.get("/api/settings/smtp").status)
        assertEquals(HttpStatusCode.Forbidden, bob.putJson("/api/settings/smtp", brevo).status)
    }

    @Test
    fun testEmailReachesTheAdminOrExplainsTheFailure() {
        val mail = RecordingMailSender()
        withApp(mail = mail) {
            val admin = adminBrowser()
            assertEquals(HttpStatusCode.Conflict, admin.post("/api/settings/smtp/test").status, "not configured yet")
            admin.configureMail()
            assertEquals(HttpStatusCode.OK, admin.post("/api/settings/smtp/test").status)
            assertEquals(ADMIN_EMAIL, mail.sent.single().to)
            mail.failWith = "535 Authentication failed"
            val failed = admin.post("/api/settings/smtp/test")
            assertEquals(HttpStatusCode.Conflict, failed.status)
            assertTrue(failed.body<ErrorResponse>().message.contains("535 Authentication failed"))
        }
    }

    @Test
    fun forgotPasswordSendsASingleUseLink() {
        val mail = RecordingMailSender()
        withApp(mail = mail) {
            val admin = adminBrowser()
            admin.configureMail()
            val anonymous = browser()
            assertTrue(anonymous.get("/api/auth/options").body<AuthOptions>().forgotPassword)
            assertEquals(HttpStatusCode.Accepted, anonymous.postJson("/api/auth/forgot", mapOf("email" to ADMIN_EMAIL.uppercase())).status)
            val email = mail.sent.single()
            assertEquals("Reset your Sim Sensor password", email.subject)
            val token = mail.lastToken()
            assertEquals("reset", anonymous.get("/api/auth/reset?token=$token").body<TokenInfo>().purpose)

            assertEquals(HttpStatusCode.UnprocessableEntity, anonymous.postJson("/api/auth/reset", mapOf("token" to token, "password" to "short")).status)
            assertEquals(HttpStatusCode.NoContent, anonymous.postJson("/api/auth/reset", mapOf("token" to token, "password" to "new-pass-1")).status)
            assertEquals(HttpStatusCode.Unauthorized, admin.get("/api/auth/me").status, "old sessions end")
            assertEquals(HttpStatusCode.Unauthorized, browser().login(ADMIN_EMAIL, ADMIN_PASSWORD).status)
            assertEquals(HttpStatusCode.OK, browser().login(ADMIN_EMAIL, "new-pass-1").status)
            assertEquals(HttpStatusCode.NotFound, anonymous.postJson("/api/auth/reset", mapOf("token" to token, "password" to "again-pass-2")).status, "single use")
        }
    }

    @Test
    fun forgotPasswordRevealsNothing() {
        val mail = RecordingMailSender()
        withApp(mail = mail) {
            val anonymous = browser()
            assertFalse(anonymous.get("/api/auth/options").body<AuthOptions>().forgotPassword, "no email set up")
            assertEquals(HttpStatusCode.Accepted, anonymous.postJson("/api/auth/forgot", mapOf("email" to ADMIN_EMAIL)).status)
            adminBrowser().configureMail()
            assertEquals(HttpStatusCode.Accepted, anonymous.postJson("/api/auth/forgot", mapOf("email" to "nobody@example.org")).status)
            assertTrue(mail.sent.isEmpty(), "nothing sent for an unknown address, same answer")
        }
    }

    @Test
    fun resetLinksExpireAfterAnHour() {
        val mail = RecordingMailSender()
        val clock = MutableClock()
        withApp(clock = clock, mail = mail) {
            adminBrowser().configureMail()
            browser().postJson("/api/auth/forgot", mapOf("email" to ADMIN_EMAIL))
            val token = mail.lastToken()
            clock.now = clock.now.plus(Duration.ofMinutes(61))
            assertEquals(HttpStatusCode.NotFound, browser().postJson("/api/auth/reset", mapOf("token" to token, "password" to "new-pass-1")).status)
        }
    }

    @Test
    fun invitedInstructorsSetTheirOwnPassword() {
        val mail = RecordingMailSender()
        withApp(mail = mail) {
            val admin = adminBrowser()
            val invite = CreateInstructorRequest(email = "carol@example.org", name = "Carol", sendInvite = true)
            assertEquals(HttpStatusCode.Conflict, admin.postJson("/api/instructors", invite).status, "email not set up")
            assertEquals(
                HttpStatusCode.UnprocessableEntity,
                admin.postJson("/api/instructors", invite.copy(sendInvite = false)).status, "no password and no invite",
            )
            admin.configureMail()
            val created = admin.postJson("/api/instructors", invite)
            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals(true, created.body<com.taksolutions.simsensor.server.api.InstructorDto>().inviteSent)
            assertEquals("Your Sim Sensor instructor account", mail.sent.last().subject)
            assertTrue(mail.sent.last().text.contains("Administrator set up a Sim Sensor instructor account"))
            val token = mail.lastToken()
            assertEquals("invite", browser().get("/api/auth/reset?token=$token").body<TokenInfo>().purpose)
            assertEquals(HttpStatusCode.NoContent, browser().postJson("/api/auth/reset", mapOf("token" to token, "password" to "carol-pass")).status)
            assertEquals(HttpStatusCode.OK, browser().login("carol@example.org", "carol-pass").status)
        }
    }

    @Test
    fun adminCanEmailAResetLink() {
        val mail = RecordingMailSender()
        withApp(mail = mail) {
            val admin = adminBrowser()
            val (_, bob) = instructorBrowser(admin, "bob@example.org")
            assertEquals(HttpStatusCode.Conflict, admin.post("/api/instructors/${bob.id}/reset-email").status, "email not set up")
            admin.configureMail()
            assertEquals(HttpStatusCode.NoContent, admin.post("/api/instructors/${bob.id}/reset-email").status)
            assertEquals("bob@example.org", mail.sent.single().to)
        }
    }
}

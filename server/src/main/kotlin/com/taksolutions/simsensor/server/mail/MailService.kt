package com.taksolutions.simsensor.server.mail

import com.taksolutions.simsensor.profile.ValidationError
import com.taksolutions.simsensor.server.api.ConflictException
import com.taksolutions.simsensor.server.api.ValidationException
import com.taksolutions.simsensor.server.events.EventLog
import com.taksolutions.simsensor.server.events.EventTypes
import com.taksolutions.simsensor.server.instructors.InstructorPrincipal
import com.taksolutions.simsensor.server.instructors.requireAdmin
import com.taksolutions.simsensor.server.settings.SettingsRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/** The SMTP settings as the console sees them: the password is never sent back. */
@Serializable
data class SmtpSettingsView(
    val configured: Boolean,
    val host: String = "",
    val port: Int = 587,
    val security: SmtpSecurity = SmtpSecurity.STARTTLS,
    val username: String = "",
    val passwordSet: Boolean = false,
    val fromAddress: String = "",
    val fromName: String = "Sim Sensor",
)

/** What the admin saves; a blank [password] keeps the stored one. */
@Serializable
data class SmtpSettingsInput(
    val host: String,
    val port: Int,
    val security: SmtpSecurity = SmtpSecurity.STARTTLS,
    val username: String = "",
    val password: String? = null,
    val fromAddress: String,
    val fromName: String = "Sim Sensor",
)

/**
 * Email for the console: the admin's SMTP connection (any provider, e.g. Brevo or Resend) and
 * the messages the server sends. Links point at [publicUrl].
 */
class MailService(
    private val settings: SettingsRepository,
    private val sender: MailSender,
    private val publicUrl: String,
    private val eventLog: EventLog,
) {
    private val log = LoggerFactory.getLogger(MailService::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private suspend fun stored(): SmtpSettings? =
        settings.all()[KEY]?.let { runCatching { json.decodeFromJsonElement(SmtpSettings.serializer(), it) }.getOrNull() }

    suspend fun isConfigured(): Boolean = stored() != null

    suspend fun view(actor: InstructorPrincipal): SmtpSettingsView {
        requireAdmin(actor)
        val s = stored() ?: return SmtpSettingsView(configured = false)
        return SmtpSettingsView(true, s.host, s.port, s.security, s.username, s.password.isNotEmpty(), s.fromAddress, s.fromName)
    }

    suspend fun update(actor: InstructorPrincipal, input: SmtpSettingsInput): SmtpSettingsView {
        requireAdmin(actor)
        val errors = buildList {
            if (input.host.isBlank() || input.host.length > 253) add(ValidationError(null, "host", "Server name is required"))
            if (input.port !in 1..65535) add(ValidationError(null, "port", "Port must be 1–65535"))
            if (!EMAIL.matches(input.fromAddress.trim())) add(ValidationError(null, "fromAddress", "Sender must be an email address"))
            if (input.fromName.length > 80) add(ValidationError(null, "fromName", "Sender name must be at most 80 characters"))
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
        val keep = stored()?.password.orEmpty()
        val saved = SmtpSettings(
            host = input.host.trim(),
            port = input.port,
            security = input.security,
            username = input.username.trim(),
            password = input.password?.takeIf { it.isNotEmpty() } ?: keep,
            fromAddress = input.fromAddress.trim(),
            fromName = input.fromName.trim().ifEmpty { "Sim Sensor" },
        )
        settings.put(KEY, json.encodeToJsonElement(SmtpSettings.serializer(), saved), actor.id)
        eventLog.record(EventTypes.SETTINGS_CHANGED, actorId = actor.id, data = mapOf("smtp" to "${saved.host}:${saved.port}"))
        return view(actor)
    }

    /** Sends a test message to the admin; the provider's error is passed back if it fails. */
    suspend fun sendTest(actor: InstructorPrincipal): String {
        requireAdmin(actor)
        val s = stored() ?: throw ConflictException("Save the email settings first")
        try {
            sender.send(
                s,
                MailMessage(
                    to = actor.email,
                    subject = "Sim Sensor test email",
                    text = "This is a test from the Sim Sensor console at $publicUrl. Email is set up correctly.",
                ),
            )
        } catch (e: Exception) {
            throw ConflictException("The mail server refused: ${e.message ?: e::class.simpleName}")
        }
        return actor.email
    }

    /** "Forgot password" / admin reset link. Returns false when email isn't set up or sending failed. */
    suspend fun sendReset(to: String, name: String, token: String, validMinutes: Long): Boolean = sendQuietly(
        MailMessage(
            to = to,
            subject = "Reset your Sim Sensor password",
            text = """
                Hello $name,

                Someone asked to reset the password of your Sim Sensor account. To choose a new one, open:

                ${link(token)}

                The link works once and expires in $validMinutes minutes. If you didn't ask for this, ignore this email; your password stays the same.
            """.trimIndent(),
            html = page(
                "Hello ${escape(name)},",
                "Someone asked to reset the password of your Sim Sensor account.",
                "Choose a new password", link(token),
                "The link works once and expires in $validMinutes minutes. If you didn't ask for this, ignore this email; your password stays the same.",
            ),
        ),
    )

    /** A new account: link to set the first password. */
    suspend fun sendInvite(to: String, name: String, invitedBy: String, token: String, validHours: Long): Boolean = sendQuietly(
        MailMessage(
            to = to,
            subject = "Your Sim Sensor instructor account",
            text = """
                Hello $name,

                $invitedBy set up a Sim Sensor instructor account for you ($to). To choose your password and sign in, open:

                ${link(token)}

                The link works once and expires in $validHours hours. After that, use "Forgot password?" on the sign-in page.
            """.trimIndent(),
            html = page(
                "Hello ${escape(name)},",
                "${escape(invitedBy)} set up a Sim Sensor instructor account for you (${escape(to)}).",
                "Choose your password", link(token),
                "The link works once and expires in $validHours hours. After that, use \"Forgot password?\" on the sign-in page.",
            ),
        ),
    )

    private suspend fun sendQuietly(message: MailMessage): Boolean {
        val s = stored() ?: return false
        return try {
            sender.send(s, message)
            true
        } catch (e: Exception) {
            log.warn("Email to {} failed: {}", message.to, e.message)
            false
        }
    }

    private fun link(token: String) = "${publicUrl.trimEnd('/')}/reset?token=$token"

    private fun page(greeting: String, intro: String, button: String, url: String, footer: String) = """
        <div style="font-family:Segoe UI,Arial,sans-serif;max-width:560px;color:#1b1f24">
          <p>$greeting</p>
          <p>$intro</p>
          <p><a href="$url" style="display:inline-block;background:#0b4f9c;color:#fff;padding:10px 18px;border-radius:6px;text-decoration:none">$button</a></p>
          <p style="color:#5a6470;font-size:13px">$footer</p>
          <p style="color:#5a6470;font-size:12px">Or paste this link into your browser: $url</p>
        </div>
    """.trimIndent()

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        const val KEY = "smtp"
        val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}

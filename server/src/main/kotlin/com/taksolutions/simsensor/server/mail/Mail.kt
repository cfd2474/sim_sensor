package com.taksolutions.simsensor.server.mail

import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.Properties

/** How the connection to the SMTP server is secured. */
@Serializable
enum class SmtpSecurity {
    /** Plain connection upgraded with STARTTLS (ports 587, 2525). */
    STARTTLS,

    /** TLS from the first byte (port 465). */
    SSL,

    /** No encryption (local test servers only). */
    NONE,
}

/** The admin's SMTP connection (system setting `smtp`). */
@Serializable
data class SmtpSettings(
    val host: String,
    val port: Int,
    val security: SmtpSecurity = SmtpSecurity.STARTTLS,
    val username: String = "",
    val password: String = "",
    /** Sender address; must be verified with the provider (e.g. a domain set up in Brevo or Resend). */
    val fromAddress: String,
    val fromName: String = "Sim Sensor",
)

/** One email: plain text, with an optional HTML version. */
data class MailMessage(val to: String, val subject: String, val text: String, val html: String? = null)

/** Sends email. The SMTP implementation is used in production; tests use a recording fake. */
interface MailSender {
    suspend fun send(settings: SmtpSettings, message: MailMessage)
}

/** Jakarta Mail (Angus) over SMTP, with short timeouts so a bad setting fails fast. */
class SmtpMailSender : MailSender {
    override suspend fun send(settings: SmtpSettings, message: MailMessage) = withContext(Dispatchers.IO) {
        val props = Properties().apply {
            put("mail.smtp.host", settings.host)
            put("mail.smtp.port", settings.port.toString())
            put("mail.smtp.auth", (settings.username.isNotEmpty()).toString())
            put("mail.smtp.connectiontimeout", TIMEOUT_MS)
            put("mail.smtp.timeout", TIMEOUT_MS)
            put("mail.smtp.writetimeout", TIMEOUT_MS)
            when (settings.security) {
                SmtpSecurity.STARTTLS -> {
                    put("mail.smtp.starttls.enable", "true")
                    put("mail.smtp.starttls.required", "true")
                }
                SmtpSecurity.SSL -> put("mail.smtp.ssl.enable", "true")
                SmtpSecurity.NONE -> Unit
            }
            if (settings.security != SmtpSecurity.NONE) put("mail.smtp.ssl.checkserveridentity", "true")
        }
        val session = if (settings.username.isNotEmpty()) {
            Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication() = PasswordAuthentication(settings.username, settings.password)
            })
        } else {
            Session.getInstance(props)
        }
        val mime = MimeMessage(session).apply {
            setFrom(InternetAddress(settings.fromAddress, settings.fromName, "UTF-8"))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(message.to))
            setSubject(message.subject, "UTF-8")
            if (message.html == null) {
                setText(message.text, "UTF-8")
            } else {
                val alternative = MimeMultipart("alternative")
                alternative.addBodyPart(MimeBodyPart().apply { setText(message.text, "UTF-8") })
                alternative.addBodyPart(MimeBodyPart().apply { setContent(message.html, "text/html; charset=UTF-8") })
                setContent(alternative)
            }
        }
        Transport.send(mime)
    }

    private companion object {
        const val TIMEOUT_MS = "15000"
    }
}

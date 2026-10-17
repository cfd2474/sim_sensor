package com.taksolutions.simsensor.server.system

import com.taksolutions.simsensor.server.instructors.InstructorPrincipal
import com.taksolutions.simsensor.server.instructors.InstructorRepository
import com.taksolutions.simsensor.server.instructors.Role
import com.taksolutions.simsensor.server.instructors.requireAdmin
import com.taksolutions.simsensor.server.mail.MailService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.URI
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** When a certificate starts and stops being valid. */
data class Validity(val notBefore: Instant, val notAfter: Instant)

/** Reads the certificate a host serves on HTTPS. */
interface CertificateProbe {
    /** The certificate's validity; throws when the handshake fails (expired, untrusted, wrong name, unreachable). */
    suspend fun validity(host: String, port: Int = 443): Validity
}

/** The JDK's TLS client with the default trust store: a certificate browsers wouldn't trust fails here too. */
class TlsCertificateProbe : CertificateProbe {
    override suspend fun validity(host: String, port: Int): Validity = withContext(Dispatchers.IO) {
        val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
        socket.use {
            it.connect(InetSocketAddress(host, port), 10_000)
            it.soTimeout = 10_000
            it.sslParameters = it.sslParameters.apply {
                serverNames = listOf(SNIHostName(host))
                endpointIdentificationAlgorithm = "HTTPS" // the name must match, as in a browser
            }
            it.startHandshake()
            val cert = it.session.peerCertificates.first() as X509Certificate
            Validity(cert.notBefore.toInstant(), cert.notAfter.toInstant())
        }
    }
}

@Serializable
data class CertificateStatus(
    val host: String,
    /** When the certificate expires; null when it couldn't be read. */
    val notAfter: String? = null,
    val daysLeft: Long? = null,
    val checkedAt: String,
    /** Set when admins should act: renewal failing or the certificate unusable. */
    val warning: String? = null,
)

/**
 * Long-term certificate care (chunk 4.8). Caddy renews the Let's Encrypt certificate by itself
 * when a third of its lifetime is left (30 days of 90; Let's Encrypt is shortening lifetimes, so
 * this scales); Let's Encrypt no longer emails expiry reminders (ended 2025-06-04). So the server
 * checks its own certificate daily and, when less than a quarter of the lifetime is left (renewal
 * has been failing for a while) or the certificate can't be used, warns admins in the console
 * and by email (when SMTP is set up), at most once a day.
 */
class CertificateService(
    publicUrl: String,
    private val probe: CertificateProbe,
    private val mail: MailService,
    private val instructors: InstructorRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(CertificateService::class.java)

    /** The public HTTPS host to watch; null for plain-HTTP or local installs. */
    private val host: String? = runCatching { URI(publicUrl) }.getOrNull()
        ?.takeIf { it.scheme == "https" && it.host != null && it.host != "localhost" && !it.host.endsWith(".local") }
        ?.host

    private val lock = Mutex()
    private var last: CertificateStatus? = null
    private var lastEmailAt: Instant = Instant.EPOCH

    /** The latest check (admins); checks first if the last one is older than a day. */
    suspend fun status(actor: InstructorPrincipal): CertificateStatus? {
        requireAdmin(actor)
        return current()
    }

    /** For every admin page: the warning, if any (no check is triggered by this). */
    fun warning(): String? = last?.warning

    suspend fun current(): CertificateStatus? {
        val h = host ?: return null
        val known = last
        if (known != null && Duration.between(Instant.parse(known.checkedAt), clock.instant()) < Duration.ofDays(1)) return known
        return check(h)
    }

    /** Runs the daily check (scheduled from [com.taksolutions.simsensor.server.AppServices.start]). */
    suspend fun checkNow(): CertificateStatus? = host?.let { check(it) }

    private suspend fun check(h: String): CertificateStatus = lock.withLock {
        val now = clock.instant()
        val result = try {
            val (notBefore, notAfter) = probe.validity(h)
            val days = Duration.between(now, notAfter).toDays()
            val lifetime = Duration.between(notBefore, notAfter)
            CertificateStatus(
                host = h, notAfter = notAfter.toString(), daysLeft = days, checkedAt = now.toString(),
                warning = if (Duration.between(now, notAfter) < lifetime.dividedBy(WARN_FRACTION)) {
                    "The HTTPS certificate for $h expires in $days days and hasn't been renewed. Renewal normally happens " +
                        "${lifetime.toDays() / 3} days ahead, so it is failing. Check that ports 80 and 443 are open and the DNS name still points " +
                        "at this server (README: Certificates)."
                } else null,
            )
        } catch (e: Exception) {
            CertificateStatus(
                host = h, checkedAt = now.toString(),
                warning = "The HTTPS certificate for $h couldn't be checked: ${e.message ?: e.javaClass.simpleName}. " +
                    "Browsers and phones may refuse to connect (README: Certificates).",
            )
        }
        last = result
        if (result.warning != null) {
            log.warn("Certificate check: {}", result.warning)
            if (Duration.between(lastEmailAt, now) >= Duration.ofDays(1)) {
                lastEmailAt = now
                instructors.list().filter { it.role == Role.ADMIN && !it.disabled }.forEach { admin ->
                    mail.sendCertificateWarning(admin.email, admin.name, h, result.warning)
                }
            }
        }
        result
    }

    companion object {
        /** Warn when less than 1/[WARN_FRACTION] of the lifetime is left (renewal starts at 1/3). */
        const val WARN_FRACTION = 4L
    }
}

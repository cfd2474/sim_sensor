package com.taksolutions.simsensor.server.devices

import com.taksolutions.simsensor.profile.ValidationError
import com.taksolutions.simsensor.protocol.EnrollRequest
import com.taksolutions.simsensor.protocol.EnrollResponse
import com.taksolutions.simsensor.protocol.EnrollmentLink
import com.taksolutions.simsensor.server.api.NotFoundException
import com.taksolutions.simsensor.server.api.UnauthorizedException
import com.taksolutions.simsensor.server.api.ValidationException
import com.taksolutions.simsensor.server.auth.SecureTokens
import com.taksolutions.simsensor.server.events.EventLog
import com.taksolutions.simsensor.server.events.EventTypes
import com.taksolutions.simsensor.server.instructors.InstructorPrincipal
import com.taksolutions.simsensor.server.instructors.requireAdmin
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A freshly created enrollment token. [token] and [link] are shown once and never again. */
data class CreatedEnrollmentToken(val info: EnrollmentToken, val token: String, val link: String)

/**
 * Enrollment (docs/DESIGN.md §6.1, §11.2): QR codes (single use, short-lived, any instructor)
 * and MDM fleet tokens (many devices, admins only).
 */
class EnrollmentService(
    private val enrollment: EnrollmentRepository,
    private val eventLog: EventLog,
    private val publicUrl: String,
    private val clock: Clock,
) {
    suspend fun createToken(
        actor: InstructorPrincipal,
        kind: EnrollmentKind,
        label: String?,
        ttlMinutes: Long?,
    ): CreatedEnrollmentToken {
        if (kind == EnrollmentKind.FLEET) requireAdmin(actor)
        val ttl = when (kind) {
            EnrollmentKind.SINGLE -> Duration.ofMinutes(ttlMinutes ?: DEFAULT_QR_MINUTES)
            EnrollmentKind.FLEET -> ttlMinutes?.let(Duration::ofMinutes)
        }
        if (ttl != null && (ttl.isNegative || ttl.isZero || (kind == EnrollmentKind.SINGLE && ttl > MAX_QR_TTL))) {
            throw ValidationException(listOf(ValidationError(null, "ttlMinutes", "QR codes last 1 minute to 24 hours")))
        }
        val token = SecureTokens.generate()
        val info = enrollment.create(
            SecureTokens.sha256(token), kind, label, actor.id, ttl?.let { clock.instant().plus(it) },
        )
        return CreatedEnrollmentToken(info, token, EnrollmentLink.build(publicUrl, token))
    }

    /** Admins see every token; instructors see the ones they created. */
    suspend fun list(actor: InstructorPrincipal): List<EnrollmentToken> =
        enrollment.list().filter { actor.isAdmin || it.createdBy == actor.id }

    suspend fun revoke(actor: InstructorPrincipal, id: UUID) {
        val token = enrollment.find(id)
        if (token == null || !(actor.isAdmin || token.createdBy == actor.id)) throw NotFoundException("Token not found")
        enrollment.revoke(id, clock.instant())
    }

    /**
     * Retirement date of an MDM fleet token (operator 2026-10-16): from then on it enrolls no new
     * phones; phones it already enrolled keep working. Null clears it. Rotate by creating the new
     * token, pushing it through the MDM, and retiring the old one at a date after the push.
     */
    suspend fun setRetirement(actor: InstructorPrincipal, id: UUID, expiresAt: Instant?): EnrollmentToken {
        requireAdmin(actor)
        val token = enrollment.find(id) ?: throw NotFoundException("Token not found")
        val errors = buildList {
            if (token.kind != EnrollmentKind.FLEET) add(ValidationError(null, "expiresAt", "Only MDM fleet tokens can be given a retirement date"))
            if (token.revokedAt != null) add(ValidationError(null, "expiresAt", "This token is already retired"))
            if (expiresAt != null && !expiresAt.isAfter(clock.instant())) add(ValidationError(null, "expiresAt", "Choose a date in the future, or retire it now"))
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
        enrollment.setExpiry(id, expiresAt)
        return enrollment.find(id)!!
    }

    /** Called by a phone. Any problem with the token gets the same answer. */
    suspend fun enroll(request: EnrollRequest): EnrollResponse {
        val deviceId = UUID.randomUUID()
        val deviceToken = SecureTokens.generate()
        val name = request.deviceName?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NAME)
            ?: "Device ${deviceId.toString().take(4).uppercase()}"
        val device = enrollment.enroll(
            enrollmentTokenHash = SecureTokens.sha256(request.enrollmentToken.trim()),
            deviceId = deviceId,
            deviceTokenHash = SecureTokens.sha256(deviceToken),
            name = name,
            model = request.model?.take(MAX_NAME),
            appVersion = request.appVersion.take(MAX_NAME),
            now = clock.instant(),
        ) ?: throw UnauthorizedException("The enrollment code is not valid. Ask an instructor for a new one.")
        eventLog.record(EventTypes.DEVICE_ENROLLED, deviceId = device.id, data = mapOf("name" to device.name))
        return EnrollResponse(deviceId = device.id.toString(), deviceToken = deviceToken, deviceName = device.name)
    }

    private companion object {
        const val DEFAULT_QR_MINUTES = 15L
        val MAX_QR_TTL: Duration = Duration.ofHours(24)
        const val MAX_NAME = 64
    }
}

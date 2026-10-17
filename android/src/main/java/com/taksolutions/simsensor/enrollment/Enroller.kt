package com.taksolutions.simsensor.enrollment

import com.taksolutions.simsensor.connection.ServerUrls
import com.taksolutions.simsensor.protocol.EnrollRequest
import com.taksolutions.simsensor.protocol.EnrollResponse
import com.taksolutions.simsensor.protocol.EnrollmentLink
import com.taksolutions.simsensor.settings.Enrollment
import com.taksolutions.simsensor.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

sealed interface EnrollmentState {
    data object NotEnrolled : EnrollmentState
    data object InProgress : EnrollmentState
    /** [tokenRefused]: the server said the code is not valid (retired, revoked, expired). */
    data class Failed(val message: String, val tokenRefused: Boolean = false) : EnrollmentState
    data class Enrolled(val enrollment: Enrollment) : EnrollmentState
}

/** Calls `POST /api/devices/enroll`. */
class EnrollmentClient(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    /** @throws EnrollmentException with a message fit to show the user */
    suspend fun enroll(serverUrl: String, request: EnrollRequest): EnrollResponse = withContext(Dispatchers.IO) {
        val url = ServerUrls.apiUrl(serverUrl, "/api/devices/enroll")
            ?: throw EnrollmentException("The server address is not valid.")
        val body = json.encodeToString(EnrollRequest.serializer(), request).toRequestBody(JSON)
        val response = try {
            http.newCall(Request.Builder().url(url).post(body).build()).execute()
        } catch (e: IOException) {
            throw EnrollmentException("Can't reach the server. Check the network and try again.")
        }
        response.use {
            when (it.code) {
                201 -> json.decodeFromString(EnrollResponse.serializer(), it.body?.string().orEmpty())
                401 -> throw EnrollmentException("This enrollment code is not valid or has expired. Ask an instructor for a new one.", tokenRefused = true)
                else -> throw EnrollmentException("The server refused enrollment (HTTP ${it.code}).")
            }
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

class EnrollmentException(message: String, val tokenRefused: Boolean = false) : Exception(message)

/** Enrolls this phone from a QR code or from MDM settings and remembers the result. */
class Enroller(
    private val client: EnrollmentClient,
    private val settings: SettingsStore,
    private val appVersion: String,
    private val model: String,
) {
    private val _state = MutableStateFlow(settings.enrollment?.let(EnrollmentState::Enrolled) ?: EnrollmentState.NotEnrolled)
    val state: StateFlow<EnrollmentState> = _state.asStateFlow()

    /** From the text of a scanned QR code. */
    suspend fun enrollWithLink(link: String, deviceName: String? = null) {
        val parsed = EnrollmentLink.parse(link.trim())
        if (parsed == null) {
            _state.value = EnrollmentState.Failed("That QR code is not a Sim Sensor enrollment code.")
            return
        }
        enroll(parsed.serverUrl, parsed.token, deviceName)
    }

    suspend fun enroll(serverUrl: String, token: String, deviceName: String?) {
        if (_state.value is EnrollmentState.InProgress) return
        _state.value = EnrollmentState.InProgress
        _state.value = try {
            val response = client.enroll(serverUrl, EnrollRequest(token, appVersion, deviceName, model))
            val enrollment = Enrollment(serverUrl.trim().trimEnd('/'), response.deviceId, response.deviceName, response.deviceToken)
            settings.saveEnrollment(enrollment)
            EnrollmentState.Enrolled(enrollment)
        } catch (e: EnrollmentException) {
            EnrollmentState.Failed(e.message ?: "Enrollment failed.", e.tokenRefused)
        }
    }

    /** Forgets the enrollment (settings menu). The server still lists the device until revoked. */
    fun unenroll() {
        settings.clearEnrollment()
        _state.value = EnrollmentState.NotEnrolled
    }

    /** Called when the server refuses the device token (revoked). */
    fun onTokenRejected() = unenroll()
}

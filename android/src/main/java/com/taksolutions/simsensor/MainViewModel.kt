package com.taksolutions.simsensor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taksolutions.simsensor.config.ManagedSettings
import com.taksolutions.simsensor.enrollment.EnrollmentState
import com.taksolutions.simsensor.enrollment.ManagedEnrollmentPolicy
import com.taksolutions.simsensor.runtime.DetectorState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Screen-facing view of the app container. */
class MainViewModel(private val container: AppContainer) : ViewModel() {

    val enrollment: StateFlow<EnrollmentState> = container.enroller.state
    val detector: StateFlow<DetectorState> = container.runtime.state

    private val _managed = MutableStateFlow(container.managedConfig.read())
    val managed: StateFlow<ManagedSettings> = _managed

    /** The MDM token the server last refused, so it isn't retried until the MDM pushes another. */
    private var refusedToken: String? = null
    private var lastAttemptMillis: Long? = null
    private val enrolling = Mutex()

    init {
        viewModelScope.launch { container.managedConfig.changes().collect { _managed.value = it } }
        // Zero-touch MDM enrollment (chunk 4.2): whenever the phone isn't enrolled, including after
        // Remove enrollment or a console revoke, while the MDM's token is valid.
        viewModelScope.launch { combine(managed, enrollment) { _, _ -> }.collect { enrollFromMdm() } }
        // Re-read the managed configuration while not enrolled: a change the MDM made while the app
        // was closed sends no broadcast (platform reference §5). Also drives network retries.
        viewModelScope.launch {
            while (true) {
                delay(MDM_SCAN_MILLIS)
                if (enrollment.value !is EnrollmentState.Enrolled) {
                    _managed.value = container.managedConfig.read()
                    enrollFromMdm()
                }
            }
        }
    }

    private suspend fun enrollFromMdm() = enrolling.withLock {
        val settings = managed.value
        val now = System.currentTimeMillis()
        if (!ManagedEnrollmentPolicy.shouldEnroll(settings, enrollment.value, refusedToken, lastAttemptMillis, now)) return@withLock
        lastAttemptMillis = now
        container.enroller.enroll(settings.serverUrl!!, settings.enrollmentToken!!, settings.deviceName)
        val result = enrollment.value
        refusedToken = if (result is EnrollmentState.Failed && result.tokenRefused) settings.enrollmentToken else null
    }

    /** An enrollment link opened from outside the app, waiting for the user to confirm. */
    val pendingLink = MutableStateFlow<String?>(null)

    fun offerLink(link: String) {
        if (enrollment.value !is EnrollmentState.Enrolled) pendingLink.value = link
    }

    fun confirmPendingLink() {
        pendingLink.value?.let(::enrollWithLink)
        pendingLink.value = null
    }

    fun enrollWithLink(link: String) {
        viewModelScope.launch { container.enroller.enrollWithLink(link, managed.value.deviceName) }
    }

    /** Retry MDM enrollment after a failure (the button also re-reads the MDM settings). */
    fun retryManagedEnrollment() {
        _managed.value = container.managedConfig.read()
        refusedToken = null
        lastAttemptMillis = null
        viewModelScope.launch { enrollFromMdm() }
    }

    fun unenroll() {
        container.runtime.stop()
        container.enroller.unenroll()
    }

    /** A newer app on the server, or null (prompt on the meter and waiting screens). */
    val appUpdate: StateFlow<com.taksolutions.simsensor.update.AppUpdate?> = container.updates.update
    fun updateLater() = container.updates.later()

    fun acknowledge() = container.runtime.acknowledge()
    fun togglePeak() = container.runtime.togglePeak()
    fun dismissMessage() = container.runtime.dismissMessage()
    fun calibrate(beaconId: String, points: List<com.taksolutions.simsensor.source.CalibrationPoint>) =
        container.runtime.calibrate(beaconId, points)
    fun announceShutdown() = container.runtime.announceShutdown()
    fun setBeaconEdge(beaconId: String, edgeRssi: Double?) = container.runtime.setBeaconEdge(beaconId, edgeRssi)
    fun setCalibrating(on: Boolean) = container.runtime.setCalibrating(on)

    /** PIN needed to open settings, if the MDM set one. */
    fun settingsPin(): String? = managed.value.settingsPin

    private companion object {
        const val MDM_SCAN_MILLIS = 5_000L
    }
}

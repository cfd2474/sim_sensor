package com.taksolutions.simsensor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taksolutions.simsensor.config.ManagedSettings
import com.taksolutions.simsensor.enrollment.EnrollmentState
import com.taksolutions.simsensor.runtime.DetectorState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Screen-facing view of the app container. */
class MainViewModel(private val container: AppContainer) : ViewModel() {

    val enrollment: StateFlow<EnrollmentState> = container.enroller.state
    val detector: StateFlow<DetectorState> = container.runtime.state

    val managed: StateFlow<ManagedSettings> = container.managedConfig.changes()
        .stateIn(viewModelScope, SharingStarted.Eagerly, container.managedConfig.read())

    init {
        // Zero-touch MDM enrollment: as soon as the MDM has pushed a server and a token.
        viewModelScope.launch {
            managed.collect { settings ->
                val state = enrollment.value
                if (settings.canEnroll && (state is EnrollmentState.NotEnrolled)) {
                    container.enroller.enroll(settings.serverUrl!!, settings.enrollmentToken!!, settings.deviceName)
                }
            }
        }
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

    /** Retry MDM enrollment after a failure. */
    fun retryManagedEnrollment() {
        val settings = managed.value
        if (!settings.canEnroll) return
        viewModelScope.launch { container.enroller.enroll(settings.serverUrl!!, settings.enrollmentToken!!, settings.deviceName) }
    }

    fun unenroll() {
        container.runtime.stop()
        container.enroller.unenroll()
    }

    fun acknowledge() = container.runtime.acknowledge()
    fun togglePeak() = container.runtime.togglePeak()
    fun dismissMessage() = container.runtime.dismissMessage()

    /** PIN needed to open settings, if the MDM set one. */
    fun settingsPin(): String? = managed.value.settingsPin
}

package com.taksolutions.simsensor.enrollment

import com.taksolutions.simsensor.config.ManagedSettings

/**
 * When the app enrolls by itself from the MDM's managed configuration (chunk 4.2, operator
 * 2026-10-16): whenever the phone isn't enrolled, including after Remove enrollment or a console
 * revoke, as long as the MDM supplies a server and token. A token the server refused (retired or
 * revoked) isn't tried again until the MDM pushes a different one; other failures (no network)
 * are retried every [RETRY_MILLIS]. Pure, so it can be unit tested.
 */
object ManagedEnrollmentPolicy {
    const val RETRY_MILLIS = 30_000L

    fun shouldEnroll(
        settings: ManagedSettings,
        state: EnrollmentState,
        refusedToken: String?,
        lastAttemptMillis: Long?,
        nowMillis: Long,
    ): Boolean {
        if (!settings.canEnroll) return false
        if (settings.enrollmentToken == refusedToken) return false
        return when (state) {
            EnrollmentState.NotEnrolled -> true
            // Refused (with an older token, see above): the MDM's new token is tried at once.
            // Anything else, such as no network, waits for the retry interval.
            is EnrollmentState.Failed ->
                state.tokenRefused || lastAttemptMillis == null || nowMillis - lastAttemptMillis >= RETRY_MILLIS
            EnrollmentState.InProgress, is EnrollmentState.Enrolled -> false
        }
    }
}

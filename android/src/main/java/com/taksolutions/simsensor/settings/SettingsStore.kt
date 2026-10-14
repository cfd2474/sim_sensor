package com.taksolutions.simsensor.settings

import android.content.Context
import com.taksolutions.simsensor.security.TokenCipher

/** What the device knows about itself once enrolled. */
data class Enrollment(
    val serverUrl: String,
    val deviceId: String,
    val deviceName: String,
    val deviceToken: String,
)

/** Device-local settings. */
interface SettingsStore {
    /** Null until the device is enrolled. */
    val enrollment: Enrollment?

    fun saveEnrollment(enrollment: Enrollment)
    fun clearEnrollment()
}

class SharedPreferencesSettingsStore(context: Context, private val cipher: TokenCipher) : SettingsStore {
    private val prefs = context.getSharedPreferences("simsensor", Context.MODE_PRIVATE)

    override val enrollment: Enrollment?
        get() {
            val url = prefs.getString(KEY_SERVER_URL, null) ?: return null
            val id = prefs.getString(KEY_DEVICE_ID, null) ?: return null
            val name = prefs.getString(KEY_DEVICE_NAME, null) ?: return null
            val token = prefs.getString(KEY_DEVICE_TOKEN, null)?.let(cipher::decrypt) ?: return null
            return Enrollment(url, id, name, token)
        }

    override fun saveEnrollment(enrollment: Enrollment) {
        prefs.edit()
            .putString(KEY_SERVER_URL, enrollment.serverUrl)
            .putString(KEY_DEVICE_ID, enrollment.deviceId)
            .putString(KEY_DEVICE_NAME, enrollment.deviceName)
            .putString(KEY_DEVICE_TOKEN, cipher.encrypt(enrollment.deviceToken))
            .apply()
    }

    override fun clearEnrollment() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_DEVICE_TOKEN = "device_token_enc"
    }
}

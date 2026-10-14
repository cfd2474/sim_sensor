package com.taksolutions.simsensor.settings

import android.content.Context

/** Device-local settings. MDM managed configuration and QR enrollment write here in Phase 1. */
interface SettingsStore {
    var serverUrl: String
    val deviceToken: String?
}

class SharedPreferencesSettingsStore(context: Context) : SettingsStore {
    private val prefs = context.getSharedPreferences("simsensor", Context.MODE_PRIVATE)

    override var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, null) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trim()).apply()

    override val deviceToken: String?
        get() = prefs.getString(KEY_DEVICE_TOKEN, null)

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_DEVICE_TOKEN = "device_token"
        const val DEFAULT_SERVER_URL = "https://simsensor.tak-solutions.com"
    }
}

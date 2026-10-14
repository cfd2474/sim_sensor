package com.taksolutions.simsensor.config

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.RestrictionsManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Settings pushed by the MDM (docs/DESIGN.md §11.2). Blank values count as not set. */
data class ManagedSettings(
    val serverUrl: String? = null,
    val enrollmentToken: String? = null,
    val deviceName: String? = null,
    val settingsPin: String? = null,
) {
    val canEnroll: Boolean get() = serverUrl != null && enrollmentToken != null
}

/** Reads managed configuration and reports changes. */
class ManagedConfigSource(private val context: Context) {
    private val manager = context.getSystemService(RestrictionsManager::class.java)

    fun read(): ManagedSettings {
        val bundle = manager?.applicationRestrictions ?: return ManagedSettings()
        fun get(key: String) = bundle.getString(key)?.trim()?.takeIf { it.isNotEmpty() }
        return ManagedSettings(
            serverUrl = get("server_url"),
            enrollmentToken = get("enrollment_token"),
            deviceName = get("device_name"),
            settingsPin = get("settings_pin"),
        )
    }

    /**
     * Current settings, then again on every change. The change broadcast only reaches
     * receivers registered at runtime (platform reference §5).
     */
    fun changes(): Flow<ManagedSettings> = callbackFlow {
        trySend(read())
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(read())
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        awaitClose { context.unregisterReceiver(receiver) }
    }
}

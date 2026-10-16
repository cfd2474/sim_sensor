package com.taksolutions.simsensor.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.taksolutions.simsensor.SimSensorApp
import com.taksolutions.simsensor.notifications.Notifications
import com.taksolutions.simsensor.runtime.DetectorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the meter running when the app is in the background (docs/DESIGN.md §6.3).
 * Type `location`: started while the app is visible, it keeps location access in the
 * background without ACCESS_BACKGROUND_LOCATION (platform reference §1–2).
 */
class DetectorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observer: Job? = null

    private val container get() = (application as SimSensorApp).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Stop the updater first, or resetting the runtime would re-post the status notification
            // after the service is gone. Stopping the service removes its notification
            // (docs/ANDROID_PLATFORM_REFERENCE.md §1); the alarm notification is ours to cancel.
            observer?.cancel()
            observer = null
            container.runtime.stop()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java).cancel(Notifications.ALARM_ID)
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            Notifications.STATUS_ID,
            Notifications.status(this, container.runtime.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        container.startRuntime()
        if (observer == null) observer = scope.launch { observe() }
        // Not sticky: a background restart couldn't get location access anyway (platform reference §1).
        return START_NOT_STICKY
    }

    /** Whether any app screen is visible; the alarm notification is only needed when not. */
    private val appVisible = MutableStateFlow(true)

    private val visibilityObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            appVisible.value = true
        }

        override fun onStop(owner: LifecycleOwner) {
            appVisible.value = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(visibilityObserver)
    }

    private suspend fun observe() {
        val manager = getSystemService(NotificationManager::class.java)
        container.runtime.state
            .combine(appVisible) { state, visible -> Triple(StatusKey(state), visible, state) }
            .distinctUntilChanged { a, b -> a.first == b.first && a.second == b.second }
            .collect { (key, visible, state) ->
                manager.notify(Notifications.STATUS_ID, Notifications.status(this, state))
                updateAlarm(manager, key, visible, state)
            }
    }

    private fun updateAlarm(manager: NotificationManager, key: StatusKey, appVisible: Boolean, state: DetectorState) {
        if (key.alarmCodes.isNotEmpty() && !appVisible) {
            manager.notify(Notifications.ALARM_ID, Notifications.alarm(this, state))
        } else {
            manager.cancel(Notifications.ALARM_ID)
        }
    }

    /** The parts of the state that change what the notifications show. */
    private data class StatusKey(val exercise: String?, val link: String, val alarmCodes: List<String>) {
        constructor(state: DetectorState) : this(
            state.exercise?.name,
            state.link::class.simpleName.orEmpty(),
            state.output.alarmCodes,
        )
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(visibilityObserver)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "com.taksolutions.simsensor.STOP"

        /** Must be called while the app is visible (background starts are blocked on Android 12+). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DetectorService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DetectorService::class.java).setAction(ACTION_STOP))
        }
    }
}

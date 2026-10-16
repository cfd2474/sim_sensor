package com.taksolutions.simsensor

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import com.taksolutions.simsensor.alarm.ToneAlarmAnnunciator
import com.taksolutions.simsensor.config.ManagedConfigSource
import com.taksolutions.simsensor.connection.ServerLink
import com.taksolutions.simsensor.enrollment.Enroller
import com.taksolutions.simsensor.enrollment.EnrollmentClient
import com.taksolutions.simsensor.enrollment.EnrollmentState
import com.taksolutions.simsensor.location.LocationSource
import com.taksolutions.simsensor.runtime.DetectorRuntime
import com.taksolutions.simsensor.security.KeystoreTokenCipher
import com.taksolutions.simsensor.settings.SharedPreferencesSettingsStore
import com.taksolutions.simsensor.telemetry.TelemetryQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Builds the app's long-lived objects once (manual dependency injection). */
@OptIn(ExperimentalCoroutinesApi::class)
class AppContainer(context: Context) {
    private val app = context.applicationContext

    /** Single-threaded: the runtime's state is only touched from here. */
    val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val appVersion = BuildConfig.VERSION_NAME
    private val model = Build.MODEL ?: "unknown"

    val settings = SharedPreferencesSettingsStore(app, KeystoreTokenCipher())
    val managedConfig = ManagedConfigSource(app)
    val enroller = Enroller(EnrollmentClient(http), settings, appVersion, model)
    val location = LocationSource(app)

    val runtime = DetectorRuntime(
        link = ServerLink(http, appVersion, model),
        location = location,
        beaconScanner = com.taksolutions.simsensor.beacons.BeaconScanner(app),
        queue = TelemetryQueue(app),
        annunciator = ToneAlarmAnnunciator(app, runtimeScope),
        battery = {
            app.getSystemService(BatteryManager::class.java)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 }
                ?.let { it / 100f }
        },
        onRejected = { enroller.onTokenRejected() },
    )

    /** Starts the meter if enrolled. Safe to call repeatedly. */
    fun startRuntime() {
        val enrolled = enroller.state.value as? EnrollmentState.Enrolled ?: return
        runtime.start(runtimeScope, enrolled.enrollment)
    }
}

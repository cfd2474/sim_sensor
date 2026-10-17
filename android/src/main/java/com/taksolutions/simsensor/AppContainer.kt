package com.taksolutions.simsensor

import android.annotation.SuppressLint
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

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
    /**
     * ANDROID_ID: one per phone for our signing key, kept across reinstalls (platform reference
     * §5b). Lets the server keep one device entry per phone.
     */
    @SuppressLint("HardwareIds") // the per-app ANDROID_ID, the identifier Android intends for this
    private val hardwareId: String? =
        Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)?.takeIf { it.isNotBlank() }

    val enroller = Enroller(EnrollmentClient(http), settings, appVersion, model, hardwareId)
    val location = LocationSource(app)

    val runtime = DetectorRuntime(
        link = ServerLink(http, appVersion, model, hardwareId),
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

    /** Compares this app with the one the server offers (operator, 2026-10-17). */
    val updates = com.taksolutions.simsensor.update.AppUpdateChecker(
        source = com.taksolutions.simsensor.update.HttpOfferedAppSource(http),
        decide = { offered ->
            com.taksolutions.simsensor.update.AppVersionCheck.update(
                installedVersionCode = BuildConfig.VERSION_CODE,
                installedVersion = BuildConfig.VERSION_NAME,
                offered = offered,
                source = com.taksolutions.simsensor.update.InstallSource.of(installerPackage()),
                kioskMode = inKioskMode(),
                packageName = app.packageName,
            )
        },
    )

    init {
        runtimeScope.launch {
            enroller.state.collect { if (it is EnrollmentState.Enrolled) runtime.follow(it.enrollment) }
        }
        updates.start(
            runtimeScope,
            serverUrl = { (enroller.state.value as? EnrollmentState.Enrolled)?.enrollment?.serverUrl },
            link = runtime.state.map { it.link }.distinctUntilChanged(),
        )
    }

    /** Who installed this copy (platform reference §7): InstallSourceInfo from API 30. */
    private fun installerPackage(): String? = runCatching {
        val pm = app.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(app.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(app.packageName)
        }
    }.getOrNull()

    /** Kiosk (lock task) mode: other apps can't be opened (platform reference §7). */
    private fun inKioskMode(): Boolean =
        app.getSystemService(android.app.ActivityManager::class.java)?.lockTaskModeState == android.app.ActivityManager.LOCK_TASK_MODE_LOCKED

    /** Starts the meter if enrolled. Safe to call repeatedly. */
    fun startRuntime() {
        val enrolled = enroller.state.value as? EnrollmentState.Enrolled ?: return
        runtime.start(runtimeScope, enrolled.enrollment)
    }
}

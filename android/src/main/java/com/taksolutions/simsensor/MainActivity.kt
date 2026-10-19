package com.taksolutions.simsensor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.taksolutions.simsensor.enrollment.EnrollmentState
import com.taksolutions.simsensor.service.DetectorService
import com.taksolutions.simsensor.ui.CalibrationScreen
import com.taksolutions.simsensor.ui.DetectorScreen
import com.taksolutions.simsensor.ui.EnrollScreen
import com.taksolutions.simsensor.ui.PermissionScreen
import com.taksolutions.simsensor.ui.SettingsScreen
import com.taksolutions.simsensor.ui.SimSensorTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Operator requirement: the display never times out while the app is in the foreground.
        // The flag belongs to this window, so it stops applying when the app is in the background.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val container = (application as SimSensorApp).container
        val factory = viewModelFactory { initializer { MainViewModel(container) } }

        setContent {
            val vm: MainViewModel = viewModel(factory = factory)
            LaunchedEffect(Unit) { offerLinkFrom(intent, vm) }
            SimSensorTheme { App(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    private fun offerLinkFrom(intent: Intent?, vm: MainViewModel) {
        if (intent?.action == Intent.ACTION_VIEW) intent.dataString?.let(vm::offerLink)
    }

    @Composable
    private fun App(vm: MainViewModel) {
        val enrollment by vm.enrollment.collectAsStateWithLifecycle()
        val detector by vm.detector.collectAsStateWithLifecycle()
        val appUpdate by vm.appUpdate.collectAsStateWithLifecycle()
        val managed by vm.managed.collectAsStateWithLifecycle()
        val pendingLink by vm.pendingLink.collectAsStateWithLifecycle()
        var showSettings by rememberSaveable { mutableStateOf(false) }
        var calibrating by rememberSaveable { mutableStateOf<String?>(null) }
        var locationGranted by remember { mutableStateOf(hasLocation()) }

        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            locationGranted = hasLocation()
        }
        // An MDM (ATLAS) grants permissions by policy a few seconds after installing, possibly
        // while this screen is open, so re-check on resume and while waiting (platform reference).
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { locationGranted = hasLocation() }
        LaunchedEffect(locationGranted) {
            while (!locationGranted) {
                delay(2_000)
                locationGranted = hasLocation()
            }
        }

        when (val state = enrollment) {
            is EnrollmentState.Enrolled -> {
                LaunchedEffect(locationGranted) {
                    // Start (or keep) the meter while the app is on screen: a location service
                    // can't be started from the background.
                    if (locationGranted) DetectorService.start(this@MainActivity)
                }
                when {
                    !locationGranted -> PermissionScreen(onRequest = { permissions.launch(requiredPermissions()) })
                    showSettings && calibrating != null -> {
                        val beacon = detector.exercise?.beacons?.firstOrNull { it.id == calibrating }
                        if (beacon == null) {
                            LaunchedEffect(Unit) { calibrating = null } // beacon left the exercise
                        } else {
                            // Paused only while the screen is really on show: leaving with Home
                            // (app in the background) resumes readings and alarms; coming back
                            // pauses them again.
                            LifecycleStartEffect(Unit) {
                                vm.setCalibrating(true)
                                onStopOrDispose { vm.setCalibrating(false) }
                            }
                            CalibrationScreen(
                                beacon = beacon,
                                detector = detector,
                                onSave = { points -> vm.calibrate(beacon.id, points) },
                                onSaveEdge = { edge -> vm.setBeaconEdge(beacon.id, edge) },
                                onBack = { calibrating = null },
                            )
                        }
                    }
                    showSettings -> SettingsScreen(
                        enrollment = state.enrollment,
                        detector = detector,
                        appVersion = BuildConfig.VERSION_NAME,
                        onUnenroll = {
                            DetectorService.stop(this@MainActivity)
                            vm.unenroll()
                            showSettings = false
                        },
                        onCalibrate = { calibrating = it },
                        onShutdown = { shutDown(vm) },
                        onBack = { showSettings = false },
                    )
                    else -> DetectorScreen(
                        state = detector,
                        settingsPin = vm.settingsPin(),
                        onAcknowledge = vm::acknowledge,
                        onTogglePeak = vm::togglePeak,
                        onDismissMessage = vm::dismissMessage,
                        onOpenSettings = { showSettings = true },
                        appUpdate = appUpdate,
                        onUpdate = ::openUpdate,
                        onUpdateLater = vm::updateLater,
                        onShutdown = { shutDown(vm) },
                    )
                }
            }
            else -> {
                LaunchedEffect(Unit) { DetectorService.stop(this@MainActivity) }
                EnrollScreen(
                    state = state,
                    managed = managed,
                    pendingLink = pendingLink,
                    onConfirmLink = vm::confirmPendingLink,
                    onRejectLink = { vm.pendingLink.value = null },
                    onScanned = vm::enrollWithLink,
                    onRetryManaged = vm::retryManagedEnrollment,
                )
            }
        }
    }

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_SCAN)
    }.toTypedArray()

    /** Stops the meter and closes the app (Settings → Shut down, or Back on the main screen). */
    private fun shutDown(vm: MainViewModel) {
        vm.announceShutdown()
        DetectorService.stop(this)
        finishAndRemoveTask()
    }

    /**
     * Opens where the update comes from (platform reference §7): Play's listing in the Play Store
     * app for a Play copy, the server's download link in the browser otherwise.
     */
    private fun openUpdate(action: com.taksolutions.simsensor.update.UpdateAction) {
        val intent = when (action) {
            is com.taksolutions.simsensor.update.UpdateAction.OpenPlay ->
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(action.listingUrl))
                    .setPackage(com.taksolutions.simsensor.update.InstallSource.PLAY_PACKAGE)
            is com.taksolutions.simsensor.update.UpdateAction.OpenDownload ->
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(action.url))
            com.taksolutions.simsensor.update.UpdateAction.AskAdministrator -> return
        }
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(this, "No app on this phone can open the update link.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

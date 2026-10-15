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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.taksolutions.simsensor.enrollment.EnrollmentState
import com.taksolutions.simsensor.service.DetectorService
import com.taksolutions.simsensor.ui.DetectorScreen
import com.taksolutions.simsensor.ui.EnrollScreen
import com.taksolutions.simsensor.ui.PermissionScreen
import com.taksolutions.simsensor.ui.SettingsScreen
import com.taksolutions.simsensor.ui.SimSensorTheme

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
        val managed by vm.managed.collectAsStateWithLifecycle()
        val pendingLink by vm.pendingLink.collectAsStateWithLifecycle()
        var showSettings by rememberSaveable { mutableStateOf(false) }
        var locationGranted by remember { mutableStateOf(hasLocation()) }

        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            locationGranted = hasLocation()
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
                    showSettings -> SettingsScreen(
                        enrollment = state.enrollment,
                        detector = detector,
                        appVersion = BuildConfig.VERSION_NAME,
                        onUnenroll = {
                            DetectorService.stop(this@MainActivity)
                            vm.unenroll()
                            showSettings = false
                        },
                        onBack = { showSettings = false },
                    )
                    else -> DetectorScreen(
                        state = detector,
                        settingsPin = vm.settingsPin(),
                        onAcknowledge = vm::acknowledge,
                        onTogglePeak = vm::togglePeak,
                        onDismissMessage = vm::dismissMessage,
                        onOpenSettings = { showSettings = true },
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
    }.toTypedArray()
}

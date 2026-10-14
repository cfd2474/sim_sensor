package com.taksolutions.simsensor.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.taksolutions.simsensor.config.ManagedSettings
import com.taksolutions.simsensor.enrollment.EnrollmentState
import com.taksolutions.simsensor.protocol.EnrollmentLink

/** Shown until the phone is enrolled: MDM status, or scan an instructor's QR code. */
@Composable
fun EnrollScreen(
    state: EnrollmentState,
    managed: ManagedSettings,
    pendingLink: String?,
    onConfirmLink: () -> Unit,
    onRejectLink: () -> Unit,
    onScanned: (String) -> Unit,
    onRetryManaged: () -> Unit,
) {
    val context = LocalContext.current
    var scanning by rememberSaveable { mutableStateOf(false) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scanning = granted
    }

    if (scanning) {
        QrScanner(
            onResult = { text ->
                scanning = false
                onScanned(text)
            },
            onCancel = { scanning = false },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DetectorColors.background)
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SimulatorBanner()
        Text("Set up this phone", color = DetectorColors.reading, fontSize = 24.sp, fontWeight = FontWeight.Bold)

        when (state) {
            EnrollmentState.InProgress -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is EnrollmentState.Failed -> Text(state.message, color = DetectorColors.bad, fontSize = 16.sp)
            else -> Unit
        }

        pendingLink?.let { link ->
            val server = EnrollmentLink.parse(link)?.serverUrl
            if (server == null) {
                Text("That link is not a Sim Sensor enrollment code.", color = DetectorColors.bad, fontSize = 16.sp)
                OutlinedButton(onClick = onRejectLink) { Text("Dismiss") }
            } else {
                Text("Enroll this phone with $server?", color = DetectorColors.reading, fontSize = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onConfirmLink) { Text("Enroll") }
                    OutlinedButton(onClick = onRejectLink) { Text("Cancel") }
                }
            }
        }

        if (managed.canEnroll) {
            Text(
                "This phone is managed. It enrolls automatically with ${managed.serverUrl}.",
                color = DetectorColors.label,
                fontSize = 16.sp,
            )
            if (state is EnrollmentState.Failed) OutlinedButton(onClick = onRetryManaged) { Text("Try again") }
        }

        Text(
            "Or ask an instructor for an enrollment QR code and scan it.",
            color = DetectorColors.label,
            fontSize = 16.sp,
        )
        Button(
            enabled = state !is EnrollmentState.InProgress,
            onClick = {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                if (granted) scanning = true else cameraPermission.launch(Manifest.permission.CAMERA)
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Scan QR code", fontSize = 18.sp) }
    }
}

/** Asks for location (required) and notifications before the meter can start. */
@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DetectorColors.background)
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SimulatorBanner()
        Text("Location needed", color = DetectorColors.reading, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "Sim Sensor uses this phone's location so instructors can see you on the exercise map " +
                "and so virtual leaks can change your readings as you move. It is used only while " +
                "the simulator is running. Notifications are used for alarms when the app is in the background.",
            color = DetectorColors.label,
            fontSize = 16.sp,
        )
        Button(onClick = onRequest, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Allow", fontSize = 18.sp) }
    }
}

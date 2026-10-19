package com.taksolutions.simsensor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taksolutions.simsensor.connection.LinkStatus
import com.taksolutions.simsensor.runtime.DetectorState
import com.taksolutions.simsensor.settings.Enrollment

/** Diagnostics and enrollment management (behind the MDM PIN, when one is set). */
@Composable
fun SettingsScreen(
    enrollment: Enrollment,
    detector: DetectorState,
    appVersion: String,
    onUnenroll: () -> Unit,
    onCalibrate: (beaconId: String) -> Unit,
    onShutdown: () -> Unit,
    onBack: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    var confirmShutdown by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DetectorColors.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState()) // beacon rows can push the buttons off small screens
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SimulatorBanner()
        Text("Settings", color = DetectorColors.reading, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Info("Device", detector.deviceName ?: enrollment.deviceName)
        Info("Device id", enrollment.deviceId)
        Info("Server", enrollment.serverUrl)
        Info("Connection", when (val l = detector.link) {
            is LinkStatus.Connected -> "Connected (server v${l.serverVersion})"
            is LinkStatus.Retrying -> "Offline: ${l.reason}"
            else -> l::class.simpleName.orEmpty()
        })
        Info("Exercise", detector.exercise?.let { "${it.name} (v${it.version})" } ?: "Standby")
        Info("Monitor", detector.exercise?.profile?.let { p -> listOf(p.name, p.model).filter { it.isNotBlank() }.distinct().joinToString(" · ") } ?: "—")
        Info("GPS accuracy", detector.gpsAccuracyM?.let { "±${formatDistance(it, detector.units)}" } ?: "No fix")
        Info("GPS for leaks", detector.gpsStatus.name.lowercase())
        Info("Leaks", detector.exercise?.sources?.count { it.enabled }?.toString() ?: "—")
        detector.exercise?.beacons?.takeIf { it.isNotEmpty() }?.let { beacons ->
            Info("Beacon scan", detector.beaconScan.name.lowercase().replace('_', ' '))
            beacons.forEach { b ->
                val heard = detector.beaconsHeard.firstOrNull { it.beaconId == b.id }
                Info(
                    b.label,
                    heard?.let {
                        "${formatDistance(it.distanceM.toFloat(), detector.units)} (${it.rssi} dBm)" +
                            if (it.presence == 0.0) ", outside gas edge" else ""
                    } ?: "Not heard",
                )
                OutlinedButton(onClick = { onCalibrate(b.id) }, modifier = Modifier.fillMaxWidth()) { Text("Calibrate ${b.label}") }
            }
        }
        Info("App version", appVersion)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to meter") }
        OutlinedButton(onClick = { confirmShutdown = true }, modifier = Modifier.fillMaxWidth()) { Text("Shut down Sim Sensor") }
        Button(
            onClick = { confirm = true },
            colors = ButtonDefaults.buttonColors(containerColor = DetectorColors.bad),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Remove enrollment") }
    }
    // Back returns to the meter (without this, Back sent the whole app to the background).
    androidx.activity.compose.BackHandler(onBack = onBack)
    if (confirmShutdown) {
        ShutdownDialog(onShutdown = { confirmShutdown = false; onShutdown() }, onDismiss = { confirmShutdown = false })
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Remove enrollment?") },
            text = { Text("This phone stops simulating until it is enrolled again with a new QR code or by the MDM.") },
            confirmButton = { TextButton(onClick = { confirm = false; onUnenroll() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(DetectorColors.tile, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, color = DetectorColors.label, fontSize = 14.sp, modifier = Modifier.width(120.dp))
        Text(value, color = DetectorColors.reading, fontSize = 14.sp)
    }
}

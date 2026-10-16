package com.taksolutions.simsensor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taksolutions.simsensor.beacons.BeaconScanStatus
import com.taksolutions.simsensor.connection.LinkStatus
import com.taksolutions.simsensor.location.GpsStatus
import com.taksolutions.simsensor.runtime.DetectorState
import com.taksolutions.simsensor.ui.faces.MeterActions
import com.taksolutions.simsensor.ui.faces.meterFaceFor

/** The simulated meter (docs/DESIGN.md §6.1). */
@Composable
fun DetectorScreen(
    state: DetectorState,
    settingsPin: String?,
    onAcknowledge: () -> Unit,
    onTogglePeak: () -> Unit,
    onDismissMessage: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var askPin by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DetectorColors.background)
            .safeDrawingPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SimulatorBanner()
        StatusRow(state, onSettings = { if (settingsPin == null) onOpenSettings() else askPin = true })
        if (state.exercise == null) {
            Text("STANDBY — not in an exercise", color = DetectorColors.warn, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        if (state.gpsMatters && state.gpsStatus != GpsStatus.GOOD) GpsWarning(state.gpsStatus)
        if (state.beaconsMatter) BeaconWarning(state.beaconScan)
        state.message?.let { MessageCard(it, onDismissMessage) }
        meterFaceFor(state.exercise?.profile?.screen).Draw(
            state = state,
            actions = MeterActions(onAcknowledge = onAcknowledge, onTogglePeak = onTogglePeak),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        SimulatorBanner()
    }

    if (askPin && settingsPin != null) {
        PinDialog(expected = settingsPin, onSuccess = { askPin = false; onOpenSettings() }, onDismiss = { askPin = false })
    }
}

@Composable
private fun StatusRow(state: DetectorState, onSettings: () -> Unit) {
    val (color, text) = when (val link = state.link) {
        is LinkStatus.Connected -> DetectorColors.ok to "Online"
        LinkStatus.Connecting -> DetectorColors.warn to "Connecting"
        is LinkStatus.Retrying -> DetectorColors.warn to "Offline"
        LinkStatus.Rejected -> DetectorColors.bad to "Removed"
        LinkStatus.Disconnected -> DetectorColors.label to "Off"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text("  $text", color = DetectorColors.reading, fontSize = 14.sp)
        Text(
            "   ${state.deviceName.orEmpty()}" + (state.exercise?.let { " · ${it.name}" } ?: ""),
            color = DetectorColors.label,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        state.gpsAccuracyM?.let { Text("GPS ±${formatDistance(it, state.units)}  ", color = DetectorColors.label, fontSize = 12.sp) }
        TextButton(onClick = onSettings) { Text("⚙", fontSize = 20.sp, color = DetectorColors.label) }
    }
}

/** Leak readings follow GPS: say so when the position can't be trusted. */
@Composable
private fun GpsWarning(status: GpsStatus) {
    val text = when (status) {
        GpsStatus.NONE -> "NO GPS FIX — leak readings start once the phone has a position"
        GpsStatus.WEAK -> "GPS WEAK — readings use the last good position"
        GpsStatus.STALE -> "GPS LOST — readings use the last good position"
        GpsStatus.GOOD -> return
    }
    WarningStrip(text)
}

@Composable
private fun BeaconWarning(status: BeaconScanStatus) {
    val text = when (status) {
        BeaconScanStatus.NO_PERMISSION -> "BEACONS: allow Nearby devices in Android settings"
        BeaconScanStatus.BLUETOOTH_OFF -> "BEACONS: turn Bluetooth on"
        BeaconScanStatus.UNSUPPORTED -> "BEACONS: this phone has no Bluetooth LE"
        BeaconScanStatus.FAILED -> "BEACONS: Bluetooth scan failed — retrying"
        BeaconScanStatus.IDLE, BeaconScanStatus.SCANNING -> return
    }
    WarningStrip(text)
}

@Composable
private fun WarningStrip(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(DetectorColors.warn, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MessageCard(text: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DetectorColors.warn, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("INSTRUCTOR: $text", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("OK", color = Color.Black) }
    }
}

@Composable
private fun PinDialog(expected: String, onSuccess: () -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Instructor PIN") },
        text = {
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it; wrong = false },
                singleLine = true,
                isError = wrong,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                supportingText = { if (wrong) Text("Wrong PIN") },
            )
        },
        confirmButton = { TextButton(onClick = { if (pin == expected) onSuccess() else wrong = true }) { Text("Open") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

package com.taksolutions.simsensor.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taksolutions.simsensor.connection.LinkStatus
import com.taksolutions.simsensor.engine.AlarmLevel
import com.taksolutions.simsensor.engine.ChannelReading
import com.taksolutions.simsensor.runtime.DetectorState

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
    KeepScreenOn(state.exercise != null)
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
        state.message?.let { MessageCard(it, onDismissMessage) }
        state.output.channels.forEach { ChannelTile(it, showPeak = state.showPeak, flashing = it.alarms.isNotEmpty()) }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onAcknowledge,
                colors = ButtonDefaults.buttonColors(containerColor = if (state.sounding) DetectorColors.bad else DetectorColors.tile),
                modifier = Modifier.weight(1f).height(64.dp),
            ) { Text(if (state.output.inAlarm) "ACK / SILENCE" else "ACK", fontSize = 18.sp, color = Color.White) }
            Button(
                onClick = onTogglePeak,
                colors = ButtonDefaults.buttonColors(containerColor = if (state.showPeak) DetectorColors.warn else DetectorColors.tile),
                modifier = Modifier.weight(1f).height(64.dp),
            ) { Text(if (state.showPeak) "LIVE" else "PEAK", fontSize = 18.sp, color = Color.White) }
        }
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
        state.gpsAccuracyM?.let { Text("GPS ±${it.toInt()} m  ", color = DetectorColors.label, fontSize = 12.sp) }
        TextButton(onClick = onSettings) { Text("⚙", fontSize = 20.sp, color = DetectorColors.label) }
    }
}

@Composable
private fun ChannelTile(reading: ChannelReading, showPeak: Boolean, flashing: Boolean) {
    val alarmColor = if (AlarmLevel.HIGH in reading.alarms || AlarmLevel.OVER_RANGE in reading.alarms || reading.fault) {
        DetectorColors.bad
    } else {
        DetectorColors.warn
    }
    val flash by rememberInfiniteTransition(label = "alarm").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse),
        label = "flash",
    )
    val lit = flashing && flash > 0.5f
    val background = if (lit) alarmColor else DetectorColors.tile
    val labelColor = if (flashing) Color.White else DetectorColors.label
    val valueColor = if (lit) Color.White else DetectorColors.reading
    val c = reading.channel
    val valueText = when {
        reading.fault -> "FAULT"
        reading.overRange && !showPeak -> "OR"
        else -> formatReading(if (showPeak) reading.peak else reading.value, c.resolution)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(72.dp)) {
            Text(c.label, color = labelColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (showPeak) Text("PEAK", color = DetectorColors.warn, fontSize = 11.sp)
        }
        Text(
            reading.alarms.filter { it != AlarmLevel.FAULT && it != AlarmLevel.OVER_RANGE }.joinToString(" ") { it.name },
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Text(valueText, color = valueColor, fontSize = 40.sp, fontFamily = FontFamily.Monospace)
        Text(" ${c.unit}", color = labelColor, fontSize = 13.sp, modifier = Modifier.width(52.dp))
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

/** Keeps the display on while [enabled] (meter in an exercise and on screen). */
@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

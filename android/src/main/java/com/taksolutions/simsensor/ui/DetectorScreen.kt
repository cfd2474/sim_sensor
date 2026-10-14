package com.taksolutions.simsensor.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taksolutions.simsensor.connection.ConnectionState

/** Placeholder channels until the server pushes a sensor profile (Phase 1). */
private val PLACEHOLDER_CHANNELS = listOf("O₂" to "%VOL", "LEL" to "%LEL", "CO" to "PPM", "H₂S" to "PPM", "HCN" to "PPM")

@Composable
fun DetectorScreen(
    connectionState: ConnectionState,
    serverUrl: String,
    appVersion: String,
    onServerUrlChanged: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DetectorColors.background)
            .safeDrawingPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SimulatorBanner()
        PLACEHOLDER_CHANNELS.forEach { (gas, unit) -> ChannelTile(gas = gas, value = "--", unit = unit) }
        Spacer(Modifier.weight(1f))
        ConnectionPanel(connectionState, serverUrl, onServerUrlChanged, onConnect, onDisconnect)
        Text("v$appVersion", color = DetectorColors.label, fontSize = 12.sp)
        SimulatorBanner()
    }
}

/** Required on every screen: this phone is never a real detector (docs/DESIGN.md §1). */
@Composable
private fun SimulatorBanner() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(DetectorColors.watermark, RoundedCornerShape(4.dp))
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("SIMULATOR — TRAINING ONLY", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun ChannelTile(gas: String, value: String, unit: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DetectorColors.tile, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(gas, color = DetectorColors.label, fontSize = 20.sp, modifier = Modifier.weight(1f))
        Text(value, color = DetectorColors.reading, fontSize = 36.sp, fontFamily = FontFamily.Monospace)
        Text(" $unit", color = DetectorColors.label, fontSize = 14.sp)
    }
}

@Composable
private fun ConnectionPanel(
    state: ConnectionState,
    serverUrl: String,
    onServerUrlChanged: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val (color, label) = when (state) {
        ConnectionState.Idle -> DetectorColors.label to "Not connected"
        ConnectionState.Connecting -> DetectorColors.warn to "Connecting…"
        is ConnectionState.Connected -> DetectorColors.ok to "Connected · server v${state.serverVersion}"
        is ConnectionState.Failed -> DetectorColors.bad to "Failed: ${state.reason}"
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Text("  $label", color = DetectorColors.reading, fontSize = 14.sp)
        }
        OutlinedTextField(
            value = serverUrl,
            onValueChange = onServerUrlChanged,
            label = { Text("Server URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onConnect) { Text("Connect") }
            OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Preview
@Composable
private fun DetectorScreenPreview() {
    SimSensorTheme {
        DetectorScreen(
            connectionState = ConnectionState.Connected("0.1.0", 0),
            serverUrl = "https://simsensor.tak-solutions.com",
            appVersion = "0.1.0",
            onServerUrlChanged = {},
            onConnect = {},
            onDisconnect = {},
        )
    }
}

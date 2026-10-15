package com.taksolutions.simsensor.ui.faces

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taksolutions.simsensor.engine.ChannelReading
import com.taksolutions.simsensor.runtime.DetectorState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiles in two columns, green in clean air, flashing yellow for A1 (low gas) and red for A2
 * (high gas), under a header with the wearer's name, time and date: modelled on the Blackline
 * G8 (operator's 4-gas photo; colours from the G8 manual's low/high urgency notifications). A
 * fifth gas spans the full width (assumption: no 5-gas photo yet).
 */
object G8Face : MeterFace {
    private val screen = Color(0xFF0A0A0A)
    private val green = Color(0xFF33CC33)
    private val greenEdge = Color(0xFF1D8A1D)
    private val yellow = Color(0xFFFFD21F)
    private val yellowDim = Color(0xFF6E5A05)
    private val red = Color(0xFFE32B24)
    private val redDim = Color(0xFF5E1210)
    private val grey = Color(0xFF8A8F8A)

    @Composable
    override fun Draw(state: DetectorState, actions: MeterActions, modifier: Modifier) {
        val blink = rememberBlink(800)
        val channels = state.output.channels
        Column(
            modifier.background(screen, RoundedCornerShape(14.dp)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Header(state, channels.worst())
            AlarmBanner(channels, state.showPeak)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                channels.chunked(2).forEach { pair ->
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { Tile(it, state.showPeak, blink, Modifier.weight(1f).fillMaxHeight()) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Key(if (state.output.inAlarm) "ACK" else "OK", highlight = state.sounding, onClick = actions.onAcknowledge, modifier = Modifier.weight(1f))
                Key(if (state.showPeak) "LIVE" else "PEAK", highlight = state.showPeak, onClick = actions.onTogglePeak, modifier = Modifier.weight(1f))
            }
        }
    }

    @Composable
    private fun Header(state: DetectorState, worst: Severity) {
        val now = Date(rememberNow())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.deviceName ?: "", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                Text(SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(now), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            val (badge, mark) = when (worst) {
                Severity.NONE -> green to "✓"
                Severity.LOW -> yellow to "!"
                Severity.HIGH -> red to "!"
            }
            Box(Modifier.size(36.dp).background(badge, CircleShape), contentAlignment = Alignment.Center) {
                Text(mark, color = if (worst == Severity.LOW) Color.Black else Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    /** The on-screen message the G8 shows with its alarm lights. */
    @Composable
    private fun AlarmBanner(channels: List<ChannelReading>, showPeak: Boolean) {
        val high = channels.filter { it.severity == Severity.HIGH }
        val low = channels.filter { it.severity == Severity.LOW }
        val (text, background, foreground) = when {
            high.isNotEmpty() -> Triple("HIGH GAS  " + high.joinToString { it.channel.label }, red, Color.White)
            low.isNotEmpty() -> Triple("LOW GAS  " + low.joinToString { it.channel.label }, yellow, Color.Black)
            showPeak -> Triple("PEAK READINGS", grey, Color.Black)
            else -> return
        }
        Box(Modifier.fillMaxWidth().background(background, RoundedCornerShape(6.dp)).padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(text, color = foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    private fun Tile(reading: ChannelReading, showPeak: Boolean, blink: Boolean, modifier: Modifier) {
        val (fill, edge) = when {
            reading.fault -> grey to grey
            reading.severity == Severity.HIGH -> (if (blink) red else redDim) to red
            reading.severity == Severity.LOW -> (if (blink) yellow else yellowDim) to yellow
            else -> green to greenEdge
        }
        val dark = fill == redDim || fill == yellowDim
        val text = if (dark) Color.White else Color.Black
        Column(
            modifier.background(fill, RoundedCornerShape(6.dp)).border(2.dp, edge, RoundedCornerShape(6.dp)).padding(6.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(reading.valueText(showPeak), color = text, fontSize = 40.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("${reading.channel.label} ${unitText(reading.channel.unit)}", color = text, fontSize = 16.sp, maxLines = 1)
            reading.alarmLabel?.let { Text(it, color = text, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        }
    }

    /** "H₂S ppm", "O₂ %vol", "LEL %", as on the device. */
    private fun unitText(unit: String): String = when (unit.uppercase()) {
        "PPM" -> "ppm"
        "%VOL" -> "%vol"
        "%LEL" -> "%"
        else -> unit
    }

    @Composable
    private fun Key(label: String, highlight: Boolean, onClick: () -> Unit, modifier: Modifier) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = if (highlight) red else Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(10.dp),
            modifier = modifier.height(56.dp),
        ) { Text(label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
    }
}

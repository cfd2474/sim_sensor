package com.taksolutions.simsensor.ui.faces

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taksolutions.simsensor.engine.ChannelReading
import com.taksolutions.simsensor.runtime.DetectorState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Grey monochrome LCD with a row per gas, a status LED above and two keys below: modelled on
 * the Dräger X-am 2800 (operator's photo) and the X-am 2500 instructions (§3.6): in alarm the
 * value alternates with "A1"/"A2" and the LED flashes once (A1) or twice (A2); the green key is
 * OK (acknowledge), the blue key shows peak values here.
 */
object XamFace : MeterFace {
    private val body = Color(0xFF26282B)
    private val lcdTop = Color(0xFFDDE1E4)
    private val lcdBottom = Color(0xFFB7BCC1)
    private val ink = Color(0xFF1C2541)
    private val ledGreen = Color(0xFF3BE15A)
    private val ledRed = Color(0xFFFF2A1F)
    private val ledOff = Color(0xFF3A3D40)
    private val keyBlue = Color(0xFF1E5DB8)
    private val keyGreen = Color(0xFFA3CB38)

    @Composable
    override fun Draw(state: DetectorState, actions: MeterActions, modifier: Modifier) {
        val channels = state.output.channels
        Column(
            modifier.background(body, RoundedCornerShape(26.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Led(channels.worst())
            Lcd(state, Modifier.weight(1f).fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundKey(keyBlue, "▼≡", Color.White, if (state.showPeak) "Show live values" else "Show peak values", actions.onTogglePeak)
                RoundKey(keyGreen, "OK", ink, "OK, acknowledge", actions.onAcknowledge)
            }
        }
    }

    /** Green while ready; red flashing once per second for A1, twice for A2. */
    @Composable
    private fun Led(worst: Severity) {
        val phase = rememberPhase(1000)
        val color = when (worst) {
            Severity.NONE -> if (phase < 0.85f) ledGreen else ledGreen.copy(alpha = 0.45f)
            Severity.LOW -> if (phase < 0.25f) ledRed else ledOff
            Severity.HIGH -> if (phase < 0.15f || phase in 0.3f..0.45f) ledRed else ledOff
        }
        Box(Modifier.width(44.dp).height(16.dp).background(color, RoundedCornerShape(5.dp)))
    }

    @Composable
    private fun Lcd(state: DetectorState, modifier: Modifier) {
        val alternate = rememberBlink(1000)
        val now = Date(rememberNow())
        val worst = state.output.channels.worst()
        Column(
            modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Brush.verticalGradient(listOf(lcdTop, lcdBottom)))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), color = ink, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            state.showPeak -> "PEAK"
                            worst == Severity.NONE -> "✓"
                            else -> "!"
                        },
                        color = ink, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    )
                }
                Box(Modifier.width(26.dp).height(12.dp).background(ink, RoundedCornerShape(2.dp)))
            }
            state.output.channels.forEach { GasRow(it, state.showPeak, alternate, Modifier.weight(1f)) }
        }
    }

    @Composable
    private fun GasRow(reading: ChannelReading, showPeak: Boolean, alternate: Boolean, modifier: Modifier) {
        val label = reading.alarmLabel
        // In alarm the value and the alarm name take turns, as on the device.
        val shown = if (label != null && alternate && !showPeak) label else reading.valueText(showPeak)
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(80.dp)) {
                Text(reading.channel.label, color = ink, fontSize = 17.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(unitText(reading.channel.unit), color = ink, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
            }
            Text(
                shown,
                color = ink,
                fontSize = 44.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                maxLines = 1,
            )
        }
    }

    /** "%LEL", "Vol%", "ppm", as on the device. */
    private fun unitText(unit: String): String = when (unit.uppercase()) {
        "PPM" -> "ppm"
        "%VOL" -> "Vol%"
        else -> unit
    }

    @Composable
    private fun RoundKey(color: Color, text: String, textColor: Color, description: String, onClick: () -> Unit) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(color)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) { Text(text, color = textColor, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
    }
}

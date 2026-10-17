package com.taksolutions.simsensor.ui.faces

import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taksolutions.simsensor.engine.AlarmLevel
import com.taksolutions.simsensor.engine.ChannelReading
import com.taksolutions.simsensor.runtime.DetectorState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Grey LCD with a status bar (signal, GPS, wearer, time, battery) over a grid of gas cells in two
 * columns (label top left, large value, unit bottom right; VOC shows "Isobutylene"), a light bar
 * on top and three keys below: modelled on the MSA ALTAIR io 6 (operator's photo, 2026-10-17).
 *
 * Assumptions, as no io 6 manual or alarm photo was found: a cell in alarm inverts and flashes
 * (A1 once a second, A2 twice) with LOW / HIGH / STEL / TWA in place of its unit, and the light
 * bar flashes red; in clean air it blinks green. The round key is OK / acknowledge, the chevrons
 * switch between live and peak values.
 */
object Io6Face : MeterFace {
    private val body = Color(0xFF1B1C1E)
    private val bezel = Color(0xFF3A3C40)
    private val lcd = Color(0xFFD5D8D3)
    private val statusBar = Color(0xFFE6E8E4)
    private val ink = Color(0xFF16181A)
    private val grid = Color(0xFF55595C)
    private val lightGreen = Color(0xFF36E06A)
    private val lightRed = Color(0xFFFF3B2F)
    private val lightOff = Color(0xFF2B3A30)
    private val key = Color(0xFF2E3034)
    private val keyEdge = Color(0xFF55585E)
    private val accent = Color(0xFFE5392F)
    private val keyText = Color(0xFFCFD2D6)

    @Composable
    override fun Draw(state: DetectorState, actions: MeterActions, modifier: Modifier) {
        val worst = state.output.channels.worst()
        Column(
            modifier.background(body, RoundedCornerShape(28.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LightBar(worst)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .border(3.dp, bezel, RoundedCornerShape(10.dp))
                    .padding(3.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(lcd),
            ) {
                StatusBar(state)
                Cells(state, Modifier.weight(1f).fillMaxWidth())
            }
            Keys(state, actions)
            Box(Modifier.width(120.dp).height(10.dp).border(2.dp, accent, RoundedCornerShape(5.dp)))
        }
    }

    /** Blinks green while all is well; flashes red once a second for A1, twice for A2. */
    @Composable
    private fun LightBar(worst: Severity) {
        val phase = rememberPhase(1000)
        val color = when (worst) {
            Severity.NONE -> if (phase < 0.12f) lightGreen else lightGreen.copy(alpha = 0.35f)
            Severity.LOW -> if (phase < 0.3f) lightRed else lightOff
            Severity.HIGH -> if (phase < 0.2f || phase in 0.5f..0.7f) lightRed else lightOff
        }
        Box(Modifier.width(150.dp).height(14.dp).background(color, RoundedCornerShape(7.dp)))
    }

    @Composable
    private fun StatusBar(state: DetectorState) {
        val now = rememberNow()
        val battery = rememberBatteryPercent(now)
        Row(
            Modifier.fillMaxWidth().background(statusBar).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SignalIcon()
            PinIcon()
            PersonIcon()
            Text(
                if (state.showPeak) "PEAK" else "",
                color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
            )
            Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)), color = ink, fontSize = 14.sp)
            Spacer(Modifier.width(6.dp))
            battery?.let { Text("$it%", color = ink, fontSize = 14.sp) }
            BatteryIcon(battery)
        }
    }

    /** Two columns, as many rows as needed (three for six gases); an odd last cell stays empty. */
    @Composable
    private fun Cells(state: DetectorState, modifier: Modifier) {
        val blinkSlow = rememberBlink(1000)
        val blinkFast = rememberBlink(500)
        Column(modifier) {
            state.output.channels.chunked(2).forEachIndexed { row, pair ->
                if (row > 0) Box(Modifier.fillMaxWidth().height(2.dp).background(grid))
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Cell(pair[0], state.showPeak, blinkSlow, blinkFast, Modifier.weight(1f).fillMaxHeight())
                    Box(Modifier.width(2.dp).fillMaxHeight().background(grid))
                    if (pair.size > 1) {
                        Cell(pair[1], state.showPeak, blinkSlow, blinkFast, Modifier.weight(1f).fillMaxHeight())
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }

    @Composable
    private fun Cell(reading: ChannelReading, showPeak: Boolean, blinkSlow: Boolean, blinkFast: Boolean, modifier: Modifier) {
        val inverted = when (reading.severity) {
            Severity.NONE -> false
            Severity.LOW -> blinkSlow
            Severity.HIGH -> blinkFast
        } && !reading.fault
        val background = if (inverted) ink else lcd
        val text = if (inverted) lcd else ink
        val isVoc = reading.channel.id == "VOC"
        val value = reading.valueText(showPeak)
        Box(modifier.background(background).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(label(reading), color = text, fontSize = 15.sp, modifier = Modifier.align(Alignment.TopStart))
            if (isVoc) Text(unit(reading), color = text, fontSize = 13.sp, modifier = Modifier.align(Alignment.TopEnd))
            Text(
                value,
                color = text,
                fontSize = when {
                    value.length <= 4 -> 46.sp
                    value.length == 5 -> 40.sp
                    else -> 32.sp
                },
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.align(Alignment.Center),
            )
            val bottom = alarmWord(reading) ?: if (isVoc) "Isobutylene" else unit(reading)
            Text(
                bottom,
                color = text,
                fontSize = 13.sp,
                fontWeight = if (alarmWord(reading) != null) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.align(if (isVoc && alarmWord(reading) == null) Alignment.BottomCenter else Alignment.BottomEnd),
            )
        }
    }

    /** Plain labels as on the device: "O2", "H2S", "COMB" for the combustible sensor. */
    private fun label(reading: ChannelReading): String = when (reading.channel.id) {
        "LEL" -> "COMB"
        else -> reading.channel.id
    }

    /** "%VOL", "PPM", "%LEL", as on the device. */
    private fun unit(reading: ChannelReading): String = reading.channel.unit.uppercase()

    /** The alarm shown in a cell in place of its unit (worst first). */
    private fun alarmWord(reading: ChannelReading): String? = when {
        reading.fault -> null
        AlarmLevel.OVER_RANGE in reading.alarms -> "OVER"
        AlarmLevel.HIGH in reading.alarms -> "HIGH"
        AlarmLevel.LOW in reading.alarms -> "LOW"
        AlarmLevel.STEL in reading.alarms -> "STEL"
        AlarmLevel.TWA in reading.alarms -> "TWA"
        else -> null
    }

    @Composable
    private fun Keys(state: DetectorState, actions: MeterActions) {
        val peakHelp = if (state.showPeak) "Show live values" else "Show peak values"
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Key(peakHelp, Modifier.size(width = 76.dp, height = 52.dp), RoundedCornerShape(14.dp), actions.onTogglePeak) { Chevron(up = false) }
            Key("OK, acknowledge", Modifier.size(64.dp), CircleShape, actions.onAcknowledge) {
                if (state.output.inAlarm) Text("ACK", color = keyText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                else Box(Modifier.size(22.dp).border(3.dp, keyText, CircleShape))
            }
            Key(peakHelp, Modifier.size(width = 76.dp, height = 52.dp), RoundedCornerShape(14.dp), actions.onTogglePeak) { Chevron(up = true) }
        }
    }

    @Composable
    private fun Key(description: String, modifier: Modifier, shape: androidx.compose.ui.graphics.Shape, onClick: () -> Unit, content: @Composable () -> Unit) {
        Box(
            modifier
                .clip(shape)
                .background(key)
                .border(2.dp, keyEdge, shape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) { content() }
    }

    @Composable
    private fun Chevron(up: Boolean) = Canvas(Modifier.size(width = 26.dp, height = 14.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            if (up) { moveTo(0f, h); lineTo(w / 2, 0f); lineTo(w, h) } else { moveTo(0f, 0f); lineTo(w / 2, h); lineTo(w, 0f) }
        }
        drawPath(path, keyText, style = Stroke(3.dp.toPx()))
    }

    // --- Status icons (drawn; no icon library in the app) ---------------------------------------

    @Composable
    private fun SignalIcon(size: Dp = 16.dp) = Canvas(Modifier.size(size)) {
        val w = this.size.width / 7
        for (i in 0 until 4) {
            val h = this.size.height * (i + 1) / 4
            drawRect(ink, topLeft = Offset(i * 2 * w, this.size.height - h), size = Size(w * 1.2f, h))
        }
    }

    @Composable
    private fun PinIcon(size: Dp = 16.dp) = Canvas(Modifier.size(size)) {
        val r = this.size.width * 0.32f
        val c = Offset(this.size.width / 2, r + 1f)
        val path = Path().apply {
            moveTo(c.x - r * 0.85f, c.y + r * 0.5f)
            lineTo(c.x, this@Canvas.size.height)
            lineTo(c.x + r * 0.85f, c.y + r * 0.5f)
            close()
        }
        drawPath(path, ink)
        drawCircle(ink, r, c)
        drawCircle(statusBar, r * 0.4f, c)
    }

    @Composable
    private fun PersonIcon(size: Dp = 16.dp) = Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawCircle(ink, w * 0.22f, Offset(w / 2, w * 0.26f))
        drawRoundRect(ink, topLeft = Offset(w * 0.15f, w * 0.55f), size = Size(w * 0.7f, w * 0.45f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.3f))
    }

    @Composable
    private fun BatteryIcon(percent: Int?, width: Dp = 12.dp) = Canvas(Modifier.size(width = width, height = 18.dp)) {
        val stroke = 1.5.dp.toPx()
        drawRect(ink, topLeft = Offset(this.size.width * 0.3f, 0f), size = Size(this.size.width * 0.4f, 2.dp.toPx()))
        val top = 2.dp.toPx()
        drawRect(ink, topLeft = Offset(0f, top), size = Size(this.size.width, this.size.height - top), style = Stroke(stroke))
        val inner = this.size.height - top - stroke * 2
        val level = inner * ((percent ?: 0).coerceIn(0, 100) / 100f)
        drawRect(ink, topLeft = Offset(stroke, this.size.height - stroke - level), size = Size(this.size.width - stroke * 2, level))
    }

    /** The phone's own battery level, read again every minute ([now] ticks every second). */
    @Composable
    private fun rememberBatteryPercent(now: Long): Int? {
        val context = LocalContext.current
        val minute = now / 60_000
        return remember(minute) {
            context.getSystemService(BatteryManager::class.java)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 }
        }
    }
}

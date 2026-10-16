package com.taksolutions.simsensor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taksolutions.simsensor.protocol.Units
import com.taksolutions.simsensor.runtime.DetectorState
import com.taksolutions.simsensor.source.BeaconCalibration
import com.taksolutions.simsensor.source.BeaconRef
import com.taksolutions.simsensor.source.CalibrationPoint
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToLong

/** How long each distance is recorded; the phone's smoothed signal is sampled once a second. */
private const val RECORD_S = 15
/** Exact by definition; dividing by 3.28084 instead left noise such as 25 ft → 7.6199997 m. */
private const val METRES_PER_FOOT = 0.3048

/** One distance to measure: [distanceM] in metres, [rssi] once recorded. */
private data class Measurement(val id: Int, val distanceM: Double, val rssi: Double? = null, val samples: Int = 0)

/**
 * Settings → beacon → Calibrate (operator decision 2026-10-15: calibration happens on the phone).
 * The phone is held at known distances from the beacon (1 m, then 3 m by default); the median of
 * its smoothed signal over [RECORD_S] s is recorded at each, and the server fits the beacon's power
 * at 1 m and path loss from them. [onSave] returns false when the phone is offline.
 */
@Composable
fun CalibrationScreen(
    beacon: BeaconRef,
    detector: DetectorState,
    onSave: (List<CalibrationPoint>) -> Boolean,
    onBack: () -> Unit,
) {
    val units = detector.units
    val heard = detector.beaconsHeard.firstOrNull { it.beaconId == beacon.id }
    val latestRssi by rememberUpdatedState(heard?.rssi)
    val points = remember { mutableStateListOf(Measurement(0, 1.0), Measurement(1, 3.0)) }
    var nextId by remember { mutableStateOf(2) }
    var recording by remember { mutableStateOf<Int?>(null) }
    var secondsLeft by remember { mutableStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(false) }
    val result = detector.calibration?.takeIf { it.beaconId == beacon.id && saving }

    LaunchedEffect(recording) {
        val index = recording ?: return@LaunchedEffect
        val values = mutableListOf<Int>()
        for (s in RECORD_S downTo 1) {
            secondsLeft = s
            delay(1_000)
            latestRssi?.let(values::add)
        }
        points[index] = points[index].copy(rssi = values.sorted().getOrNull(values.size / 2)?.toDouble(), samples = values.size)
        recording = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DetectorColors.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SimulatorBanner()
        Text("Calibrate ${beacon.label}", color = DetectorColors.reading, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Note(
            "Use this phone at the site where the beacon will be used. Hold the phone at chest height with a clear line to " +
                "the beacon. At each distance wait a few seconds, then press Record and stand still for $RECORD_S s.",
        )
        Note(
            "Now: signal at 1 m ${beacon.txPowerAt1m?.let { "${oneDecimal(it)} dBm" } ?: "from the beacon"}, fade n = ${beacon.pathLossN}",
        )
        Tile {
            Text(
                heard?.let { "Hears it: ${it.rssi} dBm, about ${formatDistance(it.distanceM.toFloat(), units)}" }
                    ?: "Doesn't hear the beacon yet",
                color = if (heard != null) DetectorColors.reading else DetectorColors.warn,
                fontSize = 16.sp,
            )
        }
        points.forEachIndexed { i, p ->
            key(p.id) { Tile {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DistanceField(p.distanceM, units, enabled = recording == null) { metres ->
                            points[i] = p.copy(distanceM = metres, rssi = null, samples = 0)
                        }
                        Text(
                            when {
                                recording == i -> "Recording… $secondsLeft s"
                                p.rssi != null -> "${p.rssi.toInt()} dBm (${p.samples} readings)"
                                p.samples == 0 && p.rssi == null -> "—"
                                else -> "Not heard, try again"
                            },
                            color = DetectorColors.reading,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { saving = false; recording = i }, enabled = recording == null && heard != null && p.distanceM > 0) {
                            Text(if (p.rssi != null) "Record again" else "Record")
                        }
                        if (points.size > 1) {
                            OutlinedButton(onClick = { points.removeAt(i) }, enabled = recording == null) { Text("Remove") }
                        }
                    }
                }
            } }
        }
        OutlinedButton(onClick = { points.add(Measurement(nextId++, 5.0)) }, enabled = recording == null, modifier = Modifier.fillMaxWidth()) {
            Text("Add a distance")
        }
        val measured = points.filter { it.rssi != null }
        if (measured.map { it.distanceM }.distinct().size < 2) {
            Note("With one distance only the signal at 1 m is set; two or more also set how fast it fades.")
        }
        Button(
            onClick = {
                val sent = onSave(measured.map { CalibrationPoint(it.distanceM, it.rssi!!) })
                offline = !sent
                saving = sent
            },
            enabled = recording == null && measured.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save calibration") }
        if (offline) Warning("Not connected to the server. Try again when the phone is online.")
        if (saving && result == null) Note("Saving…")
        if (result != null) {
            if (result.ok) {
                Tile {
                    Text(
                        "Saved: signal at 1 m ${result.txPowerAt1m} dBm, fade n = ${result.pathLossN}. " +
                            "Measurements are within about ${"%.1f".format(result.rmsErrorDb ?: 0.0)} dB of the curve.",
                        color = DetectorColors.reading,
                        fontSize = 16.sp,
                    )
                }
                if ((result.rmsErrorDb ?: 0.0) > 3.0) Warning("The measurements don't fit well. Check the distances and record again.")
                val n = result.pathLossN ?: 0.0
                if (n <= BeaconCalibration.MIN_N || n >= BeaconCalibration.MAX_N) {
                    Warning("The fade came out at its limit. A measurement was probably blocked; record again.")
                }
            } else {
                Warning("Not saved: ${result.message}")
            }
        }
        OutlinedButton(onClick = onBack, enabled = recording == null, modifier = Modifier.fillMaxWidth()) { Text("Back to settings") }
    }
}

/** A distance typed in the display units, reported in metres. */
@Composable
private fun DistanceField(metres: Double, units: Units, enabled: Boolean, onChange: (Double) -> Unit) {
    val imperial = units == Units.IMPERIAL
    val shown = if (imperial) metres / METRES_PER_FOOT else metres
    var text by remember(units) { mutableStateOf(oneDecimal(shown)) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            t.toDoubleOrNull()?.takeIf { it > 0 }?.let { onChange(toMillimetre(if (imperial) it * METRES_PER_FOOT else it)) }
        },
        enabled = enabled,
        singleLine = true,
        suffix = { Text(if (imperial) "ft" else "m") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.width(130.dp),
    )
}

/** "-58.5", "-59", "3.3": one decimal, dropped when zero, always with a dot so it reads back as a number. */
private fun oneDecimal(v: Double): String = String.format(Locale.US, "%.1f", v).removeSuffix(".0")

private fun toMillimetre(metres: Double): Double = (metres * 1000).roundToLong() / 1000.0

@Composable
private fun Tile(content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(DetectorColors.tile, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) { content() }
}

@Composable
private fun Note(text: String) = Text(text, color = DetectorColors.label, fontSize = 14.sp)

@Composable
private fun Warning(text: String) {
    Text(
        text,
        color = Color.Black,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .background(DetectorColors.warn, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

package com.taksolutions.simsensor.ui.faces

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.taksolutions.simsensor.engine.AlarmLevel
import com.taksolutions.simsensor.engine.ChannelReading
import com.taksolutions.simsensor.ui.formatReading
import kotlinx.coroutines.delay

/** How serious a channel's alarm is, for colours: none, A1-like, A2-like. */
enum class Severity { NONE, LOW, HIGH }

val ChannelReading.severity: Severity
    get() = when {
        fault || AlarmLevel.HIGH in alarms || AlarmLevel.OVER_RANGE in alarms -> Severity.HIGH
        alarms.isNotEmpty() -> Severity.LOW
        else -> Severity.NONE
    }

/** The worst severity on the meter. */
fun List<ChannelReading>.worst(): Severity = maxOfOrNull { it.severity } ?: Severity.NONE

/** The label shown for the channel's alarm (A2 before A1, then STEL, TWA). */
val ChannelReading.alarmLabel: String?
    get() = listOf(AlarmLevel.HIGH, AlarmLevel.LOW, AlarmLevel.STEL, AlarmLevel.TWA).firstOrNull { it in alarms }?.label

/** What the value position shows: the reading (or peak), "OR" over range, "FAULT". */
fun ChannelReading.valueText(showPeak: Boolean): String = when {
    fault -> "FAULT"
    overRange && !showPeak -> "OR"
    else -> formatReading(if (showPeak) peak else value, channel.resolution)
}

/** 0..1, cycling every [periodMillis] (drives flashing). */
@Composable
fun rememberPhase(periodMillis: Int): Float {
    val phase by rememberInfiniteTransition(label = "phase").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMillis, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    return phase
}

/** True for the first half of every [periodMillis]. */
@Composable
fun rememberBlink(periodMillis: Int = 1000): Boolean = rememberPhase(periodMillis) < 0.5f

/** Wall-clock time, updated every second (for the clocks some meters show). */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }
    return now
}

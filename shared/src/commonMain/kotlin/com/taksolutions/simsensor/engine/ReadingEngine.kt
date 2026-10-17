package com.taksolutions.simsensor.engine

import com.taksolutions.simsensor.profile.ChannelConfig
import com.taksolutions.simsensor.profile.SensorProfile
import com.taksolutions.simsensor.protocol.OverrideMode
import com.taksolutions.simsensor.protocol.ReadingOverride
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Alarm conditions a channel can be in. [label] is what people see: the low and high alarms
 * are A1 and A2 (industry names); the enum names stay on the wire ("CO:HIGH").
 */
enum class AlarmLevel(val label: String) {
    LOW("A1"),
    HIGH("A2"),
    STEL("STEL"),
    TWA("TWA"),
    /** Over the sensor's limit (above its range): shown as OL (operator, 2026-10-17). */
    OVER_RANGE("OL"),
    FAULT("FAULT"),
}

/** One channel's output for a tick. */
data class ChannelReading(
    val channel: ChannelConfig,
    /** Rounded to the channel resolution and clamped to the range. Null while in fault. */
    val value: Double?,
    val overRange: Boolean,
    val fault: Boolean,
    /** Conditions present right now. */
    val active: Set<AlarmLevel>,
    /**
     * What the meter shows: [active] plus latched alarms not yet acknowledged, with A1 (LOW)
     * left out while A2 (HIGH) is shown in the same direction.
     */
    val alarms: Set<AlarmLevel>,
    /** Highest reading (lowest for a falling-alarm channel such as O₂) since the last reset. */
    val peak: Double,
)

/** Everything the meter shows for a tick. */
data class EngineOutput(val channels: List<ChannelReading>) {
    val inAlarm: Boolean get() = channels.any { it.alarms.isNotEmpty() }

    /** Alarm codes as sent in telemetry, e.g. "CO:HIGH". */
    val alarmCodes: List<String>
        get() = channels.flatMap { c -> c.alarms.sortedBy { it.ordinal }.map { "${c.channel.id}:${it.name}" } }

    /**
     * Conditions present right now, as codes. An alarm is "new" when its condition starts, not
     * when it reappears on the display (A1 hidden behind an acknowledged A2 is not new).
     */
    val activeCodes: Set<String>
        get() = channels.flatMapTo(mutableSetOf()) { c -> c.active.map { "${c.channel.id}:${it.name}" } }

    /** Displayed values for telemetry (faulted channels omitted). */
    val readings: Map<String, Double>
        get() = channels.mapNotNull { c -> c.value?.let { c.channel.id to it } }.toMap()
}

/**
 * Simulated sensor physics for one device (docs/DESIGN.md §8). Pure and deterministic given
 * its [random]: the phone runs it a few times per second, the server and console can run the
 * same code for previews.
 *
 * Per channel and tick:
 * 1. true concentration = baseline + source contributions (GPS/beacon sources: Phases 2–3)
 * 2. instructor override (channel-specific beats an all-channel one): FIXED, RAMP, OFFSET, FAULT
 * 3. sensor lag: first-order response reaching 90 % of a step after T90
 * 4. noise, clamp to range (over-range above), round to resolution
 * 5. alarms: A1/A2 (low/high; O₂ low alarm falls), STEL (15-min average), TWA (8-h weighted),
 *    each latching or not as its channel says. A2 replaces A1 on the display.
 */
class ReadingEngine(
    profile: SensorProfile,
    private val random: Random = Random.Default,
) {
    var profile: SensorProfile = profile
        private set

    private val states = mutableMapOf<String, ChannelState>()

    /** Swaps in a new profile (exercise criteria changed). Sensor state of kept channels survives. */
    fun updateProfile(newProfile: SensorProfile) {
        profile = newProfile
        states.keys.retainAll(newProfile.channels.map { it.id }.toSet())
    }

    /**
     * Advances to [nowMillis] (device time corrected to server time) and returns what to show.
     * @param contributions true-concentration additions per channel from sources
     */
    fun step(
        nowMillis: Long,
        overrides: List<ReadingOverride> = emptyList(),
        contributions: Map<String, Double> = emptyMap(),
    ): EngineOutput = EngineOutput(
        profile.channels.map { channel ->
            val state = states.getOrPut(channel.id) { ChannelState(channel.baseline, nowMillis) }
            val override = overrides.firstOrNull { it.channelId == channel.id } ?: overrides.firstOrNull { it.channelId == null }
            stepChannel(channel, state, nowMillis, override, contributions[channel.id] ?: 0.0)
        },
    )

    /** Acknowledge: clears latched alarms whose condition has gone. Active alarms stay. */
    fun acknowledge() {
        states.values.forEach { it.latched.retainAll(it.lastActive) }
    }

    /** Resets peaks (the meter's "clear peak" action). */
    fun resetPeaks() {
        states.values.forEach { it.peak = null }
    }

    private fun stepChannel(
        channel: ChannelConfig,
        state: ChannelState,
        now: Long,
        override: ReadingOverride?,
        contribution: Double,
    ): ChannelReading {
        val natural = channel.baseline + contribution
        val truth = when (override?.mode) {
            null -> natural
            OverrideMode.FIXED -> override.value ?: natural
            OverrideMode.OFFSET -> natural + (override.value ?: 0.0)
            OverrideMode.RAMP -> ramp(state, override, now)
            OverrideMode.FAULT -> natural
        }
        if (override?.mode != OverrideMode.RAMP) state.rampKey = null

        // First-order lag: after T90 the sensor shows 90 % of a step change.
        val dtSeconds = max(0.0, (now - state.lastMillis) / 1000.0)
        state.lastMillis = now
        val tau = channel.t90Seconds / LN_10
        state.sensed = if (tau <= 0.0) truth else state.sensed + (truth - state.sensed) * (1 - exp(-dtSeconds / tau))

        val fault = override?.mode == OverrideMode.FAULT
        val noisy = state.sensed + if (channel.noise > 0) random.nextDouble(-channel.noise, channel.noise) else 0.0
        val overRange = noisy > channel.rangeMax
        val clamped = min(max(noisy, channel.rangeMin), channel.rangeMax)
        val shown = roundTo(clamped, channel.resolution)

        if (!fault) {
            state.exposure.add(now, shown)
            state.peak = when {
                state.peak == null -> shown
                channel.lowAlarmFalling -> min(state.peak!!, shown)
                else -> max(state.peak!!, shown)
            }
        }

        val active = if (fault) setOf(AlarmLevel.FAULT) else alarmsFor(channel, shown, overRange, state.exposure)
        state.lastActive = active
        state.latched += active.filter { latches(channel, it) }
        val alarms = shown(channel, active + state.latched)

        return ChannelReading(
            channel = channel,
            value = if (fault) null else shown,
            overRange = overRange && !fault,
            fault = fault,
            active = active,
            alarms = alarms,
            peak = state.peak ?: shown,
        )
    }

    /** RAMP from the true concentration when the override was first seen to its target. */
    private fun ramp(state: ChannelState, override: ReadingOverride, now: Long): Double {
        val key = "${override.startedAtMillis}:${override.value}:${override.rampSeconds}"
        if (state.rampKey != key) {
            state.rampKey = key
            state.rampFrom = state.sensed
        }
        val target = override.value ?: return state.rampFrom
        val seconds = override.rampSeconds ?: 0.0
        if (seconds <= 0.0) return target
        val progress = ((now - override.startedAtMillis) / 1000.0 / seconds).coerceIn(0.0, 1.0)
        return state.rampFrom + (target - state.rampFrom) * progress
    }

    private fun latches(c: ChannelConfig, level: AlarmLevel): Boolean = when (level) {
        AlarmLevel.LOW -> c.lowAlarmLatching
        AlarmLevel.HIGH, AlarmLevel.OVER_RANGE -> c.highAlarmLatching
        AlarmLevel.STEL -> c.stelAlarmLatching
        AlarmLevel.TWA -> c.twaAlarmLatching
        AlarmLevel.FAULT -> false
    }

    /**
     * One alarm per direction: A2 (HIGH) supersedes A1 (LOW) on a rising channel. On O₂ they
     * point opposite ways (low oxygen vs enrichment), so both stay visible.
     */
    private fun shown(c: ChannelConfig, alarms: Set<AlarmLevel>): Set<AlarmLevel> =
        if (!c.lowAlarmFalling && AlarmLevel.HIGH in alarms) alarms - AlarmLevel.LOW else alarms

    private fun alarmsFor(c: ChannelConfig, value: Double, overRange: Boolean, exposure: Exposure): Set<AlarmLevel> =
        buildSet {
            val low = c.lowAlarm
            val high = c.highAlarm
            if (c.lowAlarmFalling) {
                if (low != null && value <= low) add(AlarmLevel.LOW)
                if (high != null && value >= high) add(AlarmLevel.HIGH)
            } else {
                if (low != null && value >= low) add(AlarmLevel.LOW)
                if (high != null && value >= high) add(AlarmLevel.HIGH)
            }
            c.stelAlarm?.let { if (exposure.stel() >= it) add(AlarmLevel.STEL) }
            c.twaAlarm?.let { if (exposure.twa() >= it) add(AlarmLevel.TWA) }
            if (overRange) {
                add(AlarmLevel.OVER_RANGE)
                if (high != null) add(AlarmLevel.HIGH)
            }
        }

    private class ChannelState(var sensed: Double, var lastMillis: Long) {
        var peak: Double? = null
        var rampKey: String? = null
        var rampFrom: Double = sensed
        var lastActive: Set<AlarmLevel> = emptySet()
        val latched = mutableSetOf<AlarmLevel>()
        val exposure = Exposure(lastMillis)
    }

    private companion object {
        val LN_10 = ln(10.0)

        fun roundTo(value: Double, resolution: Double): Double {
            if (resolution <= 0.0) return value
            val steps = (value / resolution).roundToLong()
            // Re-derive through the number of decimals to avoid 20.900000000000002.
            val decimals = decimalsOf(resolution)
            val scale = pow10(decimals)
            return (steps * resolution * scale).roundToLong() / scale
        }

        private fun decimalsOf(resolution: Double): Int {
            var d = 0
            var r = resolution
            while (d < 6 && floor(r) != r) {
                r *= 10
                d++
            }
            return d
        }

        private fun pow10(n: Int): Double {
            var p = 1.0
            repeat(n) { p *= 10 }
            return p
        }
    }
}

/**
 * Exposure averages. STEL: average over the last 15 minutes (per-second buckets kept for
 * 15 min). TWA: time-weighted sum since the engine started, divided by 8 hours, the way
 * meters report a running 8-hour TWA.
 */
internal class Exposure(private var lastMillis: Long) {
    private val window = ArrayDeque<Pair<Long, Double>>() // (end millis, value·seconds)
    private var windowSum = 0.0
    private var twaSum = 0.0

    fun add(now: Long, value: Double) {
        val dt = max(0.0, (now - lastMillis) / 1000.0)
        lastMillis = now
        if (dt == 0.0) return
        window.addLast(now to value * dt)
        windowSum += value * dt
        twaSum += value * dt
        val cutoff = now - STEL_MILLIS
        while (window.isNotEmpty() && window.first().first < cutoff) {
            val (_, removed) = window.removeFirst()
            windowSum -= removed
        }
    }

    /** Average over the last 15 minutes, counting time before the meter started as zero exposure. */
    fun stel(): Double = windowSum / (STEL_MILLIS / 1000.0)

    fun twa(): Double = twaSum / TWA_SECONDS

    private companion object {
        const val STEL_MILLIS = 15 * 60 * 1000L
        const val TWA_SECONDS = 8 * 3600.0
    }
}

package com.taksolutions.simsensor.runtime

import com.taksolutions.simsensor.alarm.AlarmAnnunciator
import com.taksolutions.simsensor.connection.LinkStatus
import com.taksolutions.simsensor.connection.ServerLink
import com.taksolutions.simsensor.engine.EngineOutput
import com.taksolutions.simsensor.engine.ReadingEngine
import com.taksolutions.simsensor.location.GpsQuality
import com.taksolutions.simsensor.location.GpsStatus
import com.taksolutions.simsensor.location.LocationSource
import com.taksolutions.simsensor.profile.DefaultProfiles
import com.taksolutions.simsensor.protocol.CommandKind
import com.taksolutions.simsensor.protocol.DeviceCommand
import com.taksolutions.simsensor.protocol.DeviceInfo
import com.taksolutions.simsensor.protocol.DeviceEvent
import com.taksolutions.simsensor.protocol.ExerciseSnapshot
import com.taksolutions.simsensor.protocol.ExerciseUpdate
import com.taksolutions.simsensor.protocol.OverridesUpdate
import com.taksolutions.simsensor.protocol.ProtocolMessage
import com.taksolutions.simsensor.protocol.ReadingOverride
import com.taksolutions.simsensor.protocol.SettingsUpdate
import com.taksolutions.simsensor.protocol.Units
import com.taksolutions.simsensor.protocol.Telemetry
import com.taksolutions.simsensor.protocol.TelemetrySample
import com.taksolutions.simsensor.protocol.Welcome
import com.taksolutions.simsensor.settings.Enrollment
import com.taksolutions.simsensor.source.SourceModel
import com.taksolutions.simsensor.telemetry.TelemetryQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Everything the detector screen and notifications show. */
data class DetectorState(
    val deviceName: String? = null,
    val link: LinkStatus = LinkStatus.Disconnected,
    /** Null = standby (not in an exercise). */
    val exercise: ExerciseSnapshot? = null,
    val output: EngineOutput = EngineOutput(emptyList()),
    val silenced: Boolean = false,
    val showPeak: Boolean = false,
    /** Message an instructor sent to this screen. */
    val message: String? = null,
    val gpsAccuracyM: Float? = null,
    /** Quality of the position used for leak readings. */
    val gpsStatus: GpsStatus = GpsStatus.NONE,
    /** Display units chosen by the admin. */
    val units: Units = Units.IMPERIAL,
    val running: Boolean = false,
    /** State of beacon scanning (only meaningful while the exercise has beacons). */
    val beaconScan: com.taksolutions.simsensor.beacons.BeaconScanStatus = com.taksolutions.simsensor.beacons.BeaconScanStatus.IDLE,
    /** Beacons heard, with estimated distance (Settings screen). */
    val beaconsHeard: List<com.taksolutions.simsensor.protocol.BeaconHeard> = emptyList(),
    /** The server's answer to the last calibration sent (Calibrate screen). */
    val calibration: com.taksolutions.simsensor.protocol.CalibrationResult? = null,
    /** The Calibrate screen is open: the phone is held at the beacon, so readings and alarms pause. */
    val calibrating: Boolean = false,
) {
    /** The exercise has ended (or been archived): the meter rests at baseline with no alarms. */
    val exerciseFinished: Boolean get() = exercise?.state.let { it == "ended" || it == "archived" }

    /** Leak readings depend on GPS only when the exercise has enabled map leaks (not beacon ones). */
    val gpsMatters: Boolean get() = exercise?.sources?.any { it.enabled && it.shape !is com.taksolutions.simsensor.source.BeaconShape } == true

    /** Beacon leaks need Bluetooth scanning: why it isn't working, when it isn't. */
    val beaconsMatter: Boolean get() = exercise?.sources?.any { it.enabled && it.shape is com.taksolutions.simsensor.source.BeaconShape } == true
    val sounding: Boolean get() = output.inAlarm && !silenced && !calibrating
}

/**
 * The simulated meter: applies server state (exercise, overrides, commands), runs the reading
 * engine a few times per second, queues telemetry and drives the alarm sound.
 *
 * All its coroutines run in the [scope] it is started with, which must be single-threaded
 * (the container gives it one), so its state needs no locking.
 */
class DetectorRuntime(
    private val link: ServerLink,
    private val location: LocationSource,
    private val beaconScanner: com.taksolutions.simsensor.beacons.BeaconScanner,
    private val queue: TelemetryQueue,
    private val annunciator: AlarmAnnunciator,
    private val battery: () -> Float?,
    private val onRejected: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(DetectorState())
    val state: StateFlow<DetectorState> = _state.asStateFlow()

    private var engine = ReadingEngine(DefaultProfiles.FIVE_GAS)
    private var overrides: List<ReadingOverride> = emptyList()
    private var overridesVersion = -1
    private var clockOffsetMillis = 0L
    private var telemetryIntervalMillis: Long? = null // set by an instructor command
    private var lastAlarmCodes: Set<String> = emptySet()
    private val gps = GpsQuality()
    private var job: Job? = null
    private lateinit var scope: CoroutineScope

    private val serverNow: Long get() = clock() + clockOffsetMillis

    /** The enrollment the meter runs with, while running. */
    private var enrollment: Enrollment? = null

    fun start(scope: CoroutineScope, enrollment: Enrollment) {
        if (job?.isActive == true) return
        this.enrollment = enrollment
        this.scope = scope
        _state.value = _state.value.copy(deviceName = enrollment.deviceName, running = true)
        link.start(scope, enrollment.serverUrl, enrollment.deviceToken)
        location.start()
        job = scope.launch {
            launch { link.incoming.collect(::apply) }
            launch {
                link.status.collect { status ->
                    _state.value = _state.value.copy(link = status)
                    if (status == LinkStatus.Rejected) onRejected()
                }
            }
            launch { location.fix.collect { fix -> _state.value = _state.value.copy(gpsAccuracyM = fix?.accuracyM) } }
            launch { tickLoop() }
            launch { telemetryLoop() }
        }
    }

    /**
     * Enrollment changed while the meter runs (the phone re-enrolled from the MDM after a revoke):
     * restart with the new device token instead of the refused one.
     */
    fun follow(newEnrollment: Enrollment) {
        val running = job?.isActive == true
        if (!running || newEnrollment == enrollment) return
        val scope = this.scope
        stop()
        start(scope, newEnrollment)
    }

    fun stop() {
        enrollment = null
        beaconScanner.stop()
        job?.cancel()
        job = null
        link.stop()
        location.stop()
        annunciator.update(false)
        _state.value = DetectorState()
    }

    /** Acknowledge button: clear latched alarms that have gone, silence the rest until a new one. */
    fun acknowledge() = onScope {
        engine.acknowledge()
        _state.value = _state.value.copy(silenced = true)
        sendEvent("alarm.ack")
    }

    fun togglePeak() = onScope { _state.value = _state.value.copy(showPeak = !_state.value.showPeak) }

    fun dismissMessage() = onScope { _state.value = _state.value.copy(message = null) }

    /** Calibrate screen: sends the measured points; the answer arrives in [DetectorState.calibration]. False when offline. */
    fun calibrate(beaconId: String, points: List<com.taksolutions.simsensor.source.CalibrationPoint>): Boolean {
        onScope { _state.value = _state.value.copy(calibration = null) }
        return link.send(com.taksolutions.simsensor.protocol.CalibrateBeacon(beaconId, points))
    }

    /** Calibrate screen: the gas edge recorded at the doorway, or null to clear it. False when offline. */
    fun setBeaconEdge(beaconId: String, edgeRssi: Double?): Boolean {
        onScope { _state.value = _state.value.copy(calibration = null) }
        return link.send(com.taksolutions.simsensor.protocol.SetBeaconEdge(beaconId, edgeRssi))
    }

    /**
     * Calibrate screen open or closed. While open the phone counts as out of the gas (no leaks, no
     * overrides) so no alarm can latch and sound when the screen closes; the console is told why
     * the readings dropped.
     */
    fun setCalibrating(on: Boolean) = onScope {
        if (_state.value.calibrating == on) return@onScope
        _state.value = _state.value.copy(calibrating = on)
        sendEvent(if (on) "calibration.started" else "calibration.ended")
    }

    /** Before the app shuts down: lets the console show why the phone went offline. */
    fun announceShutdown() {
        link.send(DeviceEvent(ts = serverNow, kind = "app.shutdown"))
    }

    private fun onScope(block: () -> Unit) {
        if (::scope.isInitialized) scope.launch { block() }
    }

    private fun apply(message: ProtocolMessage) {
        when (message) {
            is Welcome -> {
                clockOffsetMillis = message.serverTimeMillis - clock()
                _state.value = _state.value.copy(deviceName = message.deviceName, units = message.units)
                applyExercise(message.exercise)
                applyOverrides(message.overrides, force = true)
            }
            is ExerciseUpdate -> applyExercise(message.exercise)
            is OverridesUpdate -> applyOverrides(message, force = false)
            is DeviceCommand -> applyCommand(message)
            is SettingsUpdate -> _state.value = _state.value.copy(units = message.units)
            is DeviceInfo -> _state.value = _state.value.copy(deviceName = message.deviceName)
            is com.taksolutions.simsensor.protocol.CalibrationResult -> _state.value = _state.value.copy(calibration = message)
            else -> Unit
        }
    }

    private fun applyExercise(snapshot: ExerciseSnapshot?) {
        val current = _state.value.exercise
        if (snapshot != null && current != null && snapshot.exerciseId == current.exerciseId && snapshot.version < current.version) return
        val wasFinished = _state.value.exerciseFinished && current?.exerciseId == snapshot?.exerciseId
        val finishing = snapshot != null && finished(snapshot) && !wasFinished
        engine = when {
            snapshot == null -> ReadingEngine(DefaultProfiles.FIVE_GAS)
            // Ended (operator 2026-10-16): straight to baseline, every alarm, latch, exposure and
            // peak cleared. A fresh engine starts at baseline, without the sensor lag.
            finishing -> ReadingEngine(snapshot.profile)
            current?.exerciseId == snapshot.exerciseId -> engine.also { it.updateProfile(snapshot.profile) }
            else -> ReadingEngine(snapshot.profile)
        }
        if (snapshot == null) overrides = emptyList()
        if (finishing) {
            lastAlarmCodes = emptySet()
            annunciator.update(false)
            _state.value = _state.value.copy(exercise = snapshot, silenced = false, showPeak = false)
        } else {
            _state.value = _state.value.copy(exercise = snapshot)
        }
    }

    private fun finished(snapshot: ExerciseSnapshot) = snapshot.state == "ended" || snapshot.state == "archived"

    private fun applyOverrides(update: OverridesUpdate, force: Boolean) {
        if (!force && update.version < overridesVersion) return
        overridesVersion = update.version
        overrides = update.overrides
    }

    private fun applyCommand(command: DeviceCommand) {
        when (command.kind) {
            CommandKind.SILENCE_ALARMS -> _state.value = _state.value.copy(silenced = true)
            CommandKind.SHOW_MESSAGE -> _state.value = _state.value.copy(message = command.text)
            CommandKind.SET_TELEMETRY_INTERVAL -> telemetryIntervalMillis = command.value?.let { (it * 1000).toLong() }
        }
    }

    private suspend fun tickLoop() {
        while (scope.isActive) {
            val now = serverNow
            val snapshot = _state.value.exercise
            val fix = gps.update(location.fix.value, clock())
            beaconScanner.update(snapshot?.beacons.orEmpty())
            // Always ranged: the Calibrate screen shows the live signal and the gas edge.
            val beaconDistances = beaconScanner.distances()
            val beaconPresence = beaconScanner.presence()
            val calibrating = _state.value.calibrating
            // Calibrating, or the exercise has ended: no leaks and no overrides, so nothing alarms.
            val paused = calibrating || _state.value.exerciseFinished
            val contributions = if (snapshot == null || paused) emptyMap() else SourceModel.contributions(
                sources = snapshot.sources,
                position = fix.position,
                elapsedS = snapshot.clock?.elapsedAt(now) ?: 0.0,
                emitting = snapshot.clock != null,
                exerciseWindFromDeg = snapshot.windDirDeg,
                o2Displacement = snapshot.o2Displacement,
                profile = engine.profile,
                beaconDistances = beaconDistances,
                beaconPresence = beaconPresence,
            )
            val heard = beaconScanner.heard()
            if (beaconScanner.status != _state.value.beaconScan || heard != _state.value.beaconsHeard) {
                _state.value = _state.value.copy(beaconScan = beaconScanner.status, beaconsHeard = heard)
            }
            val output = engine.step(now, if (snapshot != null && !paused) overrides else emptyList(), contributions)
            if (fix.status != _state.value.gpsStatus) _state.value = _state.value.copy(gpsStatus = fix.status)
            val conditions = output.activeCodes
            val newAlarms = (conditions - lastAlarmCodes).filter { it in output.alarmCodes }
            lastAlarmCodes = conditions
            var silenced = _state.value.silenced
            if (newAlarms.isNotEmpty()) {
                silenced = false // a new alarm always sounds, even after an acknowledge
                sendEvent("alarm.raised", detail = newAlarms.sorted().joinToString())
            }
            if (output.alarmCodes.isEmpty()) silenced = false
            _state.value = _state.value.copy(output = output, silenced = silenced)
            annunciator.update(_state.value.sounding)
            delay(TICK_MILLIS)
        }
    }

    private suspend fun telemetryLoop() {
        while (scope.isActive) {
            val s = _state.value
            val fix = location.fix.value
            queue.add(
                TelemetrySample(
                    ts = serverNow,
                    lat = fix?.lat,
                    lon = fix?.lon,
                    accuracyM = fix?.accuracyM,
                    headingDeg = fix?.headingDeg,
                    speedMps = fix?.speedMps,
                    battery = battery(),
                    readings = s.output.readings,
                    alarms = s.output.alarmCodes,
                    beacons = s.beaconsHeard,
                ),
            )
            flushQueue()
            delay(telemetryIntervalMillis ?: if (s.exercise != null) ACTIVE_INTERVAL_MILLIS else STANDBY_INTERVAL_MILLIS)
        }
    }

    /** Sends queued samples, oldest first, in batches, while the link is up. */
    private fun flushQueue() {
        while (link.status.value is LinkStatus.Connected) {
            val batch = queue.peek(BATCH)
            if (batch.isEmpty()) return
            if (!link.send(Telemetry(batch.map { it.second }))) return
            queue.removeThrough(batch.last().first)
            if (batch.size < BATCH) return
        }
    }

    private fun sendEvent(kind: String, detail: String? = null) {
        link.send(DeviceEvent(ts = serverNow, kind = kind, detail = detail))
    }

    private companion object {
        const val TICK_MILLIS = 250L
        const val ACTIVE_INTERVAL_MILLIS = 1_000L
        const val STANDBY_INTERVAL_MILLIS = 10_000L
        const val BATCH = 200
    }
}

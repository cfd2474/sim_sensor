package com.taksolutions.simsensor.beacons

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.taksolutions.simsensor.protocol.BeaconHeard
import com.taksolutions.simsensor.source.BeaconRanging
import com.taksolutions.simsensor.source.BeaconRef
import com.taksolutions.simsensor.source.IBeaconParser

/** Why beacon leaks may not work on this phone right now. */
enum class BeaconScanStatus { IDLE, SCANNING, NO_PERMISSION, BLUETOOTH_OFF, UNSUPPORTED, FAILED }

/**
 * Listens for the exercise's iBeacons (docs/ANDROID_PLATFORM_REFERENCE.md §3): one long
 * low-latency scan with a ScanFilter per beacon (filters keep scans running with the screen off),
 * restarted only when the exercise's beacon list changes, never more than 4 times in 30 s
 * (Android throttles 5+ starts). Signal strength is smoothed into distances by the shared
 * [BeaconRanging].
 *
 * All calls come from the runtime's single thread except [callback], which only records into
 * [ranging] under a lock.
 */
class BeaconScanner(private val context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val ranging = BeaconRanging()
    private val lock = Any()
    private var beacons: List<BeaconRef> = emptyList()
    private var byIdentity: Map<Triple<String, Int, Int>, BeaconRef> = emptyMap()
    private val recentStarts = ArrayDeque<Long>()
    private var scanning = false
    private var pendingRestart = false

    var status: BeaconScanStatus = BeaconScanStatus.IDLE
        private set

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = record(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::record)
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: $errorCode")
            scanning = false
            status = BeaconScanStatus.FAILED
        }
    }

    private fun record(result: ScanResult) {
        val data = result.scanRecord?.getManufacturerSpecificData(IBeaconParser.APPLE_COMPANY_ID) ?: return
        val frame = IBeaconParser.parse(data) ?: return
        val beacon = byIdentity[Triple(frame.uuid, frame.major, frame.minor)] ?: return
        synchronized(lock) { ranging.observe(beacon.id, result.rssi, frame.measuredPower, clock()) }
    }

    /** The exercise's beacons; empty stops scanning. Call every tick: it only acts on changes. */
    fun update(exerciseBeacons: List<BeaconRef>) {
        if (exerciseBeacons != beacons || pendingRestart) {
            beacons = exerciseBeacons
            byIdentity = exerciseBeacons.associateBy { Triple(it.uuid.lowercase(), it.major, it.minor) }
            synchronized(lock) { ranging.retain(exerciseBeacons.map { it.id }.toSet()) }
            restart()
        } else if (beacons.isNotEmpty() && !scanning) {
            restart() // e.g. permission granted or Bluetooth turned on since
        }
    }

    /** Estimated metres to each beacon heard recently. */
    fun distances(): Map<String, Double> = synchronized(lock) { ranging.distances(beacons, clock()) }

    private var lastPresence: Map<String, Double> = emptyMap()

    /** Gas-edge factor per beacon with an edge. Call once per tick (it moves the dead band). */
    fun presence(): Map<String, Double> = synchronized(lock) { ranging.presence(beacons, clock()).also { lastPresence = it } }

    /** What the console shows per phone. */
    fun heard(): List<BeaconHeard> = synchronized(lock) {
        val rssi = ranging.rssi(clock())
        ranging.distances(beacons, clock()).map { (id, d) -> BeaconHeard(id, rssi[id]?.toInt() ?: 0, d, lastPresence[id]) }
    }

    fun stop() {
        beacons = emptyList()
        stopScan()
        status = BeaconScanStatus.IDLE
    }

    private fun restart() {
        stopScan()
        if (beacons.isEmpty()) {
            status = BeaconScanStatus.IDLE
            return
        }
        val now = clock()
        while (recentStarts.isNotEmpty() && now - recentStarts.first() > THROTTLE_WINDOW_MS) recentStarts.removeFirst()
        if (recentStarts.size >= MAX_STARTS) {
            pendingRestart = true // try again on a later tick
            return
        }
        pendingRestart = false
        startScan()
        if (scanning) recentStarts.addLast(now) // only real starts count toward Android's limit
    }

    @SuppressLint("MissingPermission") // checked by hasPermission()
    private fun startScan() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            status = BeaconScanStatus.UNSUPPORTED
            return
        }
        if (!hasPermission()) {
            status = BeaconScanStatus.NO_PERMISSION
            return
        }
        if (!adapter.isEnabled) {
            status = BeaconScanStatus.BLUETOOTH_OFF
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run { status = BeaconScanStatus.BLUETOOTH_OFF; return }
        val filters = beacons.map { b ->
            val (data, mask) = IBeaconParser.filterData(b)
            ScanFilter.Builder().setManufacturerData(IBeaconParser.APPLE_COMPANY_ID, data, mask).build()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        try {
            scanner.startScan(filters, settings, callback)
            scanning = true
            status = BeaconScanStatus.SCANNING
        } catch (e: SecurityException) {
            status = BeaconScanStatus.NO_PERMISSION
        } catch (e: IllegalStateException) {
            status = BeaconScanStatus.BLUETOOTH_OFF
        }
    }

    @SuppressLint("MissingPermission") // stopping needs no extra permission beyond the scan's
    private fun stopScan() {
        if (!scanning) return
        try {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
        scanning = false
    }

    fun hasPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            granted(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "BeaconScanner"
        const val THROTTLE_WINDOW_MS = 30_000L
        const val MAX_STARTS = 4
    }
}

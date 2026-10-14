package com.taksolutions.simsensor.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GeoFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float?,
    val headingDeg: Float?,
    val speedMps: Float?,
    val timeMillis: Long,
)

/**
 * Position from the platform LocationManager. No Google Play services (docs/DESIGN.md §6.2):
 * the AOSP fused provider on Android 12+, otherwise GPS plus network.
 */
class LocationSource(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val _fix = MutableStateFlow<GeoFix?>(null)
    val fix: StateFlow<GeoFix?> = _fix.asStateFlow()

    private val listener = LocationListener { location -> _fix.value = location.toFix() }
    private var running = false

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked by hasPermission()
    fun start(intervalMillis: Long = 1_000) {
        if (running || manager == null || !hasPermission()) return
        providers().forEach { provider ->
            manager.requestLocationUpdates(provider, intervalMillis, 0f, listener, Looper.getMainLooper())
        }
        running = true
    }

    fun stop() {
        if (!running) return
        manager?.removeUpdates(listener)
        running = false
    }

    private fun providers(): List<String> {
        val enabled = manager?.getProviders(true).orEmpty()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && LocationManager.FUSED_PROVIDER in enabled) {
            return listOf(LocationManager.FUSED_PROVIDER)
        }
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { it in enabled }
    }

    private fun Location.toFix() = GeoFix(
        lat = latitude,
        lon = longitude,
        accuracyM = if (hasAccuracy()) accuracy else null,
        headingDeg = if (hasBearing()) bearing else null,
        speedMps = if (hasSpeed()) speed else null,
        timeMillis = time,
    )
}

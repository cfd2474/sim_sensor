package com.taksolutions.simsensor.ui.faces

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.taksolutions.simsensor.profile.SensorProfile
import com.taksolutions.simsensor.runtime.DetectorState

/** The two buttons every meter has, whatever they look like. */
data class MeterActions(
    val onAcknowledge: () -> Unit,
    val onTogglePeak: () -> Unit,
)

/**
 * One style of meter screen: draws the readings, alarm states and buttons. Readings, alarm
 * logic and sound stay in the shared runtime (DetectorRuntime, ReadingEngine); a face only
 * draws [DetectorState] and calls [MeterActions]. No manufacturer logos (PROJECT_STATE 2.8).
 */
interface MeterFace {
    @Composable
    fun Draw(state: DetectorState, actions: MeterActions, modifier: Modifier)
}

/** The face for a monitor's `screen` key; unknown keys (newer server) fall back to generic. */
fun meterFaceFor(screen: String?): MeterFace = when (screen) {
    SensorProfile.G8_SCREEN -> G8Face
    SensorProfile.XAM_SCREEN -> XamFace
    else -> GenericFace
}

package com.taksolutions.simsensor.ui.faces

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taksolutions.simsensor.engine.AlarmLevel
import com.taksolutions.simsensor.engine.ChannelReading
import com.taksolutions.simsensor.runtime.DetectorState
import com.taksolutions.simsensor.ui.DetectorColors

/** The original Sim Sensor meter: a dark tile per gas, flashing in alarm. */
object GenericFace : MeterFace {
    @Composable
    override fun Draw(state: DetectorState, actions: MeterActions, modifier: Modifier) {
        val blink = rememberBlink()
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.output.channels.forEach { Tile(it, showPeak = state.showPeak, lit = it.alarms.isNotEmpty() && blink) }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = actions.onAcknowledge,
                    colors = ButtonDefaults.buttonColors(containerColor = if (state.sounding) DetectorColors.bad else DetectorColors.tile),
                    modifier = Modifier.weight(1f).height(64.dp),
                ) { Text(if (state.output.inAlarm) "ACK / SILENCE" else "ACK", fontSize = 18.sp, color = Color.White) }
                Button(
                    onClick = actions.onTogglePeak,
                    colors = ButtonDefaults.buttonColors(containerColor = if (state.showPeak) DetectorColors.warn else DetectorColors.tile),
                    modifier = Modifier.weight(1f).height(64.dp),
                ) { Text(if (state.showPeak) "LIVE" else "PEAK", fontSize = 18.sp, color = Color.White) }
            }
        }
    }

    @Composable
    private fun Tile(reading: ChannelReading, showPeak: Boolean, lit: Boolean) {
        val alarmColor = if (reading.severity == Severity.HIGH) DetectorColors.bad else DetectorColors.warn
        val flashing = reading.alarms.isNotEmpty()
        val c = reading.channel
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (lit) alarmColor else DetectorColors.tile, RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(72.dp)) {
                Text(c.label, color = if (flashing) Color.White else DetectorColors.label, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (showPeak) Text("PEAK", color = DetectorColors.warn, fontSize = 11.sp)
            }
            Text(
                reading.alarms.filter { it != AlarmLevel.FAULT && it != AlarmLevel.OVER_RANGE }.joinToString(" ") { it.label },
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(reading.valueText(showPeak), color = if (lit) Color.White else DetectorColors.reading, fontSize = 40.sp, fontFamily = FontFamily.Monospace)
            Text(" ${c.unit}", color = if (flashing) Color.White else DetectorColors.label, fontSize = 13.sp, modifier = Modifier.width(52.dp))
        }
    }
}

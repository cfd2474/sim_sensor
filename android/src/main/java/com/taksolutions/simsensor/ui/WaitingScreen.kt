package com.taksolutions.simsensor.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taksolutions.simsensor.R
import com.taksolutions.simsensor.connection.LinkStatus
import com.taksolutions.simsensor.runtime.DetectorState

/** Brand colours of the Play feature graphic (tools/make-play-graphics.py). */
private object Brand {
    val navy = Color(0xFF021A49)
    val navyDeep = Color(0xFF01102E)
    val cyan = Color(0xFF1EF6DD)
    val muted = Color(0xFFB2C4DE)
}

/**
 * Shown instead of the meter while the phone has no exercise (operator 2026-10-16), in the style
 * of the Play feature graphic: navy gradient, faint signal arcs, the app icon and tagline.
 * [message] shows an instructor message, if any; [onSettings] opens Settings (PIN rule applies).
 */
@Composable
fun WaitingScreen(state: DetectorState, onSettings: () -> Unit, message: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Brand.navy, Brand.navyDeep))),
    ) {
        SignalArcs(Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SimulatorBanner()
            ConnectionRow(state, onSettings)
            message()
            Spacer(Modifier.weight(1f))
            Image(
                painter = painterResource(R.drawable.brand_icon),
                contentDescription = null,
                modifier = Modifier.size(120.dp).clip(RoundedCornerShape(28.dp)),
            )
            Spacer(Modifier.height(12.dp))
            Text("Sim Sensor", color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Bold)
            Text("Gas detector simulator\nfor hazmat training", color = Brand.cyan, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp)
            Spacer(Modifier.height(40.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDot()
                Text("  Waiting for exercise assignment", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "The instructor adds this phone to an exercise on the console. The meter starts on its own.",
                color = Brand.muted,
                fontSize = 16.sp,
            )
            Spacer(Modifier.weight(1.4f))
            SimulatorBanner()
        }
    }
}

@Composable
private fun ConnectionRow(state: DetectorState, onSettings: () -> Unit) {
    val (color, text) = when (state.link) {
        is LinkStatus.Connected -> DetectorColors.ok to "Online"
        LinkStatus.Connecting -> DetectorColors.warn to "Connecting"
        is LinkStatus.Retrying -> DetectorColors.warn to "Offline"
        LinkStatus.Rejected -> DetectorColors.bad to "Removed"
        LinkStatus.Disconnected -> Brand.muted to "Off"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text("  $text", color = Color.White, fontSize = 14.sp)
        Text("   ${state.deviceName.orEmpty()}", color = Brand.muted, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
        TextButton(onClick = onSettings) { Text("⚙", fontSize = 20.sp, color = Brand.muted) }
    }
}

/** The feature graphic's three faint arcs, centred off the top-right corner. */
@Composable
private fun SignalArcs(modifier: Modifier) {
    Canvas(modifier) {
        val center = Offset(size.width * 0.85f, -size.height * 0.04f)
        val stroke = Stroke(width = 9.dp.toPx())
        for (fraction in listOf(1.05f, 0.85f, 0.65f)) {
            val r = size.width * fraction
            drawArc(
                color = Brand.cyan.copy(alpha = 0.15f),
                startAngle = 20f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = Size(r * 2, r * 2),
                style = stroke,
            )
        }
    }
}

@Composable
private fun PulsingDot() {
    val pulse by rememberInfiniteTransition(label = "waiting").animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "dot",
    )
    Box(Modifier.size(14.dp).alpha(pulse).background(Brand.cyan, CircleShape))
}

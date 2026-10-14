package com.taksolutions.simsensor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Required on every screen: this phone is never a real detector (docs/DESIGN.md §1). */
@Composable
fun SimulatorBanner() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(DetectorColors.watermark, RoundedCornerShape(4.dp))
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("SIMULATOR — TRAINING ONLY", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

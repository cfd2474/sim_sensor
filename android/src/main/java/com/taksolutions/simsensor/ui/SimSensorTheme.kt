package com.taksolutions.simsensor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Dark, high-contrast palette in the style of a meter LCD. */
object DetectorColors {
    val background = Color(0xFF0B0F0C)
    val tile = Color(0xFF16201A)
    val reading = Color(0xFFB8F5C8)
    val label = Color(0xFF7FA58A)
    val watermark = Color(0xFFFFC107)
    val ok = Color(0xFF4CAF50)
    val warn = Color(0xFFFFC107)
    val bad = Color(0xFFF44336)
}

@Composable
fun SimSensorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = DetectorColors.background,
            surface = DetectorColors.tile,
            primary = DetectorColors.reading,
        ),
        content = content,
    )
}

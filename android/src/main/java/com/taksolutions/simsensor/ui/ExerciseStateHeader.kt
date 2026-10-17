package com.taksolutions.simsensor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** How the phone presents an exercise state (`ExerciseSnapshot.state`). */
data class ExerciseStateLook(val title: String, val detail: String, val color: Color)

/**
 * The exercise's state as the trainee sees it (operator, 2026-10-17): not started, active,
 * paused, ended. Null for a state the app doesn't know (a newer server): nothing is shown.
 */
fun exerciseStateLook(state: String?): ExerciseStateLook? = when (state) {
    "draft" -> ExerciseStateLook("EXERCISE NOT STARTED", "readings at baseline until it starts", DetectorColors.warn)
    "running" -> ExerciseStateLook("EXERCISE ACTIVE", "readings follow the scenario", DetectorColors.ok)
    "paused" -> ExerciseStateLook("EXERCISE PAUSED", "leaks held as they are", DetectorColors.warn)
    "ended", "archived" -> ExerciseStateLook("EXERCISE ENDED", "readings at baseline", DetectorColors.label)
    else -> null
}

/** One line above the meter: a coloured dot, the state and what it means for the readings. */
@Composable
fun ExerciseStateHeader(state: String?) {
    val look = exerciseStateLook(state) ?: return
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.5.dp, look.color, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(look.color, CircleShape))
        // One text, so a narrow phone wraps the explanation instead of cutting it off.
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = look.color, fontSize = 16.sp, fontWeight = FontWeight.Bold)) { append("  ${look.title}") }
                withStyle(SpanStyle(color = DetectorColors.label, fontSize = 14.sp)) { append(" — ${look.detail}") }
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

package com.taksolutions.simsensor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taksolutions.simsensor.update.AppUpdate
import com.taksolutions.simsensor.update.UpdateAction

private val updateBlue = Color(0xFF4FC3F7)

/**
 * The connected server offers a newer app (operator, 2026-10-17). A card, never a dialog, so it
 * can't cover an alarm. **Update** opens the download (or Play for a Play copy); MDM-managed and
 * kiosk phones are told their administrator installs it. **Later** hides it for this version.
 */
@Composable
fun AppUpdateCard(update: AppUpdate, onUpdate: (UpdateAction) -> Unit, onLater: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF0E2233), RoundedCornerShape(8.dp))
            .border(1.5.dp, updateBlue, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            "APP UPDATE: ${update.offered.version} is available",
            color = updateBlue, fontSize = 16.sp, fontWeight = FontWeight.Bold,
        )
        Text(
            "This phone has ${update.installedVersion}." +
                if (update.action == UpdateAction.AskAdministrator) " Your administrator installs updates on this phone." else "",
            color = DetectorColors.reading, fontSize = 14.sp,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (update.action != UpdateAction.AskAdministrator) {
                Button(
                    onClick = { onUpdate(update.action) },
                    colors = ButtonDefaults.buttonColors(containerColor = updateBlue, contentColor = Color.Black),
                ) { Text("Update", fontWeight = FontWeight.Bold) }
            }
            TextButton(onClick = onLater) { Text("Later", color = DetectorColors.label) }
        }
    }
}

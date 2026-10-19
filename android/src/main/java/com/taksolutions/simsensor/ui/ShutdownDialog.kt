package com.taksolutions.simsensor.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * "Shut down Sim Sensor?": from Settings, and from Back on the main screen (operator, 2026-10-18).
 * Shutting down stops the meter and its foreground service and closes the app (platform
 * reference §1, App shutdown).
 */
@Composable
fun ShutdownDialog(onShutdown: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Shut down Sim Sensor?") },
        text = {
            Text(
                "The meter stops: no readings, GPS, Bluetooth or connection until the app is opened again. " +
                    "The instructor sees that this phone shut down.",
            )
        },
        confirmButton = { TextButton(onClick = onShutdown) { Text("Shut down") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

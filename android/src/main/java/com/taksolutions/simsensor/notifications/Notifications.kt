package com.taksolutions.simsensor.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.taksolutions.simsensor.MainActivity
import com.taksolutions.simsensor.R
import com.taksolutions.simsensor.connection.LinkStatus
import com.taksolutions.simsensor.runtime.DetectorState
import com.taksolutions.simsensor.ui.formatReading

/** Notification channels and builders. */
object Notifications {
    const val STATUS_ID = 1
    const val ALARM_ID = 2
    private const val STATUS_CHANNEL = "status"
    private const val ALARM_CHANNEL = "alarms"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(STATUS_CHANNEL, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(ALARM_CHANNEL, context.getString(R.string.channel_alarms), NotificationManager.IMPORTANCE_HIGH).apply {
                // The app plays its own alarm tone; the notification itself stays silent.
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    /** The always-on notification of the foreground service. */
    fun status(context: Context, state: DetectorState): Notification =
        NotificationCompat.Builder(context, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SIMULATOR — " + (state.exercise?.name ?: "Standby"))
            .setContentText(linkText(state.link))
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    /** Heads-up alarm, shown while the meter alarms and the app isn't on screen. */
    fun alarm(context: Context, state: DetectorState): Notification {
        val text = state.output.channels
            .filter { it.alarms.isNotEmpty() }
            .joinToString { c -> "${c.channel.label} ${c.alarms.joinToString("/") { it.label }}" + (c.value?.let { " ${formatReading(it, c.channel.resolution)} ${c.channel.unit}" } ?: "") }
        return NotificationCompat.Builder(context, ALARM_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SIMULATOR ALARM")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
    }

    private fun linkText(link: LinkStatus): String = when (link) {
        is LinkStatus.Connected -> "Connected"
        LinkStatus.Connecting -> "Connecting…"
        is LinkStatus.Retrying -> "Offline — retrying"
        LinkStatus.Rejected -> "Removed by admin"
        LinkStatus.Disconnected -> "Not connected"
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

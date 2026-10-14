package com.taksolutions.simsensor.alarm

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Makes the meter's alarm noise. */
interface AlarmAnnunciator {
    /** Sound and vibrate while [sounding] is true. */
    fun update(sounding: Boolean)
    fun release()
}

/**
 * Loud beeping on the alarm stream plus vibration, in the rhythm of a multi-gas meter.
 * Runs from the foreground service, so it keeps going when the app isn't on screen.
 */
class ToneAlarmAnnunciator(context: Context, private val scope: CoroutineScope) : AlarmAnnunciator {
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull()
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    private var job: Job? = null

    override fun update(sounding: Boolean) {
        if (sounding && job?.isActive != true) {
            job = scope.launch {
                while (isActive) {
                    tone?.startTone(ToneGenerator.TONE_CDMA_HIGH_L, BEEP_MILLIS)
                    vibrator?.vibrate(VibrationEffect.createOneShot(BEEP_MILLIS.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
                    delay(PERIOD_MILLIS)
                }
            }
        } else if (!sounding) {
            job?.cancel()
            job = null
            tone?.stopTone()
            vibrator?.cancel()
        }
    }

    override fun release() {
        update(false)
        tone?.release()
    }

    private companion object {
        const val BEEP_MILLIS = 250
        const val PERIOD_MILLIS = 500L
    }
}

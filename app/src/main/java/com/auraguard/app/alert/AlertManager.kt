package com.auraguard.app.alert

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.auraguard.app.core.AlertLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "Event/Alert Engine" — human-facing side. Produces the large on-screen
 * warning banner plus an audible + haptic cue whose urgency scales with
 * [AlertLevel]. Everything here is informational: AURA Guard never issues
 * any command to the drone or any other system as a result of an alert.
 */
class AlertManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var toneGenerator: ToneGenerator? = null
    private var bannerAutoClearJob: Job? = null

    private val _banner = MutableStateFlow<AlertBannerData?>(null)
    val banner: StateFlow<AlertBannerData?> = _banner.asStateFlow()

    private val _highestActiveLevel = MutableStateFlow(AlertLevel.INFORMATION)
    val highestActiveLevel: StateFlow<AlertLevel> = _highestActiveLevel.asStateFlow()

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 95)
        } catch (t: Throwable) {
            toneGenerator = null // No audio output available on this device/emulator — degrade silently.
        }
    }

    fun raise(level: AlertLevel, title: String, subtitle: String, zoneName: String?, audibleEnabled: Boolean) {
        _highestActiveLevel.value = level
        _banner.value = AlertBannerData(level, title, subtitle, zoneName, System.currentTimeMillis())
        if (audibleEnabled) playTone(level)
        vibrate(level)

        bannerAutoClearJob?.cancel()
        bannerAutoClearJob = scope.launch {
            delay(if (level == AlertLevel.CRITICAL) 6000 else 3500)
            _banner.value = null
        }
    }

    fun dismissBanner() {
        bannerAutoClearJob?.cancel()
        _banner.value = null
    }

    fun resetLevel() {
        _highestActiveLevel.value = AlertLevel.INFORMATION
    }

    private fun playTone(level: AlertLevel) {
        val tg = toneGenerator ?: return
        try {
            when (level) {
                AlertLevel.CRITICAL -> tg.startTone(ToneGenerator.TONE_SUP_ERROR, 900)
                AlertLevel.WARNING -> tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 350)
                AlertLevel.INFORMATION -> tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            }
        } catch (t: Throwable) {
            // Ignore — audible alert is a nice-to-have, never worth crashing the monitoring loop.
        }
    }

    private fun vibrate(level: AlertLevel) {
        val vibrator: Vibrator? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (t: Throwable) {
            null
        } ?: return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val pattern = when (level) {
            AlertLevel.CRITICAL -> longArrayOf(0, 250, 120, 250, 120, 250)
            AlertLevel.WARNING -> longArrayOf(0, 180)
            AlertLevel.INFORMATION -> longArrayOf(0, 60)
        }
        try {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (t: Throwable) {
            // Best-effort.
        }
    }

    fun release() {
        toneGenerator?.release()
        toneGenerator = null
    }
}

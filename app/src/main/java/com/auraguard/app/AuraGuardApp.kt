package com.auraguard.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.auraguard.app.core.AuraViewModel
import com.auraguard.app.core.SettingsRepository

class AuraGuardApp : Application() {

    lateinit var settingsRepository: SettingsRepository
        private set

    /**
     * The single pipeline instance (capture -> detector -> tracker ->
     * perimeter/change engines -> alerts -> event log), owned by the
     * Application rather than any one Activity/Service. MainActivity's
     * Compose UI and the floating overlay windows drawn by
     * ScreenCaptureService both read and drive this SAME instance, so
     * zones, tracked objects, and alerts always agree between them
     * instead of each maintaining an independent, diverging pipeline.
     */
    lateinit var auraViewModel: AuraViewModel
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        auraViewModel = AuraViewModel(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.capture_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing perimeter monitoring status"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "aura_guard_monitoring"
    }
}

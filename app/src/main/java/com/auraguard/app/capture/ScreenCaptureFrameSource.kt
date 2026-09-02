package com.auraguard.app.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Real input source: the phone's own screen, captured via MediaProjection
 * while the drone manufacturer's app is in the foreground. The actual
 * system permission dialog must be triggered from an Activity
 * (see LiveScreen's rememberLauncherForActivityResult); this class starts
 * the capture foreground service once that permission has been granted.
 */
class ScreenCaptureFrameSource(private val appContext: Context) : FrameSource {

    override val state: StateFlow<CaptureState> = CaptureBus.state
    override val frames: SharedFlow<Bitmap> = CaptureBus.frames
    override val sourceFps: StateFlow<Float> = CaptureBus.sourceFps

    /** UI layer should call this to know it must launch the system capture-permission dialog. */
    override fun start() {
        CaptureBus.setState(CaptureState.REQUESTING_PERMISSION)
    }

    /** Called after the ActivityResult from MediaProjectionManager.createScreenCaptureIntent() returns OK. */
    fun startWithPermission(resultCode: Int, resultData: Intent) {
        val intent = ScreenCaptureService.startIntent(appContext, resultCode, resultData)
        ContextCompat.startForegroundService(appContext, intent)
    }

    fun onPermissionDenied() {
        CaptureBus.setState(CaptureState.PERMISSION_DENIED)
    }

    override fun stop() {
        appContext.startService(ScreenCaptureService.stopIntent(appContext))
        CaptureBus.reset()
    }
}

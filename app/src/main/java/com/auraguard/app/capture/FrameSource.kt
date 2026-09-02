package com.auraguard.app.capture

import android.graphics.Bitmap
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class CaptureState {
    IDLE,
    REQUESTING_PERMISSION,
    ACTIVE,
    PERMISSION_DENIED,
    STOPPED_BY_USER,
    ERROR
}

/**
 * Common contract for anything that can feed frames into the AURA Guard
 * pipeline — either the real MediaProjection screen capture of the drone
 * app, or a looped demo video used for development/testing without a
 * physical drone. The AI Detector and everything downstream is identical
 * for both, per the spec's "same AI pipeline" requirement.
 */
interface FrameSource {
    val state: StateFlow<CaptureState>
    val frames: SharedFlow<Bitmap>
    val sourceFps: StateFlow<Float>

    fun start()
    fun stop()
}

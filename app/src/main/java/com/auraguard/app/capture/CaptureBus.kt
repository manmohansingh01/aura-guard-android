package com.auraguard.app.capture

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process bridge between [ScreenCaptureService] (which owns the
 * MediaProjection VirtualDisplay and runs in a foreground service) and the
 * rest of the app. The service and the UI/ViewModel run in the same
 * process, so a lightweight singleton bus avoids AIDL/Messenger overhead
 * while still keeping capture concerns out of the Activity.
 */
object CaptureBus {
    private val _frames = MutableSharedFlow<Bitmap>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val frames = _frames.asSharedFlow()

    private val _state = MutableStateFlow(CaptureState.IDLE)
    val state = _state.asStateFlow()

    private val _sourceFps = MutableStateFlow(0f)
    val sourceFps = _sourceFps.asStateFlow()

    fun setState(newState: CaptureState) {
        _state.value = newState
    }

    fun setFps(fps: Float) {
        _sourceFps.value = fps
    }

    fun tryEmitFrame(bitmap: Bitmap): Boolean = _frames.tryEmit(bitmap)

    fun reset() {
        _state.value = CaptureState.IDLE
        _sourceFps.value = 0f
    }
}

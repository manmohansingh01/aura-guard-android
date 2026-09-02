package com.auraguard.app.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import com.auraguard.app.core.InputSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Top of the pipeline: "Screen Capture" stage. Owns both real input
 * sources (live MediaProjection capture, and looped demo video) behind one
 * switchable API so everything downstream — frame processor, detector,
 * tracker, perimeter/change engines — never needs to know which is active.
 *
 * [frames], [state] and [fps] are all *reactive* to [activeSource] — they
 * re-subscribe to whichever underlying source is currently selected (via
 * flatMapLatest) rather than being resolved once, so switching between
 * live capture and demo video mid-session actually rewires the pipeline
 * instead of silently freezing it on the old source.
 */
class CaptureManager(private val appContext: Context) {

    val screenSource = ScreenCaptureFrameSource(appContext)
    val demoSource = DemoVideoFrameSource(appContext)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _activeSource = MutableStateFlow(InputSource.NONE)
    val activeSource: StateFlow<InputSource> = _activeSource.asStateFlow()

    private val _state = MutableStateFlow(CaptureState.IDLE)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private val _fps = MutableStateFlow(0f)
    val fps: StateFlow<Float> = _fps.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val frames: Flow<Bitmap> = _activeSource.flatMapLatest { source ->
        when (source) {
            InputSource.SCREEN_CAPTURE -> screenSource.frames
            InputSource.DEMO_VIDEO -> demoSource.frames
            InputSource.NONE -> emptyFlow()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    init {
        _activeSource.flatMapLatest { source ->
            when (source) {
                InputSource.SCREEN_CAPTURE -> screenSource.state
                InputSource.DEMO_VIDEO -> demoSource.state
                InputSource.NONE -> flowOf(CaptureState.IDLE)
            }
        }.onEach { _state.value = it }.launchIn(scope)

        _activeSource.flatMapLatest { source ->
            when (source) {
                InputSource.SCREEN_CAPTURE -> screenSource.sourceFps
                InputSource.DEMO_VIDEO -> demoSource.sourceFps
                InputSource.NONE -> flowOf(0f)
            }
        }.onEach { _fps.value = it }.launchIn(scope)
    }

    fun requestScreenCapture() {
        _activeSource.value = InputSource.SCREEN_CAPTURE
        screenSource.start()
    }

    fun onScreenCapturePermission(resultCode: Int, data: Intent?) {
        if (data == null) {
            screenSource.onPermissionDenied()
            return
        }
        screenSource.startWithPermission(resultCode, data)
    }

    fun onScreenCapturePermissionDenied() {
        screenSource.onPermissionDenied()
    }

    fun startDemo(videoUri: Uri) {
        demoSource.setVideo(videoUri)
        _activeSource.value = InputSource.DEMO_VIDEO
        demoSource.start()
    }

    fun stopAll() {
        screenSource.stop()
        demoSource.stop()
        _activeSource.value = InputSource.NONE
    }
}

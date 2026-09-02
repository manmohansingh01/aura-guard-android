package com.auraguard.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * DEMO MODE input source. Feeds frames from a prerecorded video already on
 * the device through the exact same pipeline used for live screen capture,
 * so the full detection/tracking/perimeter/change/alert stack can be
 * exercised without a physical drone. The video loops continuously.
 */
class DemoVideoFrameSource(private val appContext: Context) : FrameSource {

    private val _state = MutableStateFlow(CaptureState.IDLE)
    override val state: StateFlow<CaptureState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<Bitmap>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val frames: SharedFlow<Bitmap> = _frames.asSharedFlow()

    private val _sourceFps = MutableStateFlow(0f)
    override val sourceFps: StateFlow<Float> = _sourceFps.asStateFlow()

    private var videoUri: Uri? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Target sample interval; kept modest since MediaMetadataRetriever seeks are relatively slow. */
    var frameIntervalMs: Long = 200L

    fun setVideo(uri: Uri) {
        videoUri = uri
    }

    override fun start() {
        val uri = videoUri
        if (uri == null) {
            _state.value = CaptureState.ERROR
            return
        }
        job?.cancel()
        job = scope.launch {
            _state.value = CaptureState.ACTIVE
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(appContext, uri)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 10_000L

                var positionMs = 0L
                var frameCounter = 0
                var windowStart = System.currentTimeMillis()

                while (isActive) {
                    val bitmap = retriever.getFrameAtTime(
                        positionMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
                    if (bitmap != null) {
                        _frames.tryEmit(scaleDown(bitmap))
                        frameCounter++
                        val now = System.currentTimeMillis()
                        val elapsed = now - windowStart
                        if (elapsed >= 1000) {
                            _sourceFps.value = frameCounter * 1000f / elapsed
                            frameCounter = 0
                            windowStart = now
                        }
                    }
                    positionMs += frameIntervalMs
                    if (positionMs >= durationMs) positionMs = 0L // loop demo video
                    delay(frameIntervalMs)
                }
            } catch (t: Throwable) {
                _state.value = CaptureState.ERROR
            } finally {
                try { retriever.release() } catch (_: Throwable) { }
            }
        }
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val maxDim = 1280
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxDim) return bitmap
        val scale = maxDim.toFloat() / largest
        val scaled = Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true
        )
        if (scaled != bitmap) bitmap.recycle()
        return scaled
    }

    override fun stop() {
        job?.cancel()
        job = null
        _state.value = CaptureState.IDLE
        _sourceFps.value = 0f
    }
}

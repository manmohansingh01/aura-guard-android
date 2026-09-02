package com.auraguard.app.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.auraguard.app.AuraGuardApp
import com.auraguard.app.MainActivity
import com.auraguard.app.R
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground service that owns the MediaProjection VirtualDisplay used to
 * capture whatever is on screen — i.e. the drone manufacturer's own app,
 * unmodified. Frames are decoded from the ImageReader and published on
 * [CaptureBus] for the rest of the pipeline to consume.
 *
 * This service never reads input from, or sends commands to, the drone.
 * It only observes pixels already being rendered to the phone's screen.
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private val frameCount = AtomicInteger(0)
    private val fpsWindowStart = AtomicLong(0)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            CaptureBus.setState(CaptureState.STOPPED_BY_USER)
            stopCapture()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            CaptureBus.setState(CaptureState.PERMISSION_DENIED)
            stopSelf()
            return
        }

        // Foreground notification must be up BEFORE we obtain the MediaProjection
        // (required starting Android 10, enforced strictly from Android 14 on).
        startForeground(NOTIFICATION_ID, buildNotification())

        try {
            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
            mediaProjection = projection
            projection.registerCallback(projectionCallback, null)
            startVirtualDisplay(projection)
            CaptureBus.setState(CaptureState.ACTIVE)
        } catch (t: Throwable) {
            CaptureBus.setState(CaptureState.ERROR)
            stopCapture()
            stopSelf()
        }
    }

    private fun startVirtualDisplay(projection: MediaProjection) {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        // Cap the analyzed resolution for performance; the polygon/box overlays are
        // normalized coordinates so they remain correct at any capture resolution.
        val scale = (MAX_CAPTURE_DIMENSION.toFloat() / maxOf(metrics.widthPixels, metrics.heightPixels))
            .coerceAtMost(1f)
        screenWidth = (metrics.widthPixels * scale).toInt().coerceAtLeast(2)
        screenHeight = (metrics.heightPixels * scale).toInt().coerceAtLeast(2)
        screenDensity = metrics.densityDpi

        handlerThread = HandlerThread("AuraCaptureThread").also { it.start() }
        handler = Handler(handlerThread!!.looper)

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ reader ->
                val image = try {
                    reader.acquireLatestImage()
                } catch (t: Throwable) {
                    null
                } ?: return@setOnImageAvailableListener

                try {
                    val bitmap = imageToBitmap(image)
                    if (bitmap != null) {
                        CaptureBus.tryEmitFrame(bitmap)
                        trackFps()
                    }
                } finally {
                    image.close()
                }
            }, handler)
        }

        virtualDisplay = projection.createVirtualDisplay(
            "AuraGuardCapture",
            screenWidth, screenHeight, screenDensity,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler
        )
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            if (bitmap.width != screenWidth) {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                bitmap.recycle()
                cropped
            } else {
                bitmap
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun trackFps() {
        val now = System.currentTimeMillis()
        val windowStart = fpsWindowStart.get()
        if (windowStart == 0L) {
            fpsWindowStart.set(now)
            frameCount.set(1)
            return
        }
        val count = frameCount.incrementAndGet()
        val elapsed = now - windowStart
        if (elapsed >= 1000) {
            CaptureBus.setFps(count * 1000f / elapsed)
            fpsWindowStart.set(now)
            frameCount.set(0)
        }
    }

    private fun stopCapture() {
        try {
            virtualDisplay?.release()
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Throwable) {
            // Best-effort teardown.
        } finally {
            virtualDisplay = null
            imageReader = null
            mediaProjection = null
            handlerThread?.quitSafely()
            handlerThread = null
            handler = null
        }
    }

    override fun onDestroy() {
        stopCapture()
        if (CaptureBus.state.value == CaptureState.ACTIVE) {
            CaptureBus.setState(CaptureState.IDLE)
        }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, AuraGuardApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()
    }

    companion object {
        const val ACTION_START = "com.auraguard.app.action.START_CAPTURE"
        const val ACTION_STOP = "com.auraguard.app.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val NOTIFICATION_ID = 4201
        private const val MAX_CAPTURE_DIMENSION = 1280

        fun startIntent(context: Context, resultCode: Int, resultData: Intent): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
    }
}

package com.auraguard.app.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.auraguard.app.MainActivity
import com.auraguard.app.core.AuraViewModel
import com.auraguard.app.ui.theme.AuraGuardTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Owns the two floating windows AURA Guard draws directly on top of
 * whatever app is currently on screen (the drone controller app), so the
 * operator sees detections, perimeter zones, and alerts — and can define
 * a perimeter — without ever switching away from it. Requires the "draw
 * over other apps" (SYSTEM_ALERT_WINDOW) permission; the caller
 * (ScreenCaptureService) must confirm that's granted before calling
 * [start] — everything degrades gracefully to "no overlay, in-app Live
 * screen only" when it isn't.
 *
 * Two separate windows, not one, because they need opposite touch
 * behavior: [detectionView] is full-screen and must let touches fall
 * through to the app underneath except while actively defining a
 * perimeter (toggled reactively off [AuraViewModel.editState]), while
 * [bubbleView] is small and must always be touchable so the operator can
 * drag it and tap its controls.
 */
class OverlayController(
    private val appContext: Context,
    private val viewModel: AuraViewModel,
    private val onRequestStopCapture: () -> Unit
) {
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val lifecycleOwner = OverlayLifecycleOwner()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var detectionView: ComposeView? = null
    private var detectionParams: WindowManager.LayoutParams? = null
    private var bubbleView: ComposeView? = null
    private var running = false

    fun start() {
        if (running) return
        running = true
        lifecycleOwner.performRestore(null)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        addDetectionWindow()
        addBubbleWindow()

        // Keep the full-screen layer's touchability in sync with perimeter-edit
        // mode: touchable while defining/editing so taps reach PerimeterOverlay,
        // touch-through the rest of the time so the drone app stays usable.
        viewModel.editState
            .onEach { edit -> setDetectionWindowTouchable(edit.isActive) }
            .launchIn(scope)
    }

    fun stop() {
        if (!running) return
        running = false
        scope.cancel()

        detectionView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        detectionView = null
        detectionParams = null
        bubbleView = null

        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    private fun setDetectionWindowTouchable(touchable: Boolean) {
        val view = detectionView ?: return
        val params = detectionParams ?: return
        params.flags = baseFlags(touchable = touchable)
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun baseFlags(touchable: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return flags
    }

    private fun addDetectionWindow() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags(touchable = false),
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val view = ComposeView(appContext).also { attachOwners(it) }
        view.setContent {
            AuraGuardTheme { OverlayDetectionLayer(viewModel) }
        }

        if (runCatching { windowManager.addView(view, params) }.isSuccess) {
            detectionView = view
            detectionParams = params
        }
    }

    private fun addBubbleWindow() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 300
        }

        val view = ComposeView(appContext).also { attachOwners(it) }
        view.setContent {
            AuraGuardTheme {
                OverlayControlBubble(
                    viewModel = viewModel,
                    onDrag = { dx, dy ->
                        params.x += dx.toInt()
                        params.y += dy.toInt()
                        bubbleView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
                    },
                    onOpenApp = { openHostApp() },
                    onStopCapture = onRequestStopCapture
                )
            }
        }

        if (runCatching { windowManager.addView(view, params) }.isSuccess) {
            bubbleView = view
        }
    }

    private fun attachOwners(view: ComposeView) {
        view.setViewTreeLifecycleOwner(lifecycleOwner)
        view.setViewTreeViewModelStoreOwner(lifecycleOwner)
        view.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
    }

    private fun openHostApp() {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        appContext.startActivity(intent)
    }
}

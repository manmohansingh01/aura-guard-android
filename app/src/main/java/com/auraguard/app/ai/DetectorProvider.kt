package com.auraguard.app.ai

import android.content.Context

/**
 * Chooses the active [ObjectDetector] implementation. Real model present ->
 * TFLiteObjectDetector. No model file bundled -> falls back to
 * MotionDetector (real frame-difference motion tracking, not scripted) so
 * the rest of the app (tracking, perimeter breach logic, change detection,
 * alerts, event log, UI) remains fully demonstrable against real video
 * content, with the fallback always visible to the operator via
 * DetectorStatus.MOTION_CV / DetectorStatus.NO_MODEL in the status bar.
 *
 * This is the single place that would change to plug in ONNX Runtime or
 * ncnn instead of TensorFlow Lite.
 */
object DetectorProvider {
    /**
     * [roiScale] is how large the region this detector will actually run on is, relative to a
     * full frame (roughly sqrt(width fraction * height fraction) of the crop it'll be fed) —
     * 1f for the whole frame, smaller for a tightly cropped zone. It only affects the
     * MotionDetector fallback: that engine samples a fixed-size grid, so without this a small
     * zone crop gets sampled at the same 64-column resolution as the full frame, which is
     * effectively a digital zoom-in that makes fine background texture (dust, heat shimmer)
     * look like large, coherent blobs instead of the fine noise it actually is. Scaling the grid
     * down for smaller crops keeps a grid cell representing roughly the same real-world area
     * regardless of how tightly a zone is drawn.
     */
    fun create(context: Context, roiScale: Float = 1f, confidenceThresholdProvider: () -> Float): ObjectDetector {
        val tflite = TFLiteObjectDetector(
            context = context,
            confidenceThresholdProvider = confidenceThresholdProvider
        )
        return if (tflite.status.value == DetectorStatus.NO_MODEL) {
            tflite.close()
            val cols = (64 * roiScale.coerceIn(0.15f, 1f)).toInt().coerceIn(24, 64)
            MotionDetector(gridCols = cols)
        } else {
            tflite
        }
    }
}

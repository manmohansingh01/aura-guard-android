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
    fun create(context: Context, confidenceThresholdProvider: () -> Float): ObjectDetector {
        val tflite = TFLiteObjectDetector(
            context = context,
            confidenceThresholdProvider = confidenceThresholdProvider
        )
        return if (tflite.status.value == DetectorStatus.NO_MODEL) {
            tflite.close()
            MotionDetector()
        } else {
            tflite
        }
    }
}

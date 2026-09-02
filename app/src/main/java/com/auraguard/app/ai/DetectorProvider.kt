package com.auraguard.app.ai

import android.content.Context

/**
 * Chooses the active [ObjectDetector] implementation. Real model present ->
 * TFLiteObjectDetector. No model file bundled -> falls back to
 * SimulatedDetector so the rest of the app (tracking, perimeter breach
 * logic, change detection, alerts, event log, UI) remains fully
 * demonstrable, with the fallback always visible to the operator via
 * DetectorStatus.SIMULATED / DetectorStatus.NO_MODEL in the status bar.
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
            SimulatedDetector()
        } else {
            tflite
        }
    }
}

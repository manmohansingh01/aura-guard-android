package com.auraguard.app.ai

import android.graphics.Bitmap
import com.auraguard.app.core.Detection
import kotlinx.coroutines.flow.StateFlow

enum class DetectorStatus { LOADING, READY, NO_MODEL, ERROR, SIMULATED, MOTION_CV }

/**
 * "AI Detector" stage of the pipeline. Deliberately a narrow interface —
 * `detect(bitmap) -> List<Detection>` — so the underlying engine can be
 * swapped (TensorFlow Lite today; ONNX Runtime or NCNN later) without
 * touching the tracker, perimeter engine, or UI at all.
 */
interface ObjectDetector {
    val status: StateFlow<DetectorStatus>
    val modelInfo: String

    /** Runs one forward pass. Safe to call off the main thread only. */
    fun detect(bitmap: Bitmap): List<Detection>

    fun close()
}

package com.auraguard.app.ai

import android.graphics.Bitmap
import com.auraguard.app.core.Detection
import com.auraguard.app.core.NormRect
import com.auraguard.app.core.ObjectClass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sin
import kotlin.random.Random

/**
 * NOT a real detector. Used only when no .tflite model file has been placed
 * in assets/models/, so the rest of the pipeline (tracker, perimeter
 * engine, change detection, alerts, event log, UI) can still be built,
 * demoed and tested end-to-end per the spec's staged-milestone plan.
 *
 * The UI must always show INFERENCE STATUS: SIMULATED while this is
 * active — see DetectorStatus.SIMULATED — so nobody mistakes synthetic
 * boxes for real detections. Swap in TFLiteObjectDetector (or any other
 * ObjectDetector) the moment a real model is available.
 */
class SimulatedDetector : ObjectDetector {

    private val _status = MutableStateFlow(DetectorStatus.SIMULATED)
    override val status: StateFlow<DetectorStatus> = _status
    override val modelInfo: String = "SIMULATED (no .tflite model loaded)"

    private var t = 0f
    private val rng = Random(seed = 7)

    override fun detect(bitmap: Bitmap): List<Detection> {
        t += 0.06f
        val results = mutableListOf<Detection>()

        // One person walking a slow diagonal loop across the frame.
        val px = 0.15f + 0.55f * ((sin(t.toDouble()).toFloat() + 1f) / 2f)
        val py = 0.65f + 0.15f * ((sin((t * 0.7f).toDouble()).toFloat()))
        results += Detection(
            classId = 0, label = ObjectClass.PERSON.label, confidence = 0.80f + rng.nextFloat() * 0.15f,
            box = NormRect(px - 0.035f, py - 0.09f, px + 0.035f, py + 0.09f)
        )

        // A slower vehicle drifting near the top of frame.
        val vx = 0.1f + 0.7f * (((t * 0.15f) % 1f))
        results += Detection(
            classId = 2, label = ObjectClass.CAR.label, confidence = 0.7f + rng.nextFloat() * 0.2f,
            box = NormRect(vx, 0.12f, vx + 0.14f, 0.22f)
        )

        return results
    }

    override fun close() {}
}

package com.auraguard.app.ai

import android.content.Context
import android.graphics.Bitmap
import com.auraguard.app.core.Detection
import com.auraguard.app.core.NormRect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * "AI Detector" stage — real, on-device inference via TensorFlow Lite.
 *
 * This class targets the common Ultralytics YOLOv5 / YOLOv8 float32
 * `.tflite` export layout (single RGB image in, a single detection tensor
 * out), because that is the most widely available family of lightweight,
 * pretrained, freely redistributable detectors suitable for phones. No
 * training happens here or anywhere in this app — this only *runs* a
 * model someone else already trained.
 *
 * ── Where to put the model ───────────────────────────────────────────────
 * Place a compatible model file at:
 *     app/src/main/assets/models/model.tflite
 * See app/src/main/assets/models/README.md and MODEL_SETUP.md for exactly
 * where to obtain one and how to export it. If no file is present, [status]
 * becomes [DetectorStatus.NO_MODEL] and detect() returns an empty list —
 * the app keeps running normally on change-detection alone.
 *
 * ── Label set auto-detection ────────────────────────────────────────────
 * The model's output head is inspected at load time to pick the right
 * label/class mapping automatically, purely from how many classes the
 * exported model has — no app config needed either way:
 *   - 80 classes  -> [CocoLabels]      (stock/generic YOLO "coco" weights)
 *   - 10 classes  -> [VisDroneLabels]  (a model fine-tuned per
 *                     /colab/train_yolo26_visdrone.ipynb — recognizes
 *                     overhead/aerial drone-view people & vehicles)
 *   - anything else -> every class index is surfaced as a generic
 *                     [com.auraguard.app.core.ObjectClass.UNKNOWN] "OBJECT"
 *                     so a custom-trained model still produces detections
 *                     instead of being silently filtered to nothing.
 *
 * ── Replacing this detector ─────────────────────────────────────────────
 * Everything downstream only depends on the [ObjectDetector] interface.
 * Swapping to ONNX Runtime Mobile or ncnn means writing one new class that
 * implements [ObjectDetector]; no other file needs to change.
 */
class TFLiteObjectDetector(
    private val context: Context,
    private val modelAssetPath: String = "models/model.tflite",
    private val confidenceThresholdProvider: () -> Float = { 0.45f }
) : ObjectDetector {

    private val _status = MutableStateFlow(DetectorStatus.LOADING)
    override val status: StateFlow<DetectorStatus> = _status

    override var modelInfo: String = "Loading model..."
        private set

    private var interpreter: Interpreter? = null
    private var inputW = 640
    private var inputH = 640
    private var numClasses = 0
    private var numBoxes = 0
    private var transposedOutput = false // true: [1, 4+numClasses, numBoxes]; false: [1, numBoxes, 4+numClasses]

    // Chosen automatically in loadModel() once numClasses is known — see class doc above.
    private var activeLabelNames: List<String> = CocoLabels.NAMES
    private var activeRelevantClasses: Map<Int, com.auraguard.app.core.ObjectClass> = CocoLabels.RELEVANT_CLASSES
    private var activeLabelSetName: String = "COCO"

    private val nmsIouThreshold = 0.45f

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val buffer = FileUtil.loadMappedFile(context, modelAssetPath)
            val options = Interpreter.Options().apply { setNumThreads(4) }
            val interp = Interpreter(buffer, options)
            interpreter = interp

            val inShape = interp.getInputTensor(0).shape() // [1, H, W, 3]
            if (inShape.size == 4) {
                inputH = inShape[1]
                inputW = inShape[2]
            }

            val outShape = interp.getOutputTensor(0).shape()
            if (outShape.size == 3) {
                if (outShape[1] <= outShape[2]) {
                    // [1, 4+numClasses, numBoxes] — YOLOv8-style transposed head.
                    transposedOutput = true
                    numClasses = outShape[1] - 4
                    numBoxes = outShape[2]
                } else {
                    // [1, numBoxes, 5+numClasses] — YOLOv5-style head (includes objectness).
                    transposedOutput = false
                    numClasses = outShape[2] - 5
                    numBoxes = outShape[1]
                }
            }

            when (numClasses) {
                CocoLabels.NAMES.size -> {
                    activeLabelNames = CocoLabels.NAMES
                    activeRelevantClasses = CocoLabels.RELEVANT_CLASSES
                    activeLabelSetName = "COCO"
                }
                VisDroneLabels.NAMES.size -> {
                    activeLabelNames = VisDroneLabels.NAMES
                    activeRelevantClasses = VisDroneLabels.RELEVANT_CLASSES
                    activeLabelSetName = "VisDrone (aerial)"
                }
                else -> {
                    // Unrecognized class count — surface every class as a generic OBJECT rather
                    // than silently dropping every detection because no mapping matched.
                    activeLabelNames = (0 until numClasses).map { "class_$it" }
                    activeRelevantClasses = (0 until numClasses).associateWith { com.auraguard.app.core.ObjectClass.UNKNOWN }
                    activeLabelSetName = "custom"
                }
            }

            modelInfo = "TFLite ${inputW}x$inputH · $activeLabelSetName · $numClasses classes · $numBoxes anchors"
            _status.value = DetectorStatus.READY
        } catch (e: FileNotFoundException) {
            _status.value = DetectorStatus.NO_MODEL
            modelInfo = "No model at assets/$modelAssetPath — see MODEL_SETUP.md"
        } catch (t: Throwable) {
            _status.value = DetectorStatus.ERROR
            modelInfo = "Model load failed: ${t.message ?: t::class.simpleName}"
        }
    }

    override fun detect(bitmap: Bitmap): List<Detection> {
        val interp = interpreter ?: return emptyList()
        return try {
            val input = preprocess(bitmap)
            val hasObjectness = !transposedOutput
            val output = if (transposedOutput) {
                Array(1) { Array(4 + numClasses) { FloatArray(numBoxes) } }
            } else {
                Array(1) { Array(numBoxes) { FloatArray(5 + numClasses) } }
            }
            interp.run(input, output)
            decode(output, hasObjectness)
        } catch (t: Throwable) {
            _status.value = DetectorStatus.ERROR
            emptyList()
        }
    }

    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val scaled = Bitmap.createScaledBitmap(bitmap, inputW, inputH, true)
        val buffer = ByteBuffer.allocateDirect(4 * inputW * inputH * 3).order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputW * inputH)
        scaled.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)
        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255f) // R
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255f)  // G
            buffer.putFloat((pixel and 0xFF) / 255f)          // B
        }
        buffer.rewind()
        if (scaled !== bitmap) scaled.recycle()
        return buffer
    }

    /**
     * Decodes raw detector output into normalized [Detection]s, applying a
     * confidence threshold, class-relevance filtering, and per-class NMS.
     * Box coordinates are auto-detected as either already-normalized
     * [0,1] or in input-pixel space, since both conventions are common
     * across YOLO tflite exports.
     */
    private fun decode(output: Array<*>, hasObjectness: Boolean): List<Detection> {
        val threshold = confidenceThresholdProvider()
        val candidates = mutableListOf<Detection>()

        fun valueAt(box: Int, channel: Int): Float {
            @Suppress("UNCHECKED_CAST")
            return if (transposedOutput) {
                (output[0] as Array<FloatArray>)[channel][box]
            } else {
                (output[0] as Array<FloatArray>)[box][channel]
            }
        }

        var maxCoordSeen = 0f
        for (b in 0 until numBoxes) {
            val objectness = if (hasObjectness) valueAt(b, 4) else 1f
            var bestClass = -1
            var bestScore = 0f
            val classOffset = if (hasObjectness) 5 else 4
            for (c in 0 until numClasses) {
                val raw = valueAt(b, classOffset + c)
                val score = raw * objectness
                if (score > bestScore) {
                    bestScore = score
                    bestClass = c
                }
            }
            if (bestClass < 0 || bestScore < threshold) continue
            val mapped = activeRelevantClasses[bestClass] ?: continue

            val cx = valueAt(b, 0); val cy = valueAt(b, 1)
            val w = valueAt(b, 2); val h = valueAt(b, 3)
            maxCoordSeen = max(maxCoordSeen, max(cx, cy))

            candidates += Detection(
                classId = bestClass,
                label = mapped.label,
                confidence = min(bestScore, 1f),
                box = NormRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            )
        }

        // Heuristic: if coordinates clearly exceed [0,1], they were in input-pixel space.
        val normalized = if (maxCoordSeen > 1.5f) {
            candidates.map {
                it.copy(
                    box = NormRect(
                        it.box.left / inputW, it.box.top / inputH,
                        it.box.right / inputW, it.box.bottom / inputH
                    )
                )
            }
        } else candidates

        return nonMaxSuppression(normalized)
    }

    private fun nonMaxSuppression(detections: List<Detection>): List<Detection> {
        val result = mutableListOf<Detection>()
        for (classGroup in detections.groupBy { it.classId }.values) {
            val sorted = classGroup.sortedByDescending { it.confidence }.toMutableList()
            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                result += best
                sorted.removeAll { it.box.iou(best.box) > nmsIouThreshold }
            }
        }
        return result
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
    }
}

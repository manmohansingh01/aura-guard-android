package com.auraguard.app.ai

import android.graphics.Bitmap
import com.auraguard.app.core.Detection
import com.auraguard.app.core.NormRect
import com.auraguard.app.core.ObjectClass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/**
 * A real (not scripted) fallback detector used when no trained .tflite
 * model is bundled — see MODEL_SETUP.md; shipping pretrained weights was
 * deliberately left out of this repo.
 *
 * v1 of this detector diffed each frame against the *previous* frame (a
 * rolling reference). That had two real bugs reported from live footage:
 *
 *   1. A person or vehicle that stood still for more than a couple of
 *      frames got silently absorbed into the rolling reference — each
 *      frame nudged the reference 45% of the way toward the *current*
 *      frame, so a stationary object stopped producing any delta at all
 *      within well under a second, and a genuine "person is now standing
 *      inside the zone" stopped being reported as anything.
 *   2. Because it compared two individually noisy frames against each
 *      other (sensor noise + video-compression artifacts in *both*
 *      frames), the effective noise floor was higher than diffing against
 *      a stable reference — on a visually busy but empty background (sand,
 *      dust, heat shimmer) that noise regularly crossed the threshold and
 *      produced phantom "OBJECT" boxes where nothing was there.
 *
 * This version instead maintains a genuine background model: a reference
 * built by averaging the first few frames after the detector is (re)armed,
 * then adapted very slowly afterward — and, critically, *only* for cells
 * NOT currently flagged as foreground. That second part is what fixes bug
 * #1: a real object keeps differing from the background for as long as it
 * stays in view, because the pixels it covers are excluded from the
 * background update while it's there. It also improves on bug #2, since
 * a multi-frame-averaged reference carries much less noise than a single
 * previous raw frame, so the same threshold now rejects far more sensor/
 * compression noise without needing to be raised so high that it misses
 * real objects. A morphological neighbor filter (used the same way
 * `change/ChangeDetectionEngine.kt` filters its own grid) additionally
 * discards isolated flickering cells before they can ever reach the
 * minimum-cluster-size check.
 *
 * This is still not object *classification* — everything found is reported
 * as a generic "OBJECT" — which is why the UI keeps showing INFERENCE
 * STATUS: MOTION CV while this is active, so nobody mistakes a motion blob
 * for a model's classified detection. Swap in TFLiteObjectDetector (or any
 * other ObjectDetector) the moment a real trained model is available; nothing
 * downstream (tracker, perimeter engine, alerts, UI) needs to change either
 * way, since both implement the same narrow [ObjectDetector] interface.
 */
class MotionDetector(
    private val gridCols: Int = 64,
    private val minClusterCells: Int = 9,
    private val deltaThreshold: Float = 24f,
    private val maxBlobs: Int = 2,
    /** How much of each new frame blends into the background, per frame, for cells not currently foreground. */
    private val backgroundLearningRate: Float = 0.04f,
    /** Frames spent building the initial background average before any detection is reported. */
    private val calibrationFrames: Int = 4
) : ObjectDetector {

    private val _status = MutableStateFlow(DetectorStatus.MOTION_CV)
    override val status: StateFlow<DetectorStatus> = _status
    override val modelInfo: String = "MOTION CV — background-subtraction blob tracking (no trained model)"

    private var background: FloatArray? = null
    private var bgCols = 0
    private var bgRows = 0
    private var calibrationFramesLeft = 0

    override fun detect(bitmap: Bitmap): List<Detection> {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return emptyList()

        val cols = gridCols.coerceAtLeast(8)
        val rows = (cols * h / w).coerceAtLeast(6)

        val gray = computeGrayGrid(bitmap, cols, rows)

        val bg = background
        if (bg == null || bgCols != cols || bgRows != rows) {
            background = gray.copyOf()
            bgCols = cols
            bgRows = rows
            calibrationFramesLeft = calibrationFrames
            return emptyList()
        }

        // Keep averaging every cell into the background for the first few frames after (re)arming,
        // so a single unlucky noisy frame never becomes the permanent reference. Nothing is reported
        // as a detection yet since there's no "before" to compare against.
        if (calibrationFramesLeft > 0) {
            for (i in gray.indices) {
                bg[i] = bg[i] * 0.5f + gray[i] * 0.5f
            }
            calibrationFramesLeft--
            return emptyList()
        }

        val rawChanged = BooleanArray(gray.size)
        for (i in gray.indices) {
            rawChanged[i] = abs(gray[i] - bg[i]) > deltaThreshold
        }

        // Morphological filter: a cell only counts as changed if at least 2 of its 8 neighbors also
        // changed. Kills the single/double isolated-cell flicker (dust, heat shimmer, compression
        // noise) that a raw per-cell threshold lets through, without needing a higher threshold that
        // would also blind the detector to smaller real objects.
        val changed = BooleanArray(gray.size)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val idx = r * cols + c
                if (!rawChanged[idx]) continue
                var neighbors = 0
                for (dr in -1..1) for (dc in -1..1) {
                    if (dr == 0 && dc == 0) continue
                    val nr = r + dr; val nc = c + dc
                    if (nr in 0 until rows && nc in 0 until cols && rawChanged[nr * cols + nc]) neighbors++
                }
                if (neighbors >= 2) changed[idx] = true
            }
        }

        // Only cells NOT currently flagged as changed get folded into the background. This is what
        // keeps a real, stationary object from disappearing into the reference the way the old
        // frame-to-frame version did — its pixels are excluded from the update for as long as it's
        // there, so it keeps reading as "different from background" instead of quietly becoming the
        // new normal.
        for (i in gray.indices) {
            if (!changed[i]) {
                bg[i] = bg[i] * (1 - backgroundLearningRate) + gray[i] * backgroundLearningRate
            }
        }

        return extractBlobs(changed, cols, rows)
    }

    private fun computeGrayGrid(bitmap: Bitmap, cols: Int, rows: Int): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, cols, rows, true)
        val pixels = IntArray(cols * rows)
        scaled.getPixels(pixels, 0, cols, 0, 0, cols, rows)
        val gray = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        if (scaled !== bitmap) scaled.recycle()
        return gray
    }

    /** 4-connected BFS clustering of the changed-cell grid into bounding boxes. */
    private fun extractBlobs(changed: BooleanArray, cols: Int, rows: Int): List<Detection> {
        val visited = BooleanArray(changed.size)
        val queue = ArrayDeque<Int>()
        val clusters = mutableListOf<IntArray>() // [minR, maxR, minC, maxC, count]

        for (start in changed.indices) {
            if (!changed[start] || visited[start]) continue
            visited[start] = true
            queue.clear()
            queue.add(start)
            var minR = rows; var maxR = -1; var minC = cols; var maxC = -1; var count = 0

            while (queue.isNotEmpty()) {
                val idx = queue.removeFirst()
                val r = idx / cols
                val c = idx % cols
                if (r < minR) minR = r
                if (r > maxR) maxR = r
                if (c < minC) minC = c
                if (c > maxC) maxC = c
                count++

                // Up/down neighbors.
                val up = idx - cols
                val down = idx + cols
                if (up >= 0 && changed[up] && !visited[up]) { visited[up] = true; queue.add(up) }
                if (down < changed.size && changed[down] && !visited[down]) { visited[down] = true; queue.add(down) }
                // Left/right neighbors, guarded against wrapping across row edges.
                if (c > 0) {
                    val left = idx - 1
                    if (changed[left] && !visited[left]) { visited[left] = true; queue.add(left) }
                }
                if (c < cols - 1) {
                    val right = idx + 1
                    if (changed[right] && !visited[right]) { visited[right] = true; queue.add(right) }
                }
            }

            if (count >= minClusterCells) clusters += intArrayOf(minR, maxR, minC, maxC, count)
        }

        clusters.sortByDescending { it[4] }

        val pad = 0.01f
        return clusters.take(maxBlobs).map { cl ->
            val (minR, maxR, minC, maxC, count) = cl
            val left = minC.toFloat() / cols
            val top = minR.toFloat() / rows
            val right = (maxC + 1).toFloat() / cols
            val bottom = (maxR + 1).toFloat() / rows
            val areaFrac = (count.toFloat() / (cols * rows)).coerceAtMost(1f)
            Detection(
                classId = -1,
                label = ObjectClass.UNKNOWN.label,
                confidence = (0.55f + 0.35f * areaFrac).coerceIn(0.5f, 0.9f),
                box = NormRect(
                    (left - pad).coerceIn(0f, 1f),
                    (top - pad).coerceIn(0f, 1f),
                    (right + pad).coerceIn(0f, 1f),
                    (bottom + pad).coerceIn(0f, 1f)
                )
            )
        }
    }

    private operator fun IntArray.component1() = this[0]
    private operator fun IntArray.component2() = this[1]
    private operator fun IntArray.component3() = this[2]
    private operator fun IntArray.component4() = this[3]
    private operator fun IntArray.component5() = this[4]

    override fun close() {
        background = null
    }
}

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
 * deliberately left out of this repo. The old fallback ([SimulatedDetector])
 * animated a box along a fixed sine-wave path that had nothing to do with
 * the actual video/screen content, which is why its box did not track a
 * real moving person or object — it wasn't supposed to.
 *
 * This detector instead finds bounding boxes for whatever is *actually*
 * moving in the frame via consecutive-frame differencing — the same
 * block-mean-luminance technique `change/ChangeDetectionEngine.kt` already
 * uses for zone-level change alerts, just run across the whole frame and
 * against the *previous* frame (a rolling reference) instead of a fixed
 * per-zone baseline. A genuinely moving person or vehicle now produces a
 * box whose position is computed from real pixel motion each frame, so it
 * stays locked to the object instead of wandering on its own.
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
    private val minClusterCells: Int = 4,
    private val deltaThreshold: Float = 14f,
    private val maxBlobs: Int = 2
) : ObjectDetector {

    private val _status = MutableStateFlow(DetectorStatus.MOTION_CV)
    override val status: StateFlow<DetectorStatus> = _status
    override val modelInfo: String = "MOTION CV — frame-difference blob tracking (no trained model)"

    private var prevGray: FloatArray? = null
    private var prevCols = 0
    private var prevRows = 0

    override fun detect(bitmap: Bitmap): List<Detection> {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return emptyList()

        val cols = gridCols.coerceAtLeast(8)
        val rows = (cols * h / w).coerceAtLeast(6)

        val gray = computeGrayGrid(bitmap, cols, rows)

        val prev = prevGray
        if (prev == null || prevCols != cols || prevRows != rows) {
            prevGray = gray
            prevCols = cols
            prevRows = rows
            return emptyList()
        }

        val changed = BooleanArray(gray.size)
        for (i in gray.indices) {
            changed[i] = abs(gray[i] - prev[i]) > deltaThreshold
        }

        // Roll the reference frame slowly toward the current one. This is what
        // makes the box track a *moving* object frame-to-frame (each new
        // position differs from the recent, not a far-past, reference) while
        // still letting a briefly-still object stay detected for a couple of
        // frames instead of vanishing the instant it pauses.
        for (i in gray.indices) {
            prev[i] = prev[i] * 0.55f + gray[i] * 0.45f
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
        prevGray = null
    }
}

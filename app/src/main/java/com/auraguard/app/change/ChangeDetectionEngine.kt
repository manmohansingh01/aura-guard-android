package com.auraguard.app.change

import android.graphics.Bitmap
import com.auraguard.app.core.NormRect

data class ChangeResult(
    val zoneId: String,
    val changed: Boolean,
    /** 0..1 — fraction of the zone's ROI that changed beyond the noise floor. */
    val confidence: Float,
    /** Grid cells (row, col) that changed, for the highlight overlay. */
    val changedCells: List<Pair<Int, Int>>,
    val gridSize: Int
)

/**
 * "Change Detection Engine" stage — a second, independent CV layer that
 * watches each armed zone's ROI for meaningful visual change relative to a
 * saved baseline/reference image, per the spec.
 *
 * Approach (kept dependency-light — no OpenCV native binary required — but
 * implementing the same ideas OpenCV would apply):
 *   1. Frame differencing against a stored baseline, not the previous
 *      frame, so slow drift doesn't mask a real change.
 *   2. The ROI is pooled into an NxN grid of block-mean luminance values
 *      (a cheap stand-in for image registration/SSIM's local-window
 *      comparison) rather than comparing raw pixels, which is what makes
 *      this robust to compression artifacts and sensor noise.
 *   3. A global brightness-shift correction is subtracted before
 *      thresholding, so uniform lighting changes don't trigger alerts.
 *   4. A simple morphological filter (an "erosion" pass implemented as a
 *      neighbor-count rule) discards isolated changed cells — the kind of
 *      single-cell flicker caused by a leaf or a shadow edge — and only
 *      keeps clusters of changed cells.
 *   5. When a frame is judged unchanged, the baseline is nudged a little
 *      toward the current frame (slow background modelling) so gradual
 *      lighting drift over minutes doesn't eventually cross the threshold.
 *
 * The change-confidence threshold is fully configurable (Settings +
 * per-zone sensitivity), as required by the spec.
 */
class ChangeDetectionEngine(private val gridSize: Int = 12, private val backgroundAdaptRate: Float = 0.015f) {

    private val baselineBlocks = mutableMapOf<String, FloatArray>()
    private val baselineSnapshots = mutableMapOf<String, Bitmap>()

    fun hasBaseline(zoneId: String): Boolean = baselineBlocks.containsKey(zoneId)

    fun getBaselineSnapshot(zoneId: String): Bitmap? = baselineSnapshots[zoneId]

    /** Establishes (or resets) the reference image for a zone's ROI — call after drawing/saving a zone. */
    fun setBaseline(zoneId: String, roiBitmap: Bitmap) {
        baselineBlocks[zoneId] = computeBlockMeans(roiBitmap)
        baselineSnapshots[zoneId]?.recycle()
        baselineSnapshots[zoneId] = roiBitmap.copy(Bitmap.Config.ARGB_8888, false)
    }

    fun clearZone(zoneId: String) {
        baselineBlocks.remove(zoneId)
        baselineSnapshots.remove(zoneId)?.recycle()
    }

    /**
     * Compares [roiBitmap] (the current crop of a zone's polygon bounding
     * box) against its stored baseline. [threshold] is the fraction of
     * grid cells (post-filtering) that must differ to call it a change —
     * lower threshold = more sensitive.
     */
    fun evaluate(zoneId: String, roiBitmap: Bitmap, threshold: Float): ChangeResult {
        val baseline = baselineBlocks[zoneId]
        if (baseline == null) {
            setBaseline(zoneId, roiBitmap)
            return ChangeResult(zoneId, false, 0f, emptyList(), gridSize)
        }

        val current = computeBlockMeans(roiBitmap)
        val globalShift = (current.average() - baseline.average()).toFloat()

        val rawChanged = BooleanArray(current.size)
        for (i in current.indices) {
            val adjusted = kotlin.math.abs((current[i] - globalShift) - baseline[i])
            rawChanged[i] = adjusted > BLOCK_DELTA_THRESHOLD
        }

        // Morphological filter: keep a cell only if >=2 of its 8 neighbors also changed.
        val changedCells = mutableListOf<Pair<Int, Int>>()
        for (r in 0 until gridSize) {
            for (c in 0 until gridSize) {
                val idx = r * gridSize + c
                if (!rawChanged[idx]) continue
                var neighbors = 0
                for (dr in -1..1) for (dc in -1..1) {
                    if (dr == 0 && dc == 0) continue
                    val nr = r + dr; val nc = c + dc
                    if (nr in 0 until gridSize && nc in 0 until gridSize && rawChanged[nr * gridSize + nc]) neighbors++
                }
                if (neighbors >= 2) changedCells += r to c
            }
        }

        val confidence = (changedCells.size.toFloat() / (gridSize * gridSize)).coerceIn(0f, 1f)
        val changed = confidence >= threshold

        if (!changed) {
            // Slow background adaptation absorbs gradual lighting drift so it never accumulates into a false alert.
            for (i in baseline.indices) {
                baseline[i] = baseline[i] * (1 - backgroundAdaptRate) + current[i] * backgroundAdaptRate
            }
        }

        return ChangeResult(zoneId, changed, confidence, changedCells, gridSize)
    }

    /** Maps a change result's grid cells back into a normalized bounding rect within the zone's ROI, for the UI overlay. */
    fun changedRegionWithinRoi(result: ChangeResult): NormRect? {
        if (result.changedCells.isEmpty()) return null
        var minR = result.gridSize; var maxR = 0; var minC = result.gridSize; var maxC = 0
        for ((r, c) in result.changedCells) {
            minR = minOf(minR, r); maxR = maxOf(maxR, r)
            minC = minOf(minC, c); maxC = maxOf(maxC, c)
        }
        val g = result.gridSize.toFloat()
        return NormRect(minC / g, minR / g, (maxC + 1) / g, (maxR + 1) / g)
    }

    /** Bilinear-downsampling the ROI to an NxN grid is a cheap, fast approximation of block-mean pooling. */
    private fun computeBlockMeans(bitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, gridSize, gridSize, true)
        val pixels = IntArray(gridSize * gridSize)
        scaled.getPixels(pixels, 0, gridSize, 0, 0, gridSize, gridSize)
        val means = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            means[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        if (scaled !== bitmap) scaled.recycle()
        return means
    }

    companion object {
        /** Luminance (0-255 scale) difference a single grid cell must exceed to be considered "raw changed". */
        private const val BLOCK_DELTA_THRESHOLD = 16f
    }
}

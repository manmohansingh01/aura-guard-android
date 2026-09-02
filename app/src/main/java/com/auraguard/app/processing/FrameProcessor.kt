package com.auraguard.app.processing

import android.graphics.Bitmap
import com.auraguard.app.core.NormRect

/** Small bitmap helpers shared by the AI detector and change-detection stages. */
object FrameProcessor {

    /** Crops the region of [bitmap] described by a normalized [NormRect]. Returns null for a degenerate box. */
    fun crop(bitmap: Bitmap, rect: NormRect): Bitmap? {
        val left = (rect.left.coerceIn(0f, 1f) * bitmap.width).toInt()
        val top = (rect.top.coerceIn(0f, 1f) * bitmap.height).toInt()
        val right = (rect.right.coerceIn(0f, 1f) * bitmap.width).toInt()
        val bottom = (rect.bottom.coerceIn(0f, 1f) * bitmap.height).toInt()
        val w = (right - left).coerceAtLeast(1)
        val h = (bottom - top).coerceAtLeast(1)
        if (left < 0 || top < 0 || left + w > bitmap.width || top + h > bitmap.height) return null
        return try {
            Bitmap.createBitmap(bitmap, left, top, w, h)
        } catch (t: Throwable) {
            null
        }
    }

    /** Downscales a bitmap for cheap storage (event log thumbnails / snapshots). */
    fun thumbnail(bitmap: Bitmap, maxDim: Int = 480): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxDim) return bitmap
        val scale = maxDim.toFloat() / largest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
    }
}

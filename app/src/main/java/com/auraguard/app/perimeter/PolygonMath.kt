package com.auraguard.app.perimeter

import com.auraguard.app.core.NormPoint
import kotlin.math.sqrt

/**
 * Pure geometry helpers for perimeter polygons, all in normalized [0,1]
 * image-space coordinates so a zone stays correctly aligned regardless of
 * the capture/video resolution — the UI only ever multiplies these by the
 * current view size at draw time.
 */
object PolygonMath {

    /** Standard ray-casting point-in-polygon test. */
    fun contains(polygon: List<NormPoint>, point: NormPoint): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]
            if ((pi.y > point.y) != (pj.y > point.y) &&
                point.x < (pj.x - pi.x) * (point.y - pi.y) / ((pj.y - pi.y).takeIf { it != 0f } ?: 1e-6f) + pi.x
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /** Shortest distance from a point to the polygon's boundary (0 if inside or degenerate). */
    fun distanceToBoundary(polygon: List<NormPoint>, point: NormPoint): Float {
        if (polygon.size < 2) return Float.MAX_VALUE
        var minDist = Float.MAX_VALUE
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            minDist = minOf(minDist, distanceToSegment(point, a, b))
        }
        return minDist
    }

    private fun distanceToSegment(p: NormPoint, a: NormPoint, b: NormPoint): Float {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val lenSq = abx * abx + aby * aby
        val t = if (lenSq > 1e-9f) (((p.x - a.x) * abx + (p.y - a.y) * aby) / lenSq).coerceIn(0f, 1f) else 0f
        val projX = a.x + t * abx
        val projY = a.y + t * aby
        val dx = p.x - projX
        val dy = p.y - projY
        return sqrt(dx * dx + dy * dy)
    }

    /** Bounding box (minX, minY, maxX, maxY) of a polygon, for change-detection ROI cropping. */
    fun boundingBox(polygon: List<NormPoint>): FloatArray {
        var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
        for (p in polygon) {
            minX = minOf(minX, p.x); minY = minOf(minY, p.y)
            maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y)
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }
}

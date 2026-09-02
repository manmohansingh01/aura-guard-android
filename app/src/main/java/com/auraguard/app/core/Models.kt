package com.auraguard.app.core

/**
 * Shared, dependency-free data types used across the AURA Guard pipeline:
 *
 *   Screen Capture -> Frame Processor -> AI Detector -> Object Tracker ->
 *   Perimeter Engine -> Change Detection Engine -> Event/Alert Engine -> UI
 *
 * Keeping these in one file with no Android/TFLite imports means every
 * downstream stage can be unit tested and swapped independently.
 */

/** Normalized point in image space: x,y in [0,1] relative to the analyzed frame. */
data class NormPoint(val x: Float, val y: Float)

/** A single detector output before tracking is applied. */
data class Detection(
    val classId: Int,
    val label: String,
    val confidence: Float,
    /** Bounding box in normalized [0,1] image-space coordinates. */
    val box: NormRect
)

data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun area(): Float = width.coerceAtLeast(0f) * height.coerceAtLeast(0f)

    fun iou(other: NormRect): Float {
        val ix1 = maxOf(left, other.left)
        val iy1 = maxOf(top, other.top)
        val ix2 = minOf(right, other.right)
        val iy2 = minOf(bottom, other.bottom)
        val iw = (ix2 - ix1).coerceAtLeast(0f)
        val ih = (iy2 - iy1).coerceAtLeast(0f)
        val inter = iw * ih
        val union = area() + other.area() - inter
        return if (union <= 0f) 0f else inter / union
    }
}

/** Object classes AURA Guard's initial detector head recognizes. */
enum class ObjectClass(val label: String) {
    PERSON("PERSON"),
    CAR("CAR"),
    TRUCK("TRUCK"),
    MOTORCYCLE("MOTORCYCLE"),
    BICYCLE("BICYCLE"),
    UNKNOWN("OBJECT");

    companion object {
        fun fromLabel(label: String): ObjectClass =
            entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: UNKNOWN
    }
}

/** Relationship of a tracked object to the perimeter it is nearest to. */
enum class PerimeterState { SAFE, APPROACHING, BREACH }

enum class AlertLevel { INFORMATION, WARNING, CRITICAL }

enum class ProcessingRate(val targetFps: Int, val label: String) {
    LOW(3, "LOW"),
    MEDIUM(6, "MEDIUM"),
    HIGH(10, "HIGH")
}

enum class InputSource { SCREEN_CAPTURE, DEMO_VIDEO, NONE }

enum class SystemComponentStatus { ACTIVE, STANDBY, ERROR }

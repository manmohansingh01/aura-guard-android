package com.auraguard.app.tracking

import com.auraguard.app.core.NormPoint
import com.auraguard.app.core.NormRect
import com.auraguard.app.core.ObjectClass
import com.auraguard.app.core.PerimeterState

/** A detection that has been associated with a persistent tracking ID across frames. */
data class TrackedObject(
    val id: Int,
    val objectClass: ObjectClass,
    val label: String,
    val confidence: Float,
    val box: NormRect,
    /** Most recent centroids, oldest first, capped for a short movement trail. */
    val trail: List<NormPoint> = emptyList(),
    val perimeterState: PerimeterState = PerimeterState.SAFE,
    /** Zone id this object is currently interacting with (breaching/approaching), if any. */
    val relevantZoneId: String? = null,
    val framesSinceSeen: Int = 0,
    val velocity: NormPoint = NormPoint(0f, 0f),
    /**
     * Consecutive frames this track has been matched to a fresh detection,
     * starting at 1 the frame it first appears. [CentroidTracker] only
     * reports a track once this reaches its confirmation threshold, so a
     * single-frame flicker (camera shake, a lighting change) never becomes
     * a visible box, a trail line, or a perimeter alert — only something
     * that keeps showing up frame after frame does.
     */
    val hitStreak: Int = 1
) {
    val displayName: String get() = "${label} #${id.toString().padStart(2, '0')}"
}

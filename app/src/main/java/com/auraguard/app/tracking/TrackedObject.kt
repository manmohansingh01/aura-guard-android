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
    val velocity: NormPoint = NormPoint(0f, 0f)
) {
    val displayName: String get() = "${label} #${id.toString().padStart(2, '0')}"
}

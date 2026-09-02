package com.auraguard.app.perimeter

import com.auraguard.app.core.NormPoint

/** UI-facing state for the "DEFINE PERIMETER" drawing mode, owned by AuraViewModel. */
data class PerimeterEditState(
    val isActive: Boolean = false,
    val points: List<NormPoint> = emptyList(),
    val targetZoneId: String? = null // null while drawing a brand-new zone
) {
    val isClosable: Boolean get() = points.size >= 3
}

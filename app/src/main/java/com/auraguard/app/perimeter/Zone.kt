package com.auraguard.app.perimeter

import com.auraguard.app.core.NormPoint
import com.auraguard.app.core.PerimeterState
import java.util.UUID

/**
 * A named restricted-perimeter polygon, defined in image-space (normalized
 * 0..1 coordinates of the analyzed video frame). Because the drone camera
 * moves, this is explicitly NOT a geospatial/GPS polygon — see
 * CalibrationNotice for the operator-facing disclaimer.
 */
data class Zone(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val points: List<NormPoint> = emptyList(),
    val armed: Boolean = true,
    /** 0f (least sensitive) .. 1f (most sensitive). Scales approach distance & change threshold. */
    val sensitivity: Float = 0.5f,
    val color: Long = 0xFF00E5A0,
    val currentState: PerimeterState = PerimeterState.SAFE
) {
    val isClosed: Boolean get() = points.size >= 3

    companion object {
        val PALETTE: List<Long> = listOf(0xFF00E5A0, 0xFF3DA8FF, 0xFFFFB020, 0xFFFF5C5C, 0xFFB07CFF)
        val DEFAULT_NAMES = listOf("ALPHA", "BRAVO", "CHARLIE", "DELTA", "ECHO")
    }
}

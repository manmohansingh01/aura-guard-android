package com.auraguard.app.perimeter

import com.auraguard.app.core.NormPoint
import com.auraguard.app.core.PerimeterState
import com.auraguard.app.tracking.TrackedObject

/**
 * "Perimeter Engine" stage — the most important piece of AURA Guard's
 * logic per the spec: for every tracked object, is its center point inside
 * an armed zone's polygon (BREACH), close to its boundary from the outside
 * (APPROACHING), or unrelated (SAFE)? Per-zone state is the worst
 * (highest-severity) state among the objects currently associated with it.
 *
 * IMPORTANT: this operates entirely in image-space (normalized video
 * coordinates). It has no notion of real-world GPS/geospatial position —
 * see the calibration notice shown in Setup/Zones.
 */
class PerimeterEngine {

    data class Evaluation(
        /** trackId -> (state, zoneId it's relevant to, if any) */
        val objectStates: Map<Int, Pair<PerimeterState, String?>>,
        /** zoneId -> aggregate state across all objects currently relevant to it */
        val zoneStates: Map<String, PerimeterState>
    )

    /** How close (normalized distance) to a zone boundary counts as "approaching"; scales with sensitivity. */
    private fun approachThreshold(zone: Zone): Float = 0.03f + zone.sensitivity.coerceIn(0f, 1f) * 0.12f

    fun evaluate(zones: List<Zone>, objects: List<TrackedObject>): Evaluation {
        val armedZones = zones.filter { it.armed && it.isClosed }
        val zoneStates = LinkedHashMap<String, PerimeterState>()
        armedZones.forEach { zoneStates[it.id] = PerimeterState.SAFE }

        val objectStates = LinkedHashMap<Int, Pair<PerimeterState, String?>>()

        for (obj in objects) {
            val center = NormPoint(obj.box.centerX, obj.box.centerY)
            var bestState = PerimeterState.SAFE
            var bestZoneId: String? = null

            for (zone in armedZones) {
                val state = when {
                    PolygonMath.contains(zone.points, center) -> PerimeterState.BREACH
                    PolygonMath.distanceToBoundary(zone.points, center) <= approachThreshold(zone) ->
                        PerimeterState.APPROACHING
                    else -> PerimeterState.SAFE
                }
                if (state.ordinal > bestState.ordinal) {
                    bestState = state
                    bestZoneId = zone.id
                }
                if (state.ordinal > (zoneStates[zone.id]?.ordinal ?: 0)) {
                    zoneStates[zone.id] = state
                }
            }
            objectStates[obj.id] = bestState to bestZoneId
        }

        return Evaluation(objectStates, zoneStates)
    }
}

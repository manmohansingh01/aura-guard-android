package com.auraguard.app.tracking

import com.auraguard.app.core.Detection
import com.auraguard.app.core.NormPoint
import com.auraguard.app.core.ObjectClass
import kotlin.math.sqrt

/**
 * "Object Tracker" stage. A lightweight, dependency-free centroid tracker:
 * greedy nearest-neighbor association between this frame's detections and
 * existing tracks (same class + centroid within [matchDistanceThreshold]),
 * assigning a persistent integer ID the first time an object appears and
 * keeping it while the object stays visible or briefly occluded.
 *
 * This intentionally is NOT DeepSORT/ByteTrack — those are natural
 * upgrades if heavier tracking is needed later, but a centroid tracker is
 * enough to give every box a stable ID + short motion trail at the
 * 3-10 FPS this app samples detections, which is what perimeter
 * breach/approach logic and the UI need.
 */
class CentroidTracker(
    private val maxFramesLost: Int = 8,
    private val maxTrailLength: Int = 14,
    private val matchDistanceThreshold: Float = 0.18f,
    /**
     * Frames a track must be matched to a fresh detection before it is
     * reported to the rest of the app (rendered, or eligible for a
     * perimeter alert). Kills one-off ghost boxes from camera shake or a
     * single noisy frame — a real object stays matched frame after frame
     * and clears this in well under a second at the app's sample rates; a
     * shake artifact almost never does.
     */
    private val confirmFrames: Int = 3
) {
    private var nextId = 1
    private val tracks = LinkedHashMap<Int, TrackedObject>()

    /** Feed one frame's detections; returns the currently-visible tracked objects (stable IDs, trails). */
    fun update(detections: List<Detection>): List<TrackedObject> {
        data class Candidate(val trackId: Int, val detIndex: Int, val distance: Float)

        val candidates = mutableListOf<Candidate>()
        for ((id, track) in tracks) {
            for ((idx, det) in detections.withIndex()) {
                if (!track.label.equals(det.label, ignoreCase = true)) continue
                val dx = track.box.centerX - det.box.centerX
                val dy = track.box.centerY - det.box.centerY
                val dist = sqrt(dx * dx + dy * dy)
                if (dist <= matchDistanceThreshold) candidates += Candidate(id, idx, dist)
            }
        }
        candidates.sortBy { it.distance }

        val usedTrackIds = mutableSetOf<Int>()
        val matchedDetIndices = mutableSetOf<Int>()
        val updated = LinkedHashMap<Int, TrackedObject>()

        for (cand in candidates) {
            if (cand.trackId in usedTrackIds || cand.detIndex in matchedDetIndices) continue
            usedTrackIds += cand.trackId
            matchedDetIndices += cand.detIndex

            val det = detections[cand.detIndex]
            val prev = tracks.getValue(cand.trackId)
            val newCentroid = NormPoint(det.box.centerX, det.box.centerY)
            val trail = (prev.trail + newCentroid).let {
                if (it.size > maxTrailLength) it.takeLast(maxTrailLength) else it
            }
            val velocity = NormPoint(
                det.box.centerX - prev.box.centerX,
                det.box.centerY - prev.box.centerY
            )
            updated[cand.trackId] = prev.copy(
                confidence = det.confidence,
                box = det.box,
                trail = trail,
                framesSinceSeen = 0,
                velocity = velocity,
                hitStreak = prev.hitStreak + 1
            )
        }

        // Keep briefly-occluded tracks alive (no new box this frame) so IDs survive short dropouts.
        for ((id, track) in tracks) {
            if (id in usedTrackIds) continue
            val aged = track.copy(framesSinceSeen = track.framesSinceSeen + 1)
            if (aged.framesSinceSeen <= maxFramesLost) {
                updated[id] = aged
            }
        }

        // New tracks for detections nothing matched.
        for ((idx, det) in detections.withIndex()) {
            if (idx in matchedDetIndices) continue
            val id = nextId++
            updated[id] = TrackedObject(
                id = id,
                objectClass = ObjectClass.fromLabel(det.label),
                label = det.label,
                confidence = det.confidence,
                box = det.box,
                trail = listOf(NormPoint(det.box.centerX, det.box.centerY))
            )
        }

        tracks.clear()
        tracks.putAll(updated)

        // Only objects seen in *this* frame, and matched for `confirmFrames` frames running, are
        // reported as "currently visible" to the UI/perimeter engine. Briefly-occluded tracks stay
        // in `tracks` internally so their ID/trail/streak survives; not-yet-confirmed ones do too,
        // so a genuine object still appears within a couple of frames instead of restarting its
        // streak from zero.
        return tracks.values.filter { it.framesSinceSeen == 0 && it.hitStreak >= confirmFrames }
    }

    fun reset() {
        tracks.clear()
        nextId = 1
    }
}

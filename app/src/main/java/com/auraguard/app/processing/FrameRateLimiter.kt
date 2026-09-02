package com.auraguard.app.processing

/**
 * "Frame Processor" stage: decides which captured frames are actually worth
 * sending through AI inference. The UI keeps rendering every captured
 * frame for a smooth live view, but expensive detector calls only run at
 * the configurable AI Processing Rate (Low/Medium/High) — this is what
 * keeps AURA Guard real-time on-device instead of running inference on
 * every single screen frame.
 */
class FrameRateLimiter(targetFps: Int) {

    @Volatile
    var targetFps: Int = targetFps
        set(value) {
            field = value.coerceAtLeast(1)
        }

    private var lastAcceptedAtMs: Long = 0L

    /** Call once per incoming frame; returns true if this frame should be run through inference now. */
    fun shouldProcess(nowMs: Long = System.currentTimeMillis()): Boolean {
        val minIntervalMs = 1000L / targetFps
        return if (nowMs - lastAcceptedAtMs >= minIntervalMs) {
            lastAcceptedAtMs = nowMs
            true
        } else {
            false
        }
    }
}

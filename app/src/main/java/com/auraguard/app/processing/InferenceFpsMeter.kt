package com.auraguard.app.processing

/** Tracks the achieved AI inference throughput (distinct from raw capture FPS) for the status bar. */
class InferenceFpsMeter {
    private var windowStart = 0L
    private var count = 0
    private var lastFps = 0f

    fun tick(nowMs: Long = System.currentTimeMillis()): Float {
        if (windowStart == 0L) windowStart = nowMs
        count++
        val elapsed = nowMs - windowStart
        if (elapsed >= 1000) {
            lastFps = count * 1000f / elapsed
            windowStart = nowMs
            count = 0
        }
        return lastFps
    }

    fun current(): Float = lastFps
}

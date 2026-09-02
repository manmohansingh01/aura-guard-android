package com.auraguard.app.events

import com.auraguard.app.core.AlertLevel

enum class EventType { BREACH, APPROACHING, CHANGE_DETECTED, OBJECT_DETECTED, SYSTEM }

/**
 * A single entry in the local, on-device event log. Nothing here is ever
 * transmitted off the device — see EventRepository for local-only persistence.
 */
data class AuraEvent(
    val id: String,
    val timestampMillis: Long,
    val type: EventType,
    val level: AlertLevel,
    val zoneName: String? = null,
    val objectLabel: String? = null,
    val trackId: Int? = null,
    val confidence: Float? = null,
    val message: String,
    /** Path to a saved JPEG frame snapshot for this event, if captured. */
    val snapshotPath: String? = null,
    /** For CHANGE_DETECTED events: path to the baseline/reference frame for before/after review. */
    val baselineSnapshotPath: String? = null
)

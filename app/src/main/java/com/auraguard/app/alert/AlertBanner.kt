package com.auraguard.app.alert

import com.auraguard.app.core.AlertLevel

/** A currently-displayed large warning banner on the LIVE screen. */
data class AlertBannerData(
    val level: AlertLevel,
    val title: String,
    val subtitle: String,
    val zoneName: String?,
    val timestampMillis: Long
)

package com.auraguard.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auraguard.app.ai.DetectorStatus
import com.auraguard.app.capture.CaptureState
import com.auraguard.app.core.AlertLevel
import com.auraguard.app.ui.theme.OpsAccent
import com.auraguard.app.ui.theme.OpsBorder
import com.auraguard.app.ui.theme.OpsCritical
import com.auraguard.app.ui.theme.OpsInfo
import com.auraguard.app.ui.theme.OpsSurface
import com.auraguard.app.ui.theme.OpsTextPrimary
import com.auraguard.app.ui.theme.OpsTextSecondary
import com.auraguard.app.ui.theme.OpsWarning

/** Top command bar: "AURA GUARD / SYSTEM: ACTIVE" plus the LIVE / AI / FPS / INFERENCE / ALERT readouts. */
@Composable
fun AuraStatusBar(
    captureState: CaptureState,
    detectorStatus: DetectorStatus,
    fps: Float,
    inferenceFps: Float,
    alertLevel: AlertLevel,
    modifier: Modifier = Modifier
) {
    val systemActive = captureState == CaptureState.ACTIVE
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OpsSurface)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "AURA GUARD",
                color = OpsTextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                letterSpacing = 2.sp
            )
            StatusPill(
                label = if (systemActive) "SYSTEM: ACTIVE" else "SYSTEM: STANDBY",
                dotColor = if (systemActive) OpsAccent else OpsTextSecondary
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MetricReadout("LIVE", if (systemActive) "ON AIR" else "OFF", if (systemActive) OpsCritical else OpsTextSecondary)
            MetricReadout("AI STATUS", if (systemActive) "ACTIVE" else "STANDBY", if (systemActive) OpsAccent else OpsTextSecondary)
            MetricReadout("FPS", String.format("%.1f", fps), OpsInfo)
            MetricReadout("INFERENCE", inferenceLabel(detectorStatus), inferenceColor(detectorStatus))
            MetricReadout("ALERT", alertLevel.name, alertColor(alertLevel))
        }
    }
}

@Composable
private fun MetricReadout(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, color = OpsTextSecondary, fontSize = 9.sp, letterSpacing = 0.8.sp)
        Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatusPill(label: String, dotColor: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(OpsBorder)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Column(modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(dotColor)) {}
        Text(label, color = OpsTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

private fun inferenceLabel(status: DetectorStatus): String = when (status) {
    DetectorStatus.READY -> "READY"
    DetectorStatus.LOADING -> "LOADING"
    DetectorStatus.NO_MODEL -> "NO MODEL"
    DetectorStatus.ERROR -> "ERROR"
    DetectorStatus.SIMULATED -> "SIMULATED"
}

private fun inferenceColor(status: DetectorStatus): Color = when (status) {
    DetectorStatus.READY -> OpsAccent
    DetectorStatus.LOADING -> OpsInfo
    DetectorStatus.NO_MODEL -> OpsWarning
    DetectorStatus.ERROR -> OpsCritical
    DetectorStatus.SIMULATED -> OpsWarning
}

private fun alertColor(level: AlertLevel): Color = when (level) {
    AlertLevel.INFORMATION -> OpsAccent
    AlertLevel.WARNING -> OpsWarning
    AlertLevel.CRITICAL -> OpsCritical
}

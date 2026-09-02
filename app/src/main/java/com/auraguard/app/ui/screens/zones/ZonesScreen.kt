package com.auraguard.app.ui.screens.zones

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auraguard.app.core.AuraViewModel
import com.auraguard.app.core.PerimeterState
import com.auraguard.app.perimeter.Zone
import com.auraguard.app.ui.theme.OpsAccent
import com.auraguard.app.ui.theme.OpsBackground
import com.auraguard.app.ui.theme.OpsBorder
import com.auraguard.app.ui.theme.OpsCritical
import com.auraguard.app.ui.theme.OpsInfo
import com.auraguard.app.ui.theme.OpsSurface
import com.auraguard.app.ui.theme.OpsSurfaceElevated
import com.auraguard.app.ui.theme.OpsTextPrimary
import com.auraguard.app.ui.theme.OpsTextSecondary
import com.auraguard.app.ui.theme.OpsWarning

@Composable
fun ZonesScreen(viewModel: AuraViewModel, onNavigateToLive: () -> Unit = {}) {
    val zones by viewModel.zones.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OpsBackground)
            .padding(16.dp)
    ) {
        Text("ZONES", color = OpsTextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(10.dp))
        CalibrationNotice()
        Spacer(Modifier.height(14.dp))

        if (zones.isEmpty()) {
            Text(
                "No zones defined yet. Go to LIVE → DEFINE PERIMETER to draw one.",
                color = OpsTextSecondary, fontSize = 13.sp
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(zones, key = { it.id }) { zone ->
                    ZoneCard(
                        zone = zone,
                        onArmedChange = { armed -> viewModel.setZoneArmed(zone.id, armed) },
                        onSensitivityChange = { s -> viewModel.setZoneSensitivity(zone.id, s) },
                        onEdit = { viewModel.beginDefinePerimeter(zone.id); onNavigateToLive() },
                        onDelete = { viewModel.deleteZone(zone.id) },
                        onResetBaseline = { viewModel.resetZoneBaseline(zone.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CalibrationNotice() {
    Card(
        colors = CardDefaults.cardColors(containerColor = OpsSurfaceElevated),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("SETUP / CALIBRATION NOTICE", color = OpsInfo, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Perimeters are drawn in IMAGE SPACE — pixel coordinates of the video frame, " +
                    "not real-world GPS position. Because the drone camera moves, a zone stays valid " +
                    "only while the camera framing stays roughly the same as when it was drawn. " +
                    "Re-draw the perimeter after repositioning the drone. This app does not claim or use " +
                    "geospatial/GPS coordinates unless a future version explicitly integrates them.",
                color = OpsTextSecondary, fontSize = 11.sp, lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun ZoneCard(
    zone: Zone,
    onArmedChange: (Boolean) -> Unit,
    onSensitivityChange: (Float) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onResetBaseline: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = OpsSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(color = Color(zone.color))
                    Spacer(Modifier.width(8.dp))
                    Text(zone.name, color = OpsTextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (zone.armed) "ARMED" else "DISARMED",
                        color = if (zone.armed) OpsAccent else OpsTextSecondary,
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(6.dp))
                    Switch(
                        checked = zone.armed,
                        onCheckedChange = onArmedChange,
                        colors = SwitchDefaults.colors(checkedTrackColor = OpsAccent)
                    )
                }
            }

            StateBadge(zone.currentState)

            Spacer(Modifier.height(8.dp))
            Text("SENSITIVITY: ${(zone.sensitivity * 100).toInt()}%", color = OpsTextSecondary, fontSize = 11.sp)
            Slider(
                value = zone.sensitivity,
                onValueChange = onSensitivityChange,
                colors = SliderDefaults.colors(thumbColor = OpsAccent, activeTrackColor = OpsAccent)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.height(16.dp))
                    Text(" EDIT", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onResetBaseline, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.height(16.dp))
                    Text(" RESET BASELINE", fontSize = 11.sp)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete zone", tint = OpsCritical)
                }
            }
        }
    }
}

@Composable
private fun Box(color: Color) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .height(14.dp)
            .width(14.dp)
            .background(color, RoundedCornerShape(3.dp))
    )
}

@Composable
private fun StateBadge(state: PerimeterState) {
    val (label, color) = when (state) {
        PerimeterState.SAFE -> "SAFE" to OpsAccent
        PerimeterState.APPROACHING -> "APPROACHING" to OpsWarning
        PerimeterState.BREACH -> "BREACH" to OpsCritical
    }
    Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
}

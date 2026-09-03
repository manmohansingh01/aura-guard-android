package com.auraguard.app.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auraguard.app.core.AuraViewModel
import com.auraguard.app.ui.components.AlertBannerView
import com.auraguard.app.ui.screens.live.DetectionOverlay
import com.auraguard.app.ui.screens.live.PerimeterOverlay
import com.auraguard.app.ui.theme.OpsAccent
import com.auraguard.app.ui.theme.OpsBorder
import com.auraguard.app.ui.theme.OpsCritical
import com.auraguard.app.ui.theme.OpsInfo
import com.auraguard.app.ui.theme.OpsSurface
import com.auraguard.app.ui.theme.OpsTextSecondary
import com.auraguard.app.ui.theme.OpsWarning

/**
 * Full-screen layer of the floating overlay: detection boxes, saved
 * perimeter zones, the in-progress polygon while defining one, and the
 * alert banner — the exact same composables LiveScreen uses, just drawn
 * directly on top of whatever app is currently on screen instead of
 * inside AURA Guard's own window. Normally touch-transparent (see
 * [OverlayController]) so it never blocks the drone app underneath;
 * [OverlayController] makes this window touchable only while
 * `editState.isActive`, which is what lets PerimeterOverlay's own tap/drag
 * gesture handling receive taps here.
 */
@Composable
fun OverlayDetectionLayer(viewModel: AuraViewModel) {
    val trackedObjects by viewModel.trackedObjects.collectAsState()
    val zones by viewModel.zones.collectAsState()
    val editState by viewModel.editState.collectAsState()
    val alertBanner by viewModel.alertBanner.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        PerimeterOverlay(
            zones = zones,
            editState = editState,
            onAddPoint = viewModel::addEditPoint,
            onMovePoint = viewModel::moveEditPoint,
            modifier = Modifier.fillMaxSize()
        )
        DetectionOverlay(objects = trackedObjects, modifier = Modifier.fillMaxSize())
        AlertBannerView(
            banner = alertBanner,
            onDismiss = viewModel::dismissAlertBanner,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

/**
 * The always-touchable floating control bubble ("chat head"): a small
 * draggable button that expands into a compact panel with live
 * object/warning/critical counts and DEFINE PERIMETER controls, so an
 * operator can define/manage a perimeter and see status without ever
 * switching away from the drone app. Starting DEFINE PERIMETER
 * auto-collapses the panel back to the small button so it doesn't block
 * screen space while tapping corners on [OverlayDetectionLayer]; tapping
 * the button again re-expands it (still showing the active-edit controls).
 */
@Composable
fun OverlayControlBubble(
    viewModel: AuraViewModel,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onOpenApp: () -> Unit,
    onStopCapture: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentObjects by viewModel.currentObjectsCount.collectAsState()
    val warnings by viewModel.warningsCount.collectAsState()
    val criticals by viewModel.criticalCount.collectAsState()
    val editState by viewModel.editState.collectAsState()
    val zones by viewModel.zones.collectAsState()
    var showSaveField by remember { mutableStateOf(false) }
    var zoneNameInput by remember { mutableStateOf("") }

    val bubbleColor = when {
        criticals > 0 -> OpsCritical
        warnings > 0 -> OpsWarning
        else -> OpsAccent
    }

    if (!expanded) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(OpsSurface)
                .border(2.dp, bubbleColor, CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                }
                .clickable { expanded = true },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Shield, contentDescription = "AURA Guard", tint = bubbleColor)
        }
    } else {
        Column(
            modifier = Modifier
                .width(240.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(OpsSurface.copy(alpha = 0.97f))
                .border(1.dp, OpsBorder, RoundedCornerShape(16.dp))
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("AURA GUARD", color = OpsAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                IconButton(onClick = { expanded = false }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Collapse", tint = OpsTextSecondary)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MiniStat("OBJ", currentObjects.toString(), OpsInfo)
                MiniStat("WARN", warnings.toString(), OpsWarning)
                MiniStat("CRIT", criticals.toString(), OpsCritical)
                MiniStat("ZONE", zones.size.toString(), OpsAccent)
            }
            Spacer(Modifier.height(10.dp))

            if (editState.isActive) {
                if (showSaveField) {
                    OutlinedTextField(
                        value = zoneNameInput,
                        onValueChange = { zoneNameInput = it },
                        label = { Text("Zone name", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = {
                                viewModel.saveZone(zoneNameInput)
                                zoneNameInput = ""
                                showSaveField = false
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("SAVE", fontSize = 11.sp) }
                        OutlinedButton(onClick = { showSaveField = false }, modifier = Modifier.weight(1f)) {
                            Text("BACK", fontSize = 11.sp)
                        }
                    }
                } else {
                    Text(
                        "Tap the screen to add corners, drag to move them",
                        color = OpsTextSecondary, fontSize = 10.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = viewModel::undoLastEditPoint, modifier = Modifier.weight(1f)) {
                            Text("UNDO", fontSize = 10.sp)
                        }
                        OutlinedButton(onClick = viewModel::clearEditPoints, modifier = Modifier.weight(1f)) {
                            Text("CLEAR", fontSize = 10.sp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = viewModel::cancelDefinePerimeter, modifier = Modifier.weight(1f)) {
                            Text("CANCEL", fontSize = 10.sp)
                        }
                        Button(
                            onClick = { showSaveField = true },
                            enabled = editState.isClosable,
                            modifier = Modifier.weight(1f)
                        ) { Text("SAVE", fontSize = 10.sp) }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            viewModel.beginDefinePerimeter()
                            expanded = false
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("DEFINE PERIMETER", fontSize = 9.sp) }
                    OutlinedButton(onClick = onOpenApp, modifier = Modifier.weight(1f)) {
                        Text("OPEN APP", fontSize = 10.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = onStopCapture,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = OpsCritical)
                ) { Text("STOP CAPTURE", fontSize = 10.sp) }
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(label, color = OpsTextSecondary, fontSize = 8.sp)
    }
}

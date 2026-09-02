package com.auraguard.app.ui.screens.live

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import com.auraguard.app.capture.CaptureState
import com.auraguard.app.core.AuraViewModel
import com.auraguard.app.core.InputSource
import com.auraguard.app.core.PerimeterState
import com.auraguard.app.ui.components.AlertBannerView
import com.auraguard.app.ui.components.AuraStatusBar
import com.auraguard.app.ui.theme.OpsAccent
import com.auraguard.app.ui.theme.OpsBackground
import com.auraguard.app.ui.theme.OpsBorder
import com.auraguard.app.ui.theme.OpsCritical
import com.auraguard.app.ui.theme.OpsInfo
import com.auraguard.app.ui.theme.OpsSurface
import com.auraguard.app.ui.theme.OpsTextPrimary
import com.auraguard.app.ui.theme.OpsTextSecondary
import com.auraguard.app.ui.theme.OpsWarning

@Composable
fun LiveScreen(viewModel: AuraViewModel, onRequestScreenCapture: () -> Unit) {
    val frame by viewModel.currentFrame.collectAsState()
    val captureState by viewModel.captureState.collectAsState()
    val inputSource by viewModel.inputSource.collectAsState()
    val detectorStatus by viewModel.detectorStatus.collectAsState()
    val fps by viewModel.captureFps.collectAsState()
    val inferenceFps by viewModel.inferenceFps.collectAsState()
    val alertLevel by viewModel.alertLevel.collectAsState()
    val alertBanner by viewModel.alertBanner.collectAsState()
    val zones by viewModel.zones.collectAsState()
    val editState by viewModel.editState.collectAsState()
    val trackedObjects by viewModel.trackedObjects.collectAsState()
    val currentObjects by viewModel.currentObjectsCount.collectAsState()
    val warnings by viewModel.warningsCount.collectAsState()
    val criticals by viewModel.criticalCount.collectAsState()

    var showSaveDialog by remember { mutableStateOf(false) }
    var zoneNameInput by remember { mutableStateOf("") }

    val demoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.startDemoMode(it) }
    }

    Column(modifier = Modifier
        .fillMaxSize()
        .background(OpsBackground)) {
        AuraStatusBar(
            captureState = captureState,
            detectorStatus = detectorStatus,
            fps = fps,
            inferenceFps = inferenceFps,
            alertLevel = alertLevel
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(androidx.compose.ui.graphics.Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (frame != null) {
                val bmp = frame!!
                val ratio = bmp.width.toFloat() / bmp.height.toFloat()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .aspectRatio(ratio.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f))
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Live drone feed",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                    PerimeterOverlay(
                        zones = zones,
                        editState = editState,
                        onAddPoint = viewModel::addEditPoint,
                        onMovePoint = viewModel::moveEditPoint,
                        modifier = Modifier.fillMaxSize()
                    )
                    DetectionOverlay(objects = trackedObjects, modifier = Modifier.fillMaxSize())
                }
            } else {
                NoFeedPlaceholder(captureState, inputSource)
            }

            AlertBannerView(
                banner = alertBanner,
                onDismiss = viewModel::dismissAlertBanner,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        MiniDashboard(currentObjects, warnings, criticals, zones.size)

        if (editState.isActive) {
            PerimeterEditControls(
                canClose = editState.isClosable,
                onUndo = viewModel::undoLastEditPoint,
                onClear = viewModel::clearEditPoints,
                onCancel = viewModel::cancelDefinePerimeter,
                onSave = {
                    zoneNameInput = ""
                    showSaveDialog = true
                }
            )
        } else {
            LiveControls(
                captureActive = captureState == CaptureState.ACTIVE,
                permissionDenied = captureState == CaptureState.PERMISSION_DENIED,
                onStartCapture = onRequestScreenCapture,
                onStopCapture = viewModel::stopMonitoring,
                onDefinePerimeter = { viewModel.beginDefinePerimeter() },
                onPickDemoVideo = { demoPicker.launch("video/*") }
            )
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("SAVE PERIMETER ZONE") },
            text = {
                OutlinedTextField(
                    value = zoneNameInput,
                    onValueChange = { zoneNameInput = it },
                    label = { Text("Zone name (e.g. ALPHA)") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.saveZone(zoneNameInput)
                    showSaveDialog = false
                }) { Text("SAVE") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showSaveDialog = false }) { Text("CANCEL") }
            }
        )
    }
}

@Composable
private fun NoFeedPlaceholder(captureState: CaptureState, inputSource: InputSource) {
    val message = when (captureState) {
        CaptureState.PERMISSION_DENIED -> "Screen-capture permission was denied.\nTap START CAPTURE to try again."
        CaptureState.ERROR -> "Capture error. Tap START CAPTURE to retry, or switch to DEMO MODE."
        CaptureState.STOPPED_BY_USER -> "Screen capture was stopped from the system share bar."
        CaptureState.REQUESTING_PERMISSION -> "Waiting for screen-capture permission..."
        else -> "NO SIGNAL\n\nTap START CAPTURE to mirror the drone app's screen,\nor DEMO MODE to use a prerecorded video."
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Videocam, contentDescription = null, tint = OpsTextSecondary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(message, color = OpsTextSecondary, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun MiniDashboard(currentObjects: Int, warnings: Int, criticals: Int, zoneCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OpsSurface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        DashStat("OBJECTS", currentObjects.toString(), OpsInfo)
        DashStat("WARNINGS", warnings.toString(), OpsWarning)
        DashStat("CRITICAL", criticals.toString(), OpsCritical)
        DashStat("ZONES", zoneCount.toString(), OpsAccent)
    }
}

@Composable
private fun DashStat(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(label, color = OpsTextSecondary, fontSize = 9.sp, letterSpacing = 0.6.sp)
    }
}

@Composable
private fun LiveControls(
    captureActive: Boolean,
    permissionDenied: Boolean,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onDefinePerimeter: () -> Unit,
    onPickDemoVideo: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OpsSurface)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (captureActive) {
            OutlinedButton(onClick = onStopCapture, modifier = Modifier.weight(1f)) { Text("STOP") }
        } else {
            Button(
                onClick = onStartCapture,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = OpsAccent, contentColor = OpsBackground)
            ) { Text(if (permissionDenied) "RETRY CAPTURE" else "START CAPTURE") }
        }
        Button(
            onClick = onDefinePerimeter,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = OpsInfo, contentColor = OpsBackground)
        ) { Text("DEFINE PERIMETER") }

        OutlinedButton(onClick = onPickDemoVideo, modifier = Modifier.weight(1f)) { Text("DEMO MODE") }
    }
}

@Composable
private fun PerimeterEditControls(
    canClose: Boolean,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    Column(modifier = Modifier
        .fillMaxWidth()
        .background(OpsSurface)
        .padding(12.dp)) {
        Text(
            "DEFINE PERIMETER — tap to add corners, drag to move them",
            color = OpsTextPrimary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onUndo, modifier = Modifier.weight(1f)) { Text("UNDO") }
            OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) { Text("CLEAR") }
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("CANCEL") }
            Button(
                onClick = onSave,
                enabled = canClose,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = OpsAccent, contentColor = OpsBackground)
            ) { Text("SAVE") }
        }
    }
}

package com.auraguard.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auraguard.app.core.AuraViewModel
import com.auraguard.app.core.ProcessingRate
import com.auraguard.app.ui.theme.OpsAccent
import com.auraguard.app.ui.theme.OpsBackground
import com.auraguard.app.ui.theme.OpsBackground as Bg
import com.auraguard.app.ui.theme.OpsInfo
import com.auraguard.app.ui.theme.OpsSurface
import com.auraguard.app.ui.theme.OpsTextPrimary
import com.auraguard.app.ui.theme.OpsTextSecondary

@Composable
fun SettingsScreen(viewModel: AuraViewModel) {
    val settings by viewModel.settings.collectAsState()
    val inferenceFps by viewModel.inferenceFps.collectAsState()
    val detectorStatus by viewModel.detectorStatus.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("SETTINGS", color = OpsTextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(14.dp))

        SectionCard("AI PROCESSING RATE") {
            Text(
                "Controls how often captured frames are sent through the on-device detector. " +
                    "Higher rates track fast movement better but use more battery/CPU.",
                color = OpsTextSecondary, fontSize = 11.sp
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProcessingRate.entries.forEach { rate ->
                    RateChip(
                        label = rate.label,
                        selected = settings.processingRate == rate,
                        onClick = { viewModel.setProcessingRate(rate) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Current inference throughput: ${"%.1f".format(inferenceFps)} FPS", color = OpsInfo, fontSize = 12.sp)
        }

        SectionCard("DETECTION CONFIDENCE THRESHOLD") {
            Text("Minimum confidence for a detection to be tracked and considered for perimeter/alert logic.", color = OpsTextSecondary, fontSize = 11.sp)
            Text("${(settings.detectionConfidenceThreshold * 100).toInt()}%", color = OpsAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Slider(
                value = settings.detectionConfidenceThreshold,
                onValueChange = { viewModel.setDetectionThreshold(it) },
                valueRange = 0.2f..0.9f,
                colors = SliderDefaults.colors(thumbColor = OpsAccent, activeTrackColor = OpsAccent)
            )
        }

        SectionCard("CHANGE DETECTION THRESHOLD") {
            Text(
                "Fraction of a zone's area that must visibly change (after noise filtering) before a " +
                    "CHANGE DETECTED alert fires. Lower = more sensitive.",
                color = OpsTextSecondary, fontSize = 11.sp
            )
            Text("${(settings.changeDetectionThreshold * 100).toInt()}%", color = OpsAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Slider(
                value = settings.changeDetectionThreshold,
                onValueChange = { viewModel.setChangeThreshold(it) },
                valueRange = 0.05f..0.8f,
                colors = SliderDefaults.colors(thumbColor = OpsAccent, activeTrackColor = OpsAccent)
            )
        }

        SectionCard("ALERTS") {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Audible warning tone", color = OpsTextPrimary, fontSize = 13.sp)
                Switch(
                    checked = settings.audibleAlertsEnabled,
                    onCheckedChange = { viewModel.setAudibleAlerts(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = OpsAccent)
                )
            }
        }

        SectionCard("SYSTEM INFO") {
            InfoRow("Inference engine", detectorEngineLabel(detectorStatus))
            InfoRow("Model", viewModel.modelInfo)
            InfoRow("Offline mode", "All AI processing runs on-device. No network required.")
            InfoRow("Safety", "Human-in-the-loop only — AURA Guard never controls the drone.")
        }
    }
}

private fun detectorEngineLabel(status: com.auraguard.app.ai.DetectorStatus): String = when (status) {
    com.auraguard.app.ai.DetectorStatus.READY -> "TensorFlow Lite (on-device)"
    com.auraguard.app.ai.DetectorStatus.SIMULATED -> "Simulated (no model file bundled)"
    com.auraguard.app.ai.DetectorStatus.NO_MODEL -> "No model loaded"
    com.auraguard.app.ai.DetectorStatus.LOADING -> "Loading..."
    com.auraguard.app.ai.DetectorStatus.ERROR -> "Error"
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label.uppercase(), color = OpsTextSecondary, fontSize = 10.sp)
        Text(value, color = OpsTextPrimary, fontSize = 12.sp)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = OpsSurface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = OpsAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun RateChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = if (selected) {
            androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = OpsAccent, contentColor = OpsBackground)
        } else {
            androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = OpsTextPrimary)
        }
    ) { Text(label, fontSize = 12.sp) }
}

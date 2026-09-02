package com.auraguard.app.ui.screens.events

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auraguard.app.core.AlertLevel
import com.auraguard.app.core.AuraViewModel
import com.auraguard.app.events.AuraEvent
import com.auraguard.app.events.EventType
import com.auraguard.app.ui.theme.OpsAccent
import com.auraguard.app.ui.theme.OpsBackground
import com.auraguard.app.ui.theme.OpsCritical
import com.auraguard.app.ui.theme.OpsSurface
import com.auraguard.app.ui.theme.OpsSurfaceElevated
import com.auraguard.app.ui.theme.OpsTextPrimary
import com.auraguard.app.ui.theme.OpsTextSecondary
import com.auraguard.app.ui.theme.OpsWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EventsScreen(viewModel: AuraViewModel) {
    val events by viewModel.eventRepository.events.collectAsState()
    val context = LocalContext.current
    var selectedEvent by remember { mutableStateOf<AuraEvent?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OpsBackground)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("EVENT LOG", color = OpsTextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 1.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val uri = viewModel.exportEventLog()
                    if (uri != null) {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Export AURA Guard event log"))
                    }
                }) { Text("EXPORT", fontSize = 12.sp) }
                OutlinedButton(onClick = { showClearConfirm = true }) { Text("CLEAR", fontSize = 12.sp) }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (events.isEmpty()) {
            Text("No events recorded yet.", color = OpsTextSecondary, fontSize = 13.sp)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(events, key = { it.id }) { event ->
                    EventRow(event) { selectedEvent = event }
                }
            }
        }
    }

    selectedEvent?.let { event ->
        EventDetailDialog(event, onDismiss = { selectedEvent = null })
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear event log?") },
            text = { Text("This permanently deletes all recorded events and snapshots on this device.") },
            confirmButton = {
                Button(onClick = { viewModel.clearEventLog(); showClearConfirm = false }) { Text("CLEAR") }
            },
            dismissButton = { OutlinedButton(onClick = { showClearConfirm = false }) { Text("CANCEL") } }
        )
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
private fun EventRow(event: AuraEvent, onClick: () -> Unit) {
    val color = when (event.level) {
        AlertLevel.CRITICAL -> OpsCritical
        AlertLevel.WARNING -> OpsWarning
        AlertLevel.INFORMATION -> OpsAccent
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = OpsSurface),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(timeFormat.format(Date(event.timestampMillis)), color = OpsTextSecondary, fontSize = 11.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(event.level.name, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    if (event.zoneName != null) {
                        Spacer(Modifier.width(8.dp))
                        Text("ZONE ${event.zoneName}", color = OpsTextSecondary, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(event.message, color = OpsTextPrimary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun EventDetailDialog(event: AuraEvent, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(event.type.name.replace('_', ' ')) },
        text = {
            Column {
                Text("TIME: ${timeFormat.format(Date(event.timestampMillis))}", fontSize = 12.sp)
                event.zoneName?.let { Text("ZONE: $it", fontSize = 12.sp) }
                event.objectLabel?.let { label ->
                    Text("OBJECT: $label${event.trackId?.let { " #${it.toString().padStart(2, '0')}" } ?: ""}", fontSize = 12.sp)
                }
                event.confidence?.let { Text("CONFIDENCE: ${(it * 100).toInt()}%", fontSize = 12.sp) }
                Spacer(Modifier.height(8.dp))
                Text(event.message, fontSize = 13.sp)

                if (event.type == EventType.CHANGE_DETECTED && event.baselineSnapshotPath != null && event.snapshotPath != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SnapshotColumn("BEFORE", event.baselineSnapshotPath, Modifier.weight(1f))
                        SnapshotColumn("CURRENT", event.snapshotPath, Modifier.weight(1f))
                    }
                } else if (event.snapshotPath != null) {
                    Spacer(Modifier.height(10.dp))
                    SnapshotColumn("SNAPSHOT", event.snapshotPath, Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("CLOSE") } }
    )
}

@Composable
private fun SnapshotColumn(label: String, path: String, modifier: Modifier = Modifier) {
    val bitmap = remember(path) { runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
    Column(modifier = modifier) {
        Text(label, color = OpsTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = label,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(OpsSurfaceElevated)
            )
        } else {
            Text("(image unavailable)", color = OpsTextSecondary, fontSize = 10.sp)
        }
    }
}

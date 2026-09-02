package com.auraguard.app.events

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.auraguard.app.core.AlertLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * "Event/Alert Engine" persistence layer: every significant event is
 * recorded locally on-device only (files dir) — nothing is transmitted
 * anywhere, matching the offline-operation requirement. Snapshots are
 * saved as JPEGs alongside a JSON log so the Events screen can show
 * before/after imagery for change-detection alerts.
 */
class EventRepository(private val context: Context) {

    private val eventsDir = File(context.filesDir, "events").apply { mkdirs() }
    private val snapshotsDir = File(eventsDir, "snapshots").apply { mkdirs() }
    private val logFile = File(eventsDir, "log.json")

    private val _events = MutableStateFlow<List<AuraEvent>>(emptyList())
    val events: StateFlow<List<AuraEvent>> = _events.asStateFlow()

    init {
        _events.value = loadFromDisk()
    }

    @Synchronized
    fun addEvent(event: AuraEvent) {
        val updated = (listOf(event) + _events.value).let {
            if (it.size > MAX_EVENTS) {
                // Drop oldest beyond the cap, and their snapshot files, to bound local storage use.
                val overflow = it.drop(MAX_EVENTS)
                overflow.forEach { old -> deleteSnapshotFiles(old) }
                it.take(MAX_EVENTS)
            } else it
        }
        _events.value = updated
        persist(updated)
    }

    /** Saves a JPEG snapshot for an event and returns its absolute path, or null on failure. */
    fun saveSnapshot(bitmap: Bitmap, prefix: String = "evt"): String? {
        return try {
            val file = File(snapshotsDir, "${prefix}_${UUID.randomUUID()}.jpg")
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out) }
            file.absolutePath
        } catch (t: Throwable) {
            null
        }
    }

    @Synchronized
    fun clearAll() {
        _events.value.forEach { deleteSnapshotFiles(it) }
        _events.value = emptyList()
        persist(emptyList())
    }

    /** Writes a shareable JSON export to the app's cache and returns a FileProvider content Uri. */
    fun exportToUri(): Uri? {
        return try {
            val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(exportsDir, "aura_guard_events_${System.currentTimeMillis()}.json")
            file.writeText(serialize(_events.value).toString(2))
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (t: Throwable) {
            null
        }
    }

    private fun deleteSnapshotFiles(event: AuraEvent) {
        event.snapshotPath?.let { File(it).takeIf { f -> f.exists() }?.delete() }
        event.baselineSnapshotPath?.let { File(it).takeIf { f -> f.exists() }?.delete() }
    }

    private fun persist(events: List<AuraEvent>) {
        try {
            logFile.writeText(serialize(events).toString())
        } catch (t: Throwable) {
            // Best-effort local persistence; in-memory state is still correct for this session.
        }
    }

    private fun serialize(events: List<AuraEvent>): JSONArray {
        val arr = JSONArray()
        for (e in events) {
            val obj = JSONObject()
            obj.put("id", e.id)
            obj.put("timestampMillis", e.timestampMillis)
            obj.put("type", e.type.name)
            obj.put("level", e.level.name)
            obj.put("zoneName", e.zoneName)
            obj.put("objectLabel", e.objectLabel)
            obj.put("trackId", e.trackId)
            obj.put("confidence", e.confidence)
            obj.put("message", e.message)
            obj.put("snapshotPath", e.snapshotPath)
            obj.put("baselineSnapshotPath", e.baselineSnapshotPath)
            arr.put(obj)
        }
        return arr
    }

    private fun loadFromDisk(): List<AuraEvent> {
        if (!logFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(logFile.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                AuraEvent(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    timestampMillis = o.optLong("timestampMillis"),
                    type = runCatching { EventType.valueOf(o.optString("type")) }.getOrDefault(EventType.SYSTEM),
                    level = runCatching { AlertLevel.valueOf(o.optString("level")) }.getOrDefault(AlertLevel.INFORMATION),
                    zoneName = o.optString("zoneName", null),
                    objectLabel = o.optString("objectLabel", null),
                    trackId = if (o.isNull("trackId")) null else o.optInt("trackId"),
                    confidence = if (o.isNull("confidence")) null else o.optDouble("confidence").toFloat(),
                    message = o.optString("message"),
                    snapshotPath = o.optString("snapshotPath", null),
                    baselineSnapshotPath = o.optString("baselineSnapshotPath", null)
                )
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    companion object {
        private const val MAX_EVENTS = 500
    }
}

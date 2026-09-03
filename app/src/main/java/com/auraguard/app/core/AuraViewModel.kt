package com.auraguard.app.core

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.auraguard.app.AuraGuardApp
import com.auraguard.app.ai.DetectorProvider
import com.auraguard.app.ai.DetectorStatus
import com.auraguard.app.ai.ObjectDetector
import com.auraguard.app.alert.AlertBannerData
import com.auraguard.app.alert.AlertManager
import com.auraguard.app.capture.CaptureManager
import com.auraguard.app.capture.CaptureState
import com.auraguard.app.change.ChangeDetectionEngine
import com.auraguard.app.events.AuraEvent
import com.auraguard.app.events.EventRepository
import com.auraguard.app.events.EventType
import com.auraguard.app.perimeter.PerimeterEditState
import com.auraguard.app.perimeter.PolygonMath
import com.auraguard.app.perimeter.Zone
import com.auraguard.app.processing.FrameProcessor
import com.auraguard.app.processing.FrameRateLimiter
import com.auraguard.app.processing.InferenceFpsMeter
import com.auraguard.app.tracking.TrackedObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The pipeline orchestrator. Wires together every stage described in the
 * architecture:
 *
 *   Screen Capture -> Frame Processor -> Change Detection Engine -> Event/Alert Engine -> UI
 *
 * Detection is deliberately NOT built on a per-object AI classifier + a
 * frame-to-frame tracker with persistent IDs. Earlier revisions tried that
 * (an "AI Detector" stage producing per-object boxes, an "Object Tracker"
 * stage assigning IDs, a "Perimeter Engine" stage evaluating each tracked
 * object's distance to the zone boundary) and it was unreliable in
 * practice on real footage: a stationary real object could quietly vanish
 * from tracking, a moving background (dust, heat shimmer, exposure
 * hunting) could spawn phantom boxes, box coordinates could drift out of
 * alignment after being carried through the tracker, and a track could
 * outlive the object it was following. What's actually wanted, per spec,
 * is simpler: watch each armed zone for a *change* — something entered,
 * something moved — mark exactly where that change is, and raise the
 * alert. That's a single, well-understood, robust primitive (baseline
 * differencing with a slow-adapting reference — see
 * `change/ChangeDetectionEngine.kt`), evaluated fresh every frame with no
 * persistent identity to get out of sync — so there's no per-object ID to
 * track, no track to lose, no track to leave stuck on screen.
 *
 * Every stage is a small, independently replaceable class; this
 * ViewModel's only job is to move a frame through them in order, at the
 * configured processing rate, and publish results as observable state for
 * Compose. All processing happens on-device — nothing here calls the
 * network.
 */
class AuraViewModel(application: Application) : AndroidViewModel(application) {

    val settingsRepository: SettingsRepository =
        (application as? AuraGuardApp)?.settingsRepository ?: SettingsRepository(application)

    private val captureManager = CaptureManager(application)
    private val changeEngine = ChangeDetectionEngine()
    val alertManager = AlertManager(application)
    val eventRepository = EventRepository(application)

    // Kept only as a status/model-info probe for the UI (Settings "System Info", the top status
    // bar), purely informational — whether a trained .tflite model happens to be bundled (see
    // MODEL_SETUP.md). Detection itself no longer depends on this at all; every zone is watched by
    // [changeEngine] instead (see [handleFrame]/[evaluateZone]).
    private val detector: ObjectDetector =
        DetectorProvider.create(application) { settings.value.detectionConfidenceThreshold }

    private val rateLimiter = FrameRateLimiter(ProcessingRate.MEDIUM.targetFps)
    private val inferenceFpsMeter = InferenceFpsMeter()

    val settings: StateFlow<AppSettings> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _zones = MutableStateFlow<List<Zone>>(emptyList())
    val zones: StateFlow<List<Zone>> = _zones.asStateFlow()

    private val _editState = MutableStateFlow(PerimeterEditState())
    val editState: StateFlow<PerimeterEditState> = _editState.asStateFlow()

    private val _currentFrame = MutableStateFlow<Bitmap?>(null)
    val currentFrame: StateFlow<Bitmap?> = _currentFrame.asStateFlow()

    private val _trackedObjects = MutableStateFlow<List<TrackedObject>>(emptyList())
    val trackedObjects: StateFlow<List<TrackedObject>> = _trackedObjects.asStateFlow()

    val detectorStatus: StateFlow<DetectorStatus> = detector.status
    val modelInfo: String get() = detector.modelInfo

    private val _inferenceFps = MutableStateFlow(0f)
    val inferenceFps: StateFlow<Float> = _inferenceFps.asStateFlow()

    private val _currentObjectsCount = MutableStateFlow(0)
    val currentObjectsCount: StateFlow<Int> = _currentObjectsCount.asStateFlow()

    private val _warningsCount = MutableStateFlow(0)
    val warningsCount: StateFlow<Int> = _warningsCount.asStateFlow()

    private val _criticalCount = MutableStateFlow(0)
    val criticalCount: StateFlow<Int> = _criticalCount.asStateFlow()

    val captureState: StateFlow<CaptureState> get() = captureManager.state
    val captureFps: StateFlow<Float> get() = captureManager.fps
    val inputSource: StateFlow<InputSource> get() = captureManager.activeSource
    val alertBanner: StateFlow<AlertBannerData?> get() = alertManager.banner
    val alertLevel: StateFlow<AlertLevel> get() = alertManager.highestActiveLevel

    private val lastChangeAlertAtMs = mutableMapOf<String, Long>()

    init {
        captureManager.frames.onEach { bitmap -> handleFrame(bitmap) }.launchIn(viewModelScope)
        settings.onEach { s -> rateLimiter.targetFps = s.processingRate.targetFps }.launchIn(viewModelScope)
    }

    private suspend fun handleFrame(bitmap: Bitmap) {
        // The UI always gets every captured frame for a smooth live view...
        _currentFrame.value = bitmap

        // ...but expensive processing only runs at the configured sample rate.
        if (!rateLimiter.shouldProcess()) return

        withContext(Dispatchers.Default) {
            val armedZones = _zones.value.filter { it.armed && it.isClosed }
            val markers = mutableListOf<TrackedObject>()
            var nextMarkerId = 1

            for (zone in armedZones) {
                val marker = evaluateZone(zone, bitmap, nextMarkerId)
                if (marker != null) {
                    markers += marker
                    nextMarkerId++
                }
            }

            _trackedObjects.value = markers
            _currentObjectsCount.value = markers.size
            _inferenceFps.value = inferenceFpsMeter.tick()
        }
    }

    /**
     * Watches a single armed zone for a change against its saved baseline (see
     * `change/ChangeDetectionEngine.kt`) and, if the zone changed this frame, marks exactly where
     * and raises the CHANGE DETECTED alert (cooldown-gated so a change that's still there doesn't
     * re-alert every single frame). Returns a fresh marker for the overlay when changed, or null.
     *
     * This intentionally does not track anything across frames — no ID is carried from one call to
     * the next. Every call is a clean "does this zone differ from its baseline right now?" check,
     * so the marker on screen always matches what's actually different *this* frame: it can't drift
     * out of alignment with a moving object the way a tracked box can, and it can't outlive the
     * object that caused it — the instant the frame stops differing from baseline, there's nothing
     * to mark.
     */
    private fun evaluateZone(zone: Zone, frame: Bitmap, markerId: Int): TrackedObject? {
        val bbox = PolygonMath.boundingBox(zone.points)
        val roiRect = NormRect(bbox[0], bbox[1], bbox[2], bbox[3])
        if (roiRect.width <= 0.01f || roiRect.height <= 0.01f) {
            setZoneState(zone.id, PerimeterState.SAFE)
            return null
        }
        val roi = FrameProcessor.crop(frame, roiRect) ?: run {
            setZoneState(zone.id, PerimeterState.SAFE)
            return null
        }

        val s = settings.value
        val effectiveThreshold = (s.changeDetectionThreshold * (1f - zone.sensitivity * 0.5f)).coerceIn(0.05f, 0.95f)
        val result = changeEngine.evaluate(zone.id, roi, effectiveThreshold)

        if (!result.changed) {
            setZoneState(zone.id, PerimeterState.SAFE)
            return null
        }
        setZoneState(zone.id, PerimeterState.BREACH)

        val regionInRoi = changeEngine.changedRegionWithinRoi(result)
        val box = if (regionInRoi != null) mapRoiBoxToFrame(regionInRoi, roiRect) else roiRect

        maybeAlertChange(zone, roi, result.confidence)

        return TrackedObject(
            id = markerId,
            objectClass = ObjectClass.UNKNOWN,
            label = "CHANGE",
            confidence = result.confidence,
            box = box,
            trail = emptyList(),
            perimeterState = PerimeterState.BREACH,
            relevantZoneId = zone.id
        )
    }

    private fun setZoneState(zoneId: String, state: PerimeterState) {
        _zones.update { zones -> zones.map { if (it.id == zoneId) it.copy(currentState = state) else it } }
    }

    /** Converts a box normalized to a zone's cropped ROI back into full-frame normalized coordinates. */
    private fun mapRoiBoxToFrame(box: NormRect, roi: NormRect): NormRect = NormRect(
        left = roi.left + box.left * roi.width,
        top = roi.top + box.top * roi.height,
        right = roi.left + box.right * roi.width,
        bottom = roi.top + box.bottom * roi.height
    )

    /** Raises the CHANGE DETECTED alert for a zone, throttled so a change that persists across many frames re-alerts periodically instead of every single frame. */
    private fun maybeAlertChange(zone: Zone, roi: Bitmap, confidence: Float) {
        val now = System.currentTimeMillis()
        val last = lastChangeAlertAtMs[zone.id] ?: 0L
        if (now - last < CHANGE_ALERT_COOLDOWN_MS) return
        lastChangeAlertAtMs[zone.id] = now

        val s = settings.value
        _warningsCount.update { it + 1 }
        val currentSnapshot = eventRepository.saveSnapshot(roi, "change_current")
        val baselineSnapshot = changeEngine.getBaselineSnapshot(zone.id)
            ?.let { eventRepository.saveSnapshot(it, "change_baseline") }

        eventRepository.addEvent(
            AuraEvent(
                id = UUID.randomUUID().toString(),
                timestampMillis = now,
                type = EventType.CHANGE_DETECTED,
                level = AlertLevel.WARNING,
                zoneName = zone.name,
                confidence = confidence,
                message = "Change detected in Zone ${zone.name} — something entered or moved",
                snapshotPath = currentSnapshot,
                baselineSnapshotPath = baselineSnapshot
            )
        )
        alertManager.raise(
            level = AlertLevel.WARNING,
            title = "CHANGE DETECTED",
            subtitle = "Zone ${zone.name} · Change confidence: ${(confidence * 100).toInt()}%",
            zoneName = zone.name,
            audibleEnabled = s.audibleAlertsEnabled
        )
    }

    // ---- Perimeter definition (DEFINE PERIMETER UI) ----------------------------------------

    fun beginDefinePerimeter(existingZoneId: String? = null) {
        val existing = existingZoneId?.let { id -> _zones.value.firstOrNull { it.id == id } }
        _editState.value = PerimeterEditState(
            isActive = true,
            points = existing?.points ?: emptyList(),
            targetZoneId = existingZoneId
        )
    }

    fun addEditPoint(p: NormPoint) {
        _editState.update { it.copy(points = it.points + p) }
    }

    fun moveEditPoint(index: Int, p: NormPoint) {
        _editState.update { st ->
            if (index !in st.points.indices) st
            else st.copy(points = st.points.toMutableList().also { it[index] = p })
        }
    }

    fun undoLastEditPoint() {
        _editState.update { it.copy(points = it.points.dropLast(1)) }
    }

    fun clearEditPoints() {
        _editState.update { it.copy(points = emptyList()) }
    }

    fun cancelDefinePerimeter() {
        _editState.value = PerimeterEditState()
    }

    fun saveZone(name: String) {
        val state = _editState.value
        if (!state.isClosable) return
        val existingId = state.targetZoneId

        val zoneName = name.trim().ifBlank {
            existingId?.let { id -> _zones.value.firstOrNull { it.id == id }?.name }
                ?: Zone.DEFAULT_NAMES.getOrElse(_zones.value.size) { "ZONE-${_zones.value.size + 1}" }
        }.uppercase()

        val zone = if (existingId != null) {
            _zones.value.first { it.id == existingId }.copy(points = state.points, name = zoneName)
        } else {
            Zone(name = zoneName, points = state.points, color = Zone.PALETTE[_zones.value.size % Zone.PALETTE.size])
        }

        _zones.value = if (existingId != null) {
            _zones.value.map { if (it.id == existingId) zone else it }
        } else {
            _zones.value + zone
        }

        // Establish (or refresh) the change-detection baseline for this zone's ROI right away.
        _currentFrame.value?.let { frame ->
            val bbox = PolygonMath.boundingBox(zone.points)
            FrameProcessor.crop(frame, NormRect(bbox[0], bbox[1], bbox[2], bbox[3]))?.let {
                changeEngine.setBaseline(zone.id, it)
            }
        }

        eventRepository.addEvent(
            AuraEvent(
                id = UUID.randomUUID().toString(),
                timestampMillis = System.currentTimeMillis(),
                type = EventType.SYSTEM,
                level = AlertLevel.INFORMATION,
                zoneName = zone.name,
                message = "Zone ${zone.name} perimeter ${if (existingId != null) "updated" else "defined"}"
            )
        )
        _editState.value = PerimeterEditState()
    }

    // ---- Zone management (ZONES screen) -----------------------------------------------------

    fun setZoneArmed(zoneId: String, armed: Boolean) {
        _zones.update { zones ->
            zones.map {
                if (it.id != zoneId) it
                // A disarmed zone is no longer evaluated each frame, so nothing will ever flip its
                // currentState back to SAFE on its own — reset it here so a zone that happened to be
                // showing a change right when it was disarmed doesn't stay tinted red forever.
                else it.copy(armed = armed, currentState = if (armed) it.currentState else PerimeterState.SAFE)
            }
        }
    }

    fun setZoneSensitivity(zoneId: String, sensitivity: Float) {
        _zones.update { zones -> zones.map { if (it.id == zoneId) it.copy(sensitivity = sensitivity.coerceIn(0f, 1f)) else it } }
    }

    fun deleteZone(zoneId: String) {
        _zones.update { zones -> zones.filterNot { it.id == zoneId } }
        changeEngine.clearZone(zoneId)
        lastChangeAlertAtMs.remove(zoneId)
    }

    fun resetZoneBaseline(zoneId: String) {
        val zone = _zones.value.firstOrNull { it.id == zoneId } ?: return
        val frame = _currentFrame.value ?: return
        val bbox = PolygonMath.boundingBox(zone.points)
        FrameProcessor.crop(frame, NormRect(bbox[0], bbox[1], bbox[2], bbox[3]))?.let {
            changeEngine.setBaseline(zoneId, it)
        }
    }

    fun getChangeBaselineSnapshot(zoneId: String): Bitmap? = changeEngine.getBaselineSnapshot(zoneId)

    // ---- Capture control (LIVE screen) ------------------------------------------------------

    fun requestScreenCapture() = captureManager.requestScreenCapture()

    fun onScreenCapturePermissionResult(resultCode: Int, data: Intent?) =
        captureManager.onScreenCapturePermission(resultCode, data)

    fun startDemoMode(uri: Uri) {
        viewModelScope.launch {
            settingsRepository.setDemoMode(true)
            settingsRepository.setDemoVideoUri(uri.toString())
        }
        captureManager.startDemo(uri)
    }

    fun stopMonitoring() {
        captureManager.stopAll()
        _trackedObjects.value = emptyList()
        _currentFrame.value = null
    }

    fun dismissAlertBanner() = alertManager.dismissBanner()

    // ---- Settings ----------------------------------------------------------------------------

    fun setProcessingRate(rate: ProcessingRate) = viewModelScope.launch { settingsRepository.setProcessingRate(rate) }
    fun setDetectionThreshold(v: Float) = viewModelScope.launch { settingsRepository.setDetectionThreshold(v) }
    fun setChangeThreshold(v: Float) = viewModelScope.launch { settingsRepository.setChangeThreshold(v) }
    fun setAudibleAlerts(enabled: Boolean) = viewModelScope.launch { settingsRepository.setAudibleAlerts(enabled) }

    // ---- Event log -----------------------------------------------------------------------------

    fun clearEventLog() = eventRepository.clearAll()
    fun exportEventLog(): Uri? = eventRepository.exportToUri()

    override fun onCleared() {
        super.onCleared()
        detector.close()
        alertManager.release()
        captureManager.stopAll()
    }

    companion object {
        private const val CHANGE_ALERT_COOLDOWN_MS = 8000L
    }
}

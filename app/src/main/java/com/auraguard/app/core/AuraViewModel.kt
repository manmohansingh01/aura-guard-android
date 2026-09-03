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
import com.auraguard.app.perimeter.PerimeterEngine
import com.auraguard.app.perimeter.PolygonMath
import com.auraguard.app.perimeter.Zone
import com.auraguard.app.processing.FrameProcessor
import com.auraguard.app.processing.FrameRateLimiter
import com.auraguard.app.processing.InferenceFpsMeter
import com.auraguard.app.tracking.CentroidTracker
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
 *   Screen Capture -> Frame Processor -> AI Detector -> Object Tracker ->
 *   Perimeter Engine -> Change Detection Engine -> Event/Alert Engine -> UI
 *
 * Every stage is a small, independently replaceable class; this
 * ViewModel's only job is to move a frame through them in order, at the
 * configured processing rate, and publish results as observable state for
 * Compose. All processing happens on-device — nothing here calls the
 * network.
 */
class AuraViewModel(application: Application) : AndroidViewModel(application) {

    // Stored explicitly (rather than relying on the inherited getApplication()) so it's usable
    // from ordinary member functions below, not just class-body property initializers.
    private val appContext: Application = application

    val settingsRepository: SettingsRepository =
        (application as? AuraGuardApp)?.settingsRepository ?: SettingsRepository(application)

    private val captureManager = CaptureManager(application)
    private val tracker = CentroidTracker()
    private val perimeterEngine = PerimeterEngine()
    private val changeEngine = ChangeDetectionEngine()
    val alertManager = AlertManager(application)
    val eventRepository = EventRepository(application)

    // Kept only as a status/model-info probe for the UI (Settings "System Info", the top status
    // bar) — actual detection no longer runs on this instance; see [detectorFor] and
    // [detectWithinZones] below for why detection is scoped per-zone instead of one shared engine.
    private val detector: ObjectDetector =
        DetectorProvider.create(application) { settings.value.detectionConfidenceThreshold }

    // One detector instance per armed zone, so detection runs only on that zone's cropped region.
    // A stateful engine (MotionDetector maintains a background reference) needs its own instance
    // per zone — feeding it alternating crops from different zones would make every zone look like
    // constant motion relative to the last, and its grid resolution is sized to that zone's crop
    // (see [detectorFor]) so a tightly-drawn zone isn't sampled at full-frame detail. Created
    // lazily, closed when a zone is removed.
    private val zoneDetectors = mutableMapOf<String, ObjectDetector>()

    private fun detectorFor(zoneId: String, roiRect: NormRect): ObjectDetector =
        zoneDetectors.getOrPut(zoneId) {
            val roiScale = kotlin.math.sqrt((roiRect.width * roiRect.height).coerceAtLeast(0f))
            DetectorProvider.create(appContext, roiScale = roiScale) { settings.value.detectionConfidenceThreshold }
        }

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

    // Last per-track perimeter state, to detect SAFE -> APPROACHING -> BREACH transitions
    // instead of re-alerting every single frame an object stays put.
    private val lastObjectPerimeterState = mutableMapOf<Int, PerimeterState>()
    private val lastChangeAlertAtMs = mutableMapOf<String, Long>()

    init {
        captureManager.frames.onEach { bitmap -> handleFrame(bitmap) }.launchIn(viewModelScope)
        settings.onEach { s -> rateLimiter.targetFps = s.processingRate.targetFps }.launchIn(viewModelScope)
    }

    private suspend fun handleFrame(bitmap: Bitmap) {
        // The UI always gets every captured frame for a smooth live view...
        _currentFrame.value = bitmap

        // ...but expensive AI inference only runs at the configured sample rate.
        if (!rateLimiter.shouldProcess()) return

        withContext(Dispatchers.Default) {
            val threshold = settings.value.detectionConfidenceThreshold
            val armedZones = _zones.value.filter { it.armed && it.isClosed }
            val detections = detectWithinZones(bitmap, armedZones, threshold)
            val tracked = tracker.update(detections)

            val evaluation = perimeterEngine.evaluate(_zones.value, tracked)
            val annotated = tracked.map { obj ->
                val (state, zoneId) = evaluation.objectStates[obj.id] ?: (PerimeterState.SAFE to null)
                obj.copy(perimeterState = state, relevantZoneId = zoneId)
            }
            _trackedObjects.value = annotated
            _currentObjectsCount.value = annotated.size

            _zones.update { zones ->
                zones.map { z -> z.copy(currentState = evaluation.zoneStates[z.id] ?: PerimeterState.SAFE) }
            }

            for (obj in annotated) {
                val prev = lastObjectPerimeterState[obj.id] ?: PerimeterState.SAFE
                if (obj.perimeterState != prev) {
                    onPerimeterTransition(obj, prev, obj.perimeterState, bitmap)
                }
                lastObjectPerimeterState[obj.id] = obj.perimeterState
            }
            lastObjectPerimeterState.keys.retainAll(annotated.map { it.id }.toSet())

            _inferenceFps.value = inferenceFpsMeter.tick()

            runChangeDetection(bitmap)
        }
    }

    /**
     * Runs the AI Detector stage ONLY inside each armed zone's rectangular bounding box — never
     * across the whole frame. This is what makes detection "work inside the zone and not before":
     * an object standing anywhere outside every defined zone is never fed to the detector at all,
     * so it can't produce a box, a track, a trail, or a count — there's simply nothing to report
     * until the object is within a zone's region. With no armed zone defined yet, this returns no
     * detections at all (nothing to scope detection to).
     *
     * Each zone gets its own [detectorFor] instance rather than sharing one across zones/the whole
     * frame, since a stateful engine (MotionDetector) rolls a reference frame forward — reusing one
     * instance across two different zones' crops would make each new zone's first frame look like
     * violent motion relative to the previous zone's content.
     */
    private fun detectWithinZones(frame: Bitmap, armedZones: List<Zone>, threshold: Float): List<Detection> {
        if (armedZones.isEmpty()) return emptyList()
        val results = mutableListOf<Detection>()
        for (zone in armedZones) {
            val bbox = PolygonMath.boundingBox(zone.points)
            val roiRect = NormRect(bbox[0], bbox[1], bbox[2], bbox[3])
            if (roiRect.width <= 0.01f || roiRect.height <= 0.01f) continue
            val roi = FrameProcessor.crop(frame, roiRect) ?: continue
            val zoneDetections = detectorFor(zone.id, roiRect).detect(roi).filter { it.confidence >= threshold }
            for (d in zoneDetections) {
                results += d.copy(box = mapRoiBoxToFrame(d.box, roiRect))
            }
        }
        return results
    }

    /** Converts a detection box normalized to a zone's cropped ROI back into full-frame normalized coordinates. */
    private fun mapRoiBoxToFrame(box: NormRect, roi: NormRect): NormRect = NormRect(
        left = roi.left + box.left * roi.width,
        top = roi.top + box.top * roi.height,
        right = roi.left + box.right * roi.width,
        bottom = roi.top + box.bottom * roi.height
    )

    private fun onPerimeterTransition(obj: TrackedObject, prev: PerimeterState, new: PerimeterState, frame: Bitmap) {
        val zoneName = obj.relevantZoneId?.let { id -> _zones.value.firstOrNull { it.id == id }?.name }
        // Only a classified PERSON/vehicle can raise the loud "PERIMETER BREACH"/"APPROACHING" siren.
        // The current detector engine reports a class only when a real trained model is loaded
        // (DetectorStatus.READY); the built-in MOTION CV fallback has no way to tell a person from a
        // swaying curtain or a bag, so its detections are always ObjectClass.UNKNOWN. Alerting on
        // every unclassified motion blob is exactly the false-alarm spam this guards against — those
        // are still logged to the event log below (quietly, as OBJECT_DETECTED/INFORMATION) so nothing
        // is silently dropped, but they never trigger the banner, tone, or vibration.
        val isClassified = obj.objectClass != ObjectClass.UNKNOWN
        when (new) {
            PerimeterState.BREACH -> {
                if (!isClassified) {
                    eventRepository.addEvent(
                        AuraEvent(
                            id = UUID.randomUUID().toString(),
                            timestampMillis = System.currentTimeMillis(),
                            type = EventType.OBJECT_DETECTED,
                            level = AlertLevel.INFORMATION,
                            zoneName = zoneName,
                            objectLabel = obj.label,
                            trackId = obj.id,
                            confidence = obj.confidence,
                            message = "Unclassified motion entered Zone ${zoneName ?: "UNKNOWN"} " +
                                "(no trained model loaded — cannot confirm person/vehicle, see MODEL_SETUP.md)"
                        )
                    )
                    return
                }
                _criticalCount.update { it + 1 }
                val snapshotPath = eventRepository.saveSnapshot(FrameProcessor.thumbnail(frame), "breach")
                eventRepository.addEvent(
                    AuraEvent(
                        id = UUID.randomUUID().toString(),
                        timestampMillis = System.currentTimeMillis(),
                        type = EventType.BREACH,
                        level = AlertLevel.CRITICAL,
                        zoneName = zoneName,
                        objectLabel = obj.label,
                        trackId = obj.id,
                        confidence = obj.confidence,
                        message = "${obj.displayName} entered Zone ${zoneName ?: "UNKNOWN"}",
                        snapshotPath = snapshotPath
                    )
                )
                alertManager.raise(
                    level = AlertLevel.CRITICAL,
                    title = "PERIMETER BREACH",
                    subtitle = "${obj.displayName} · Confidence: ${(obj.confidence * 100).toInt()}%",
                    zoneName = zoneName,
                    audibleEnabled = settings.value.audibleAlertsEnabled
                )
            }
            PerimeterState.APPROACHING -> {
                if (prev == PerimeterState.SAFE && isClassified) {
                    _warningsCount.update { it + 1 }
                    eventRepository.addEvent(
                        AuraEvent(
                            id = UUID.randomUUID().toString(),
                            timestampMillis = System.currentTimeMillis(),
                            type = EventType.APPROACHING,
                            level = AlertLevel.WARNING,
                            zoneName = zoneName,
                            objectLabel = obj.label,
                            trackId = obj.id,
                            confidence = obj.confidence,
                            message = "${obj.displayName} approaching Zone ${zoneName ?: "UNKNOWN"}"
                        )
                    )
                    alertManager.raise(
                        level = AlertLevel.WARNING,
                        title = "OBJECT APPROACHING PERIMETER",
                        subtitle = "${obj.displayName} · Confidence: ${(obj.confidence * 100).toInt()}%",
                        zoneName = zoneName,
                        audibleEnabled = settings.value.audibleAlertsEnabled
                    )
                }
            }
            PerimeterState.SAFE -> {
                if (prev != PerimeterState.SAFE) {
                    eventRepository.addEvent(
                        AuraEvent(
                            id = UUID.randomUUID().toString(),
                            timestampMillis = System.currentTimeMillis(),
                            type = EventType.OBJECT_DETECTED,
                            level = AlertLevel.INFORMATION,
                            zoneName = zoneName,
                            objectLabel = obj.label,
                            trackId = obj.id,
                            confidence = obj.confidence,
                            message = "${obj.displayName} left the perimeter area"
                        )
                    )
                }
            }
        }
    }

    private fun runChangeDetection(frame: Bitmap) {
        val s = settings.value
        for (zone in _zones.value) {
            if (!zone.armed || !zone.isClosed) continue
            val bbox = PolygonMath.boundingBox(zone.points)
            val roiRect = NormRect(bbox[0], bbox[1], bbox[2], bbox[3])
            if (roiRect.width <= 0.01f || roiRect.height <= 0.01f) continue
            val roi = FrameProcessor.crop(frame, roiRect) ?: continue

            val effectiveThreshold = (s.changeDetectionThreshold * (1f - zone.sensitivity * 0.5f)).coerceIn(0.05f, 0.95f)
            val result = changeEngine.evaluate(zone.id, roi, effectiveThreshold)
            if (!result.changed) continue

            val now = System.currentTimeMillis()
            val last = lastChangeAlertAtMs[zone.id] ?: 0L
            if (now - last < CHANGE_ALERT_COOLDOWN_MS) continue
            lastChangeAlertAtMs[zone.id] = now

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
                    confidence = result.confidence,
                    message = "Significant visual change detected in Zone ${zone.name}",
                    snapshotPath = currentSnapshot,
                    baselineSnapshotPath = baselineSnapshot
                )
            )
            alertManager.raise(
                level = AlertLevel.WARNING,
                title = "CHANGE DETECTED",
                subtitle = "Zone ${zone.name} · Change confidence: ${(result.confidence * 100).toInt()}%",
                zoneName = zone.name,
                audibleEnabled = s.audibleAlertsEnabled
            )
        }
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
        _zones.update { zones -> zones.map { if (it.id == zoneId) it.copy(armed = armed) else it } }
        // Disarmed zones are never fed to detectWithinZones any more, so their detector instance
        // (and, for MotionDetector, its rolling reference frame) is stale the moment it's unused —
        // drop it now rather than leave it idle; re-arming lazily creates a fresh one via detectorFor.
        if (!armed) zoneDetectors.remove(zoneId)?.close()
    }

    fun setZoneSensitivity(zoneId: String, sensitivity: Float) {
        _zones.update { zones -> zones.map { if (it.id == zoneId) it.copy(sensitivity = sensitivity.coerceIn(0f, 1f)) else it } }
    }

    fun deleteZone(zoneId: String) {
        _zones.update { zones -> zones.filterNot { it.id == zoneId } }
        changeEngine.clearZone(zoneId)
        lastChangeAlertAtMs.remove(zoneId)
        zoneDetectors.remove(zoneId)?.close()
    }

    fun resetZoneBaseline(zoneId: String) {
        val zone = _zones.value.firstOrNull { it.id == zoneId } ?: return
        val frame = _currentFrame.value ?: return
        val bbox = PolygonMath.boundingBox(zone.points)
        FrameProcessor.crop(frame, NormRect(bbox[0], bbox[1], bbox[2], bbox[3]))?.let {
            changeEngine.setBaseline(zoneId, it)
        }
        // Also drop this zone's object detector so it re-calibrates from scratch on the next few
        // frames. MotionDetector treats whatever it first sees as "empty zone" — if the zone was
        // armed while something was already standing in it, that thing would otherwise be baked
        // into the background forever and never get flagged. Resetting the baseline is the
        // operator's way of saying "the zone is clear right now," so it should resync both engines.
        zoneDetectors.remove(zoneId)?.close()
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
        tracker.reset()
        _trackedObjects.value = emptyList()
        _currentFrame.value = null
        lastObjectPerimeterState.clear()
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
        zoneDetectors.values.forEach { it.close() }
        zoneDetectors.clear()
        alertManager.release()
        captureManager.stopAll()
    }

    companion object {
        private const val CHANGE_ALERT_COOLDOWN_MS = 8000L
    }
}

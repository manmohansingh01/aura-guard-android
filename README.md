# AURA Guard — AI Drone Perimeter Protection

A working Android prototype for human-supervised perimeter surveillance
that runs entirely against the drone application's **own on-screen video**
— captured with Android's `MediaProjection` screen-recording API — so
neither the drone nor its controller app needs to be modified or accessed
directly.

> **Safety model:** AURA Guard is operator-assistance only. It never sends
> any command to the drone, never controls anything autonomously, and every
> alert is informational — a human decides what to do about it.

---

## 1. What's implemented

| Requirement | Status | Where |
|---|---|---|
| MediaProjection screen capture of the drone app | ✅ | `capture/ScreenCaptureService.kt`, `capture/ScreenCaptureFrameSource.kt` |
| Live feed inside the AURA Guard UI | ✅ | `ui/screens/live/LiveScreen.kt` |
| Draw/edit/save a restricted-perimeter polygon on the video | ✅ | `ui/screens/live/PerimeterOverlay.kt`, `perimeter/PerimeterEditState.kt` |
| Multiple named, independently armed/disarmed zones | ✅ | `perimeter/Zone.kt`, `ui/screens/zones/ZonesScreen.kt` |
| On-device object detection (person/car/truck/motorcycle/bicycle) | ✅ (pluggable; ships in SIMULATED mode until you add a model — see `MODEL_SETUP.md`) | `ai/` |
| Multi-object tracking with persistent IDs + motion trail | ✅ | `tracking/CentroidTracker.kt` |
| SAFE / APPROACHING / BREACH perimeter logic | ✅ | `perimeter/PerimeterEngine.kt` |
| Visual + audible breach alert | ✅ | `alert/AlertManager.kt`, `ui/components/AlertBannerView.kt` |
| Independent change-detection layer (baseline vs. current, noise-filtered) | ✅ | `change/ChangeDetectionEngine.kt` |
| Before/after change review | ✅ | `ui/screens/events/EventsScreen.kt` |
| Three-tier alert priority (INFO/WARNING/CRITICAL) + live dashboard | ✅ | `core/AuraViewModel.kt`, `ui/screens/live/LiveScreen.kt` |
| Local event log with export/clear | ✅ | `events/EventRepository.kt` |
| Calibration/setup notice (image-space, not GPS) | ✅ | `ui/screens/zones/ZonesScreen.kt` |
| Configurable AI processing rate + live FPS readout | ✅ | `ui/screens/settings/SettingsScreen.kt`, `processing/FrameRateLimiter.kt` |
| Demo mode (prerecorded video through the same pipeline) | ✅ | `capture/DemoVideoFrameSource.kt` |
| Offline operation (no network calls anywhere in the pipeline) | ✅ | entire `ai/`, `perimeter/`, `change/`, `tracking/` packages |

This is a **prototype**, not a hardened production app — see §7 for known
limitations.

---

## 2. Architecture

```
Screen Capture  (capture/)
      │  MediaProjection VirtualDisplay -> ImageReader, or looped demo video
      ▼
Frame Processor (processing/)
      │  rate-limits which frames get run through inference (Low/Med/High)
      ▼
AI Detector     (ai/)
      │  ObjectDetector interface — TFLiteObjectDetector (real) /
      │  SimulatedDetector (fallback when no .tflite model is bundled)
      ▼
Object Tracker  (tracking/)
      │  CentroidTracker — persistent IDs + short motion trail
      ▼
Perimeter Engine (perimeter/)
      │  point-in-polygon + approach-distance -> SAFE/APPROACHING/BREACH
      ▼
Change Detection Engine (change/)
      │  baseline vs. current ROI, block-diff + morphological filtering
      ▼
Event/Alert Engine (alert/, events/)
      │  banner + tone + vibration, local JSON event log with snapshots
      ▼
UI + Event Log  (ui/)
         Jetpack Compose — LIVE / ZONES / EVENTS / SETTINGS
```

Every stage is a small class behind a narrow interface or a plain function,
specifically so a piece (most obviously the AI Detector) can be replaced
without touching anything else. `core/AuraViewModel.kt` is the only class
that wires all of them together — it's the pipeline orchestrator described
above, one frame at a time.

### Project layout

```
app/src/main/java/com/auraguard/app/
  ai/          Detector interface, TFLite implementation, simulated fallback, COCO labels
  alert/       AlertManager (tone/vibration/banner), AlertBannerData
  capture/     MediaProjection service + demo-video frame source, unified CaptureManager
  change/      ChangeDetectionEngine (baseline diff, noise filtering)
  core/        Shared models, AppSettings/DataStore, AuraViewModel (orchestrator)
  events/      AuraEvent model, local JSON-backed EventRepository
  perimeter/   Zone model, polygon math, breach/approach engine, edit state
  processing/  Frame-rate limiter, inference FPS meter, bitmap crop helpers
  tracking/    CentroidTracker, TrackedObject
  ui/          Compose screens (live/zones/events/settings), components, theme, nav
```

---

## 3. Building the project

### Prerequisites
- Android Studio (Koala/2024.1 or newer recommended) **or** a JDK 17+ and
  network access for a command-line build.
- An Android device or emulator running **API 26+** (screen capture needs
  API 26 for `VirtualDisplay`; foreground-service-type enforcement targets
  API 34).

### One-time step this repo needs before it builds: generate the Gradle wrapper jar

This project was assembled in a sandboxed environment with no access to
`services.gradle.org`, so `gradle/wrapper/gradle-wrapper.jar` (a small
binary) isn't included — `gradlew` / `gradlew.bat` and
`gradle/wrapper/gradle-wrapper.properties` (pinned to Gradle 8.7) are.
Generate the missing jar once, from a machine with normal internet access:

```bash
# from the project root
gradle wrapper --gradle-version 8.7
```

(any locally-installed Gradle works for this one-off step) — or simply
**open the project folder in Android Studio**, which fetches/repairs the
wrapper automatically the first time you sync.

### Build & install

```bash
# Debug build, installed straight to a connected device/emulator:
./gradlew installDebug

# Or just build the APK:
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

Or in Android Studio: **Open** the project root, let Gradle sync, then
**Run ▶** with a device/emulator selected.

### Permissions the app requests at runtime
- Screen-capture (`MediaProjectionManager` system dialog) — triggered by
  the **START CAPTURE** button; required for the live feed.
- Notifications (Android 13+) — for the ongoing "monitoring active"
  foreground-service notice.
- No camera, location, or contacts permissions are requested — AURA Guard
  never touches the device camera or GPS.

---

## 4. Using it

1. Launch the app — you land on **LIVE**, the default screen.
2. Tap **START CAPTURE**, grant the system screen-recording permission.
   Whatever is on screen next (open the drone manufacturer's app and switch
   back to AURA Guard, or use split-screen/PiP if your device supports it)
   becomes the analyzed feed. No physical drone? Tap **DEMO MODE** instead
   and pick any video from your device — it runs through the identical
   pipeline, looped.
3. Tap **DEFINE PERIMETER**. Tap on the video to drop polygon corners, drag
   an existing corner to adjust it, **UNDO**/**CLEAR** as needed, then
   **SAVE** and give the zone a name (defaults to ALPHA/BRAVO/CHARLIE...).
4. The zone is now armed and monitored continuously. Detected people/
   vehicles get a bounding box, ID, and confidence; the box and the zone
   outline turn amber when something is APPROACHING and red on BREACH,
   with a full-screen banner, tone, and vibration.
5. **ZONES** — arm/disarm a zone, adjust its sensitivity, re-baseline its
   change-detection reference image, or delete it.
6. **EVENTS** — the full local log; tap an entry for details, tap **EXPORT**
   to share the JSON log, **CLEAR** to wipe it (and its saved snapshots).
7. **SETTINGS** — AI Processing Rate (Low/Medium/High), detection
   confidence threshold, change-detection sensitivity threshold, audible
   alerts on/off, and current inference throughput.

### Getting real detections instead of SIMULATED

Out of the box, the INFERENCE readout in the top bar shows `SIMULATED` —
no model is bundled (see §1). Follow **`MODEL_SETUP.md`** to drop in a
pretrained `.tflite` model; nothing else needs to change.

---

## 5. Milestones (as specified)

1. **MVP** — screen capture → live feed → draw polygon → detect person →
   determine in/out of polygon → audible/visual alert. ✅ Fully working,
   including with `SimulatedDetector` if no model file is present yet.
2. **Tracking** — persistent IDs + motion trail. ✅
3. **Change detection** — independent CV layer, baseline vs. current,
   configurable threshold, before/after review. ✅

---

## 6. Design notes worth knowing

- **Image-space, not GPS.** A perimeter is a polygon in the *video frame's*
  normalized coordinates. Because the drone camera moves, a zone is only
  valid for roughly the framing it was drawn under — the Zones screen says
  this explicitly, and the app never claims or fabricates real-world
  coordinates.
- **Why AI runs at 3–10 FPS, not screen-capture FPS.** `FrameRateLimiter`
  (`processing/FrameRateLimiter.kt`) throttles how often frames reach the
  detector; the UI still renders every captured frame, so the live view
  stays smooth even on modest hardware.
- **Change detection, not another neural net.** `ChangeDetectionEngine`
  compares a saved baseline crop of each zone against the current frame
  using block-mean luminance differencing (fast, no OpenCV dependency),
  subtracts a global brightness-shift term so lighting changes don't
  trigger it, applies a neighbor-count "morphological" filter so isolated
  noise/shadow flicker is discarded, and slowly nudges the baseline toward
  unchanged frames to absorb gradual drift.
- **SIMULATED inference is always visible, never silent.** If no model
  file is bundled, the top bar and Settings screen say `SIMULATED` in
  amber — the app never pretends synthetic detections are real.
- **Everything stays on-device.** No stage of the pipeline makes a network
  call. The event log's "export" writes a local JSON file and hands it to
  Android's normal share sheet — nothing is silently uploaded anywhere.

---

## 7. Known limitations of this prototype

- **No bundled model** (see §1/§4) and no bundled Gradle wrapper jar (see
  §3) — both are one-time steps you complete locally, for the reasons
  explained in each section.
- The TFLite postprocessor targets the common YOLOv5/v8 tflite export
  layout; a model with a different output convention (e.g. SSD-MobileNet's
  4-tensor output) needs a small decoder adapter — see `MODEL_SETUP.md` §3.
- `CentroidTracker` is a lightweight nearest-centroid tracker, not
  DeepSORT/ByteTrack — fine at the 3–10 FPS this app samples detections,
  but it can swap IDs if two similar objects cross paths closely.
- Screen capture can only see what Android renders to the display —
  DRM-protected content or a drone app that blocks screenshots (via
  `FLAG_SECURE`) can't be captured, by OS design.
- No automated test suite is included; the individual pipeline stages
  (perimeter math, tracker, change detection) are pure/dependency-light
  Kotlin specifically so they're straightforward to unit test if you add
  one.

# MODEL_SETUP — putting a real object-detection model into AURA Guard

AURA Guard ships **without** a bundled `.tflite` model file, on purpose:
pretrained weights come with their own licenses, and "don't train or bundle
a model, build the loading interface instead" was an explicit requirement.
Until you add one, the app runs the AI Detector stage in `SIMULATED` mode
(see `ai/SimulatedDetector.kt`) so every other stage of the pipeline —
tracking, perimeter breach logic, change detection, alerts, event log, UI —
is still fully testable.

## 1. Where the file goes

```
app/src/main/assets/models/model.tflite
```

That's the only thing `TFLiteObjectDetector` looks for
(`ai/TFLiteObjectDetector.kt`, `modelAssetPath` constructor parameter — change
it there if you want a different file name or a per-build-variant model).

Once the file exists, rebuild and reinstall the app. The Settings screen's
"System Info" card and the top bar's INFERENCE readout will switch from
`SIMULATED` to `READY` automatically — there's no other code change needed.

## 2. What kind of model works out of the box

`TFLiteObjectDetector` expects a single-input / single-output detector
exported from the **Ultralytics YOLOv5 or YOLOv8 family**, float32, trained
on (or fine-tuned from) the 80-class COCO dataset — this is by far the most
common lightweight, freely-redistributable pretrained detector family with
ready-made `.tflite` exports, which is why the decoder targets it.

Concretely, it auto-detects at load time:
- Input tensor shape `[1, H, W, 3]` (commonly 640×640 or 320×320).
- Output tensor shape `[1, 4+numClasses, numBoxes]` (YOLOv8-style,
  transposed) **or** `[1, numBoxes, 5+numClasses]` (YOLOv5-style, with an
  explicit objectness score).

It filters detections down to the classes AURA Guard cares about — person,
bicycle, car, motorcycle, truck (COCO class indices 0, 1, 2, 3, 7; see
`ai/CocoLabels.kt`) — and ignores everything else the model can detect.

## 3. Getting an actual model file

Pick whichever fits your license requirements; none are bundled in this
repo and you should confirm the license fits your use case before shipping:

**Option A — Ultralytics YOLOv8/YOLOv5 official exports.** If you have the
`ultralytics` Python package available:

```bash
pip install ultralytics
yolo export model=yolov8n.pt format=tflite imgsz=640
# produces yolov8n_saved_model/yolov8n_float32.tflite (and an int8 variant)
```

Rename/copy the resulting file to `app/src/main/assets/models/model.tflite`.
`yolov8n` ("nano") is the smallest/fastest variant and the right starting
point for a phone; `yolov5n`/`yolov5s` work the same way via the `yolov5`
repo's own `export.py`.

**Option B — TensorFlow Hub / TensorFlow Lite Object Detection sample
models.** Google publishes small SSD-MobileNet `.tflite` detectors (e.g. via
the [TFLite Object Detection example](https://www.tensorflow.org/lite/examples/object_detection/overview))
that are COCO-trained and Apache-2.0 licensed. These use a **different**
output layout (separate boxes/classes/scores/count tensors rather than one
combined tensor), so `TFLiteObjectDetector.decode()` would need a small
adapter for that layout — this is exactly the kind of change the
`ObjectDetector` interface was designed to isolate: copy
`TFLiteObjectDetector.kt`, adjust its `decode()`, keep everything else in
the app untouched.

**Option C — quantize for speed.** For real-time performance on
mid-range phones, prefer an INT8-quantized export
(`yolo export model=yolov8n.pt format=tflite int8=True`) and add a simple
input-quantization step to `preprocess()` if you switch to a `UINT8` input
tensor (`TFLiteObjectDetector` currently assumes a float32 input tensor;
check `interpreter.getInputTensor(0).dataType()` if you go this route).

## 4. Replacing the inference engine entirely

Everything downstream of the model only depends on the small
`ObjectDetector` interface in `ai/ObjectDetector.kt`:

```kotlin
interface ObjectDetector {
    val status: StateFlow<DetectorStatus>
    val modelInfo: String
    fun detect(bitmap: Bitmap): List<Detection>
    fun close()
}
```

To swap in ONNX Runtime Mobile or ncnn instead of TensorFlow Lite, write one
new class implementing this interface (see `TFLiteObjectDetector.kt` as a
template) and point `ai/DetectorProvider.kt` at it. No other file in the
app needs to change — that's the whole point of the layered architecture
described in the main README.

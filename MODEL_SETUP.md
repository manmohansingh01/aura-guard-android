# MODEL_SETUP — putting a real object-detection model into AURA Guard

AURA Guard ships **without** a bundled `.tflite` model file, on purpose — pretrained (and
fine-tuned) weights come with their own licenses you need to knowingly accept, and "don't train or
bundle a model, build the loading interface instead" was an explicit requirement. Until you add
one, the fallback (`ai/MotionDetector.kt`, real background-subtraction motion CV, not scripted)
covers the status/model-info probe and the app runs perfectly well without any model at all.

## What the model is actually used for

Zone watching itself does **not** depend on this model. Every armed zone is watched by
`change/ChangeDetectionEngine.kt` — baseline differencing against a saved reference, with no
tracking or persistent object identity (see the class doc on `AuraViewModel.evaluateZone`). That
alone is what detects "something entered or moved" and raises the CHANGE DETECTED alert.

A bundled model is a **one-shot enrichment step**, not the primary pipeline: when the change engine
already flags a zone as changed this frame, `AuraViewModel.classifyChange()` runs the model once
on that changed region to answer "what does this look like" — turning a generic "CHANGE DETECTED"
alert into a labeled one ("PERSON DETECTED", "CAR DETECTED", etc.) when the model is confident.
Nothing is tracked or carried across frames; with no model bundled, everything works exactly the
same as before, just with the generic label.

## 1. Where the file goes

```
app/src/main/assets/models/model.tflite
```

That's the only thing `TFLiteObjectDetector` looks for
(`ai/TFLiteObjectDetector.kt`, `modelAssetPath` constructor parameter — change
it there if you want a different file name or a per-build-variant model).

Once the file exists, rebuild and reinstall the app. The Settings screen's
"System Info" card and the top bar's INFERENCE readout will switch from
`NO_MODEL`/`MOTION_CV` to `READY` automatically — there's no other code change needed.

## 2. Two label sets are auto-detected — pick based on your camera angle

`TFLiteObjectDetector` inspects the model's output shape at load time and switches its class
mapping automatically, purely from how many classes the model has:

| Classes | Label set | Trained on | Good for |
|---|---|---|---|
| 80 | `ai/CocoLabels.kt` | stock COCO (street-level photos) | quick testing; poor accuracy on a real overhead drone view |
| 10 | `ai/VisDroneLabels.kt` | VisDrone2019-DET (real drone-captured aerial footage) | **actual overhead/nadir drone camera use** — the recommended option |
| anything else | generic | your own custom classes | still produces detections, just without security-class mapping |

If your drone's camera looks down/forward at people and vehicles from altitude, a COCO-only model
will underperform — it has never seen that viewing angle in training. Use Option A below.

## 3. Getting an actual model file

**Option A — fine-tune on VisDrone (recommended for a real drone camera).** See
[`colab/train_yolo26_visdrone.ipynb`](colab/train_yolo26_visdrone.ipynb) and
[`colab/README.md`](colab/README.md) — a ready-to-run Google Colab notebook that fine-tunes a
YOLO26n (falls back to YOLOv8n) checkpoint on VisDrone's real drone-view footage and exports
straight to `.tflite`. Free T4 GPU, roughly 1.5-3 hours for a solid result. **Read the license
note in `colab/README.md` first** — VisDrone is CC BY-NC-SA (non-commercial use only), which is
fine for a personal/prototype build of this app but matters before any commercial release.

**Option B — stock Ultralytics YOLOv8/YOLOv5 COCO export (fastest to try, weakest on aerial
footage).** If you have the `ultralytics` Python package available:

```bash
pip install ultralytics
yolo export model=yolov8n.pt format=tflite imgsz=640
# produces yolov8n_saved_model/yolov8n_float32.tflite (and an int8 variant)
```

Rename/copy the resulting file to `app/src/main/assets/models/model.tflite`. This gets you a
`READY` status and a working demo quickly, but — per the reasoning above — it's trained entirely
on ground-level photos, so expect it to miss or misclassify a lot from an overhead drone view.
Treat this as a pipeline smoke-test, not the model to actually rely on for detection accuracy.

**Option C — TensorFlow Hub / TensorFlow Lite Object Detection sample
models.** Google publishes small SSD-MobileNet `.tflite` detectors (e.g. via
the [TFLite Object Detection example](https://www.tensorflow.org/lite/examples/object_detection/overview))
that are COCO-trained and Apache-2.0 licensed. These use a **different**
output layout (separate boxes/classes/scores/count tensors rather than one
combined tensor), so `TFLiteObjectDetector.decode()` would need a small
adapter for that layout — this is exactly the kind of change the
`ObjectDetector` interface was designed to isolate: copy
`TFLiteObjectDetector.kt`, adjust its `decode()`, keep everything else in
the app untouched.

**Option D — quantize for speed.** For faster inference on mid-range
phones, prefer an INT8-quantized export
(`yolo export model=yolov8n.pt format=tflite int8=True`) and add a simple
input-quantization step to `preprocess()` if you switch to a `UINT8` input
tensor (`TFLiteObjectDetector` currently assumes a float32 input tensor;
check `interpreter.getInputTensor(0).dataType()` if you go this route). The
Colab notebook in Option A exports float16 by default, which needs no such
change and is a reasonable size/speed/accuracy balance to start with.

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

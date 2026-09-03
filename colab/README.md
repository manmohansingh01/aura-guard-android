# Training an aerial/drone-view model for AURA Guard

`train_yolo26_visdrone.ipynb` fine-tunes a lightweight YOLO model on **VisDrone2019-DET**, a
dataset of real drone-captured footage — not ground-level photos — so the resulting model is
actually trained to recognize people and vehicles from an overhead/aerial angle: the same view a
security drone's camera sees.

## Why VisDrone, not stock YOLO weights

The pretrained "yolov8n.pt"/COCO weights most tutorials point you to are trained entirely on
street-level photos, so they perform poorly on nadir/oblique drone footage — small objects, unusual
aspect ratios, viewing angles the model has never seen. VisDrone fixes that at the data level: 288
video clips plus roughly 10,000 images, shot by drone-mounted cameras across 14 cities, at angles
ranging from near-straight-down to oblique, labeled with exactly the classes a perimeter-security
app cares about (pedestrian, people, car, van, truck, bus, motor, plus bicycle/tricycle variants).

## How to use it

1. Open `train_yolo26_visdrone.ipynb` in [Google Colab](https://colab.research.google.com/) (File
   → Upload notebook, or open it directly from this GitHub repo).
2. `Runtime → Change runtime type → T4 GPU` (the free tier is enough).
3. `Runtime → Run all`. Training ~60 epochs at 640px takes roughly 1.5-3 hours on a free T4;
   lower `EPOCHS` in the config cell for a faster first pass to sanity-check the pipeline.
4. The last cell downloads `model.tflite` to your computer automatically.
5. Copy it into the app at `app/src/main/assets/models/model.tflite`, overwriting any file
   already there, and rebuild. `TFLiteObjectDetector` detects the 10-class VisDrone output shape
   automatically and switches its label mapping — no app code changes needed.

## License — read before you ship this anywhere commercial

VisDrone2019-DET is released under **CC BY-NC-SA — non-commercial, share-alike use only**
(see [aiskyeye.com/data-protection](http://aiskyeye.com/data-protection/)). A model trained on it
is fine for your own personal/prototype use of AURA Guard. If you ever plan to sell or distribute
this app, you'd need either a different training dataset with commercial-friendly licensing, or to
obtain a commercial license for VisDrone, before shipping a VisDrone-trained model.

Separately, Ultralytics' YOLO code itself is **AGPL-3.0** — free to use, but derivative works
(including a fine-tuned model like this one) carry open-source obligations if you distribute them,
unless you hold an Ultralytics Enterprise license. Same caveat applies regardless of which YOLO
version (v8, v11, 26, ...) you train.

## What changed in the app to use this

- `ai/VisDroneLabels.kt` — the 10-class VisDrone label set and its mapping to AURA Guard's
  security-relevant classes (PERSON, CAR, TRUCK, MOTORCYCLE, BICYCLE).
- `ai/TFLiteObjectDetector.kt` — now auto-selects COCO vs. VisDrone label sets purely from the
  loaded model's output shape (80 vs. 10 classes), so either kind of `.tflite` export just works.
- `core/AuraViewModel.kt` — when the change-detection engine flags a zone as changed, and a real
  model is loaded and ready, the changed region is run through it **once** as a stateless
  classification of "what does this changed region look like right now" — not tracking, no
  identity kept between frames. If it recognizes something (e.g. PERSON), the alert and event log
  say so; otherwise everything falls back to the generic "CHANGE DETECTED" behavior exactly as
  before this existed.

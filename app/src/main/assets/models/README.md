# Model directory

Place your on-device object-detection model here as:

    app/src/main/assets/models/model.tflite

See `MODEL_SETUP.md` at the project root for where to obtain a suitable
pretrained, freely-redistributable model and how to export it to this
format. Until a file exists at this path, AURA Guard automatically runs in
`SIMULATED` inference mode so the rest of the app remains fully testable.

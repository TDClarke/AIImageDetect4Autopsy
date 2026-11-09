# AIImageDetect4Autopsy

Autopsy file ingest module that flags images carrying AI-generation provenance
indicators, with an optional OpenCV-DNN classifier. Java port of `ai_image_checker.py` in the
module structure of SmutDetect4Autopsy (factory / job settings / settings panel / file ingest
module). GPL v3, like SmutDetect.

**Triage only.** No indicators does not mean an image is human-made; metadata can be stripped, forged or copied.

## Metadata checks

Reads PNG chunks, JPEG segments and WebP chunks directly (tEXt/zTXt/iTXt, EXIF, XMP, JPEG comments, IPTC strings),
applies the Python tool's provider patterns / AI-marker terms / generation-field keys and scoring
(provider +15, AI marker +25, generation field +20, C2PA present +10, weak C2PA marker +5, max 100).
C2PA manifests are detected, **not validated** (use `c2patool` on flagged files).

## Classifier (OpenCV DNN, like FaceRadar)

Inference uses `org.opencv.dnn` (`Dnn.readNetFromONNX`), structured like FaceRadar: `OpenCvNativeLoader` tries
`System.loadLibrary` and falls back to a native library bundled in the JAR, and the model can be bundled in the JAR
and extracted to a temp file. Pre-processing is done in Java (bicubic 256x256 squash, ImageNet mean/std; verified
bit-exact against Pillow 13 on 26 test images, `test/pil_parity`), so OpenCV is used only for the network.

Model contract (PatrickKillinni/clearsnap-detector, SwinV2): input `[1,3,256,256]` float32, output `logits [1,2]`,
index 0 = artificial, 1 = real.

### ONNX compatibility: read this first

OpenCV's ONNX importer supports only a subset of ONNX operators. I checked OpenCV's importer source and tested OpenCV 5.0:

- float32 models and QDQ/`QLinear*`-quantised models import;
- models quantised with ONNX Runtime **dynamic** quantisation (`MatMulInteger`, `ConvInteger`, `DynamicQuantizeLinear`) do **not**
  (`DynamicQuantizeLinear` fails in `handleNode`).

I could not download the model, so I do not know whether `clearsnap_detector_v2_int8.onnx` is one of the loadable kinds, or whether
OpenCV supports every other operator in a SwinV2 graph. The module therefore loads the model and runs a test inference when the
ingest job starts, and fails immediately with a clear message if OpenCV can't run it. If that happens, use a float32 ONNX export
of the same model (e.g. exported from haywoodsloan/ai-image-detector-deploy with Optimum), or a newer OpenCV (4.10+/5.x has the
broadest operator support).

### Setup

1. Put OpenCV in the module the way FaceRadar does (opencv jar as a library; `opencv_java4xx` native on `java.library.path`
   or copied next to `OpenCvNativeLoader` in `src/uk/co/aiimagedetect/`).
2. Provide the model: either browse to the `.onnx` file in the ingest settings, or leave the path blank and place the file in
   `src/uk/co/aiimagedetect/` as `ai_image_detector.onnx` so it is packaged in the JAR (~100+ MB module).
3. Tick **Run AI-image classifier** and choose a threshold.

### Behaviour

- The classifier result is shown in the comment and **never changes the evidence score** (as in the Python tool).
- An image with no metadata indicators is still tagged `AIImageDetect|Classifier only` if the model rates it "artificial" at or
  above the threshold (default 90%). Images that also have metadata indicators keep their normal band.
- `dnn::Net` is not thread-safe and each Net holds its own copy of the weights, so at most 2 Nets are shared by all ingest threads
  (`OpenCvClassifier.MAX_NETS`); other threads wait. FaceRadar's one-detector-per-thread would multiply a large model's memory.
- If the option is on but the model or OpenCV can't be loaded, the module fails at start-up with a clear message instead of silently
  running without it.
- The model card reports about 5% of real photos wrongly flagged and 77-88% of AI images caught on its own test set. Validate on
  your own material.
- OpenCV is loaded in one class loader only: if Autopsy or another module already loaded the same native library, the JVM will refuse a
  second load.
- Images over 24 MP are decoded sub-sampled to bound memory, then resized as usual.

## Results

| Score | Set name | Autopsy score |
|---|---|---|
| 80-100 | `AIImageDetect\|Strong evidence` | Likely notable |
| 35-79 | `AIImageDetect\|Indicators` | Likely notable |
| 1-34 | `AIImageDetect\|Some indicators` | Unknown |
| classifier only | `AIImageDetect\|Classifier only` | Unknown |
| 0 (optional) | `AIImageDetect\|Inconclusive` | Unknown |

## Settings (per ingest job)

Skip known files, minimum size, thumbnailable files vs signatures, pixel statistics (off by default), tag inconclusive (off),
classifier on/off, model path (blank = bundled), threshold.

## Not ported

The OpenAI provenance endpoint (uploads the image to a third party) and C2PA signature validation.

## Build

Not a standalone NetBeans project. Copy your SmutDetect4Autopsy / FaceRadar project (same Autopsy dependencies), delete its sources,
drop in `src/`, and set in `manifest.mf`:

    OpenIDE-Module-Localizing-Bundle: uk/co/aiimagedetect/autopsy/Bundle.properties

Sources are Java 8 compatible and compile against the OpenCV 4.9 Java API.

## Tests

The core (everything except `autopsy/` and the two `OpenCv*` classes) has no Autopsy or OpenCV dependency:

    javac -d out $(find src -path '*autopsy' -prune -o -name 'OpenCv*.java' -prune -o -name '*.java' -print)
    javac -cp out -d testout test/SelfTest.java && java -cp out:testout SelfTest

`test/pil_parity/pil_parity.py` compares the Java resize with Pillow. The OpenCV inference path itself has not been run (no native
library or model in my environment).

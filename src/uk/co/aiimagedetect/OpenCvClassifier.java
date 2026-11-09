/*
 * AIImageDetect4Autopsy
 * Copyright (C) 2014 Rajmund Witt (SmutDetect4Autopsy, from which the module
 * structure of this project is derived)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package uk.co.aiimagedetect;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;

/**
 * Runs a SwinV2 AI-image detector exported to ONNX (such as
 * PatrickKillinni/clearsnap-detector) with OpenCV's DNN module, so no extra
 * runtime beyond the OpenCV build FaceRadar already uses is needed.
 *
 * Contract (from the model card): input float32 [1,3,256,256], output logits
 * [1,2], index 0 = artificial, 1 = real. Preprocessing is done in Java
 * (ImagePreprocessor, bit-exact with Pillow); OpenCV is used only for inference.
 *
 * IMPORTANT: OpenCV's ONNX importer supports only a subset of ONNX operators.
 * It reads float models and QDQ / QLinear-quantised models, but NOT the
 * MatMulInteger / ConvInteger / DynamicQuantizeLinear operators that ONNX
 * Runtime's dynamic int8 quantisation produces. acquire() therefore loads the
 * model and runs a test inference up front, so an unsupported model fails
 * immediately with a clear message instead of on every image.
 *
 * cv::dnn::Net is not thread-safe and the model is large, so a small pool of
 * at most MAX_NETS Nets is shared by all ingest threads (extra threads wait).
 * Java OpenCV has no explicit Net release; dropping the pool lets the GC free it.
 */
public final class OpenCvClassifier implements AiClassifier {

    /** Name of an optional model bundled in the module JAR next to this class (like FaceRadar's YuNet model). */
    public static final String BUNDLED_MODEL = "ai_image_detector.onnx";

    /** Each Net holds its own copy of the weights, so keep this small. */
    static final int MAX_NETS = 2;

    private static final Map<String, OpenCvClassifier> SHARED = new HashMap<>();
    private static File bundledFile = null;

    private final String path;
    private final String modelName;
    private final BlockingQueue<Net> idle = new LinkedBlockingQueue<>();
    private int created = 0;
    private int refs = 0;

    private OpenCvClassifier(String path) throws IOException {
        this.path = path;
        this.modelName = new File(path).getName();
        Net first = loadNet();            // also validates the model
        created = 1;
        idle.add(first);
    }

    /**
     * Loads the model on first use and returns the shared instance. Pair with release().
     *
     * @param modelPath path to the .onnx file, or blank to use the model bundled in the module JAR
     * @throws IOException       model missing, unreadable, or not importable by OpenCV
     * @throws LinkageError      the OpenCV jar or native library is not available
     */
    public static synchronized OpenCvClassifier acquire(String modelPath) throws IOException {
        OpenCvNativeLoader.load();
        final String path = (modelPath == null || modelPath.trim().isEmpty())
                ? bundledModelFile().getPath() : modelPath.trim();
        if (!new File(path).isFile()) {
            throw new IOException("ONNX model file not found: " + path);
        }
        OpenCvClassifier c = SHARED.get(path);
        if (c == null) {
            c = new OpenCvClassifier(path);
            SHARED.put(path, c);
        }
        c.refs++;
        return c;
    }

    public static synchronized void release(OpenCvClassifier c) {
        if (c == null || --c.refs > 0) {
            return;
        }
        SHARED.remove(c.path);
        c.idle.clear(); // Nets become garbage; native memory is freed by the GC
    }

    /** Copies the bundled model out of the JAR to a temp file (once) because OpenCV needs a real path. */
    private static File bundledModelFile() throws IOException {
        if (bundledFile == null || !bundledFile.exists()) {
            try (InputStream in = OpenCvClassifier.class.getResourceAsStream(BUNDLED_MODEL)) {
                if (in == null) {
                    throw new IOException("No model file was selected and none is bundled in the module ("
                            + BUNDLED_MODEL + ")");
                }
                File tmp = File.createTempFile("aiimagedetect_model_", ".onnx");
                tmp.deleteOnExit();
                Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
                bundledFile = tmp;
            }
        }
        return bundledFile;
    }

    /** Imports the model and runs a zero tensor through it to prove OpenCV can execute it. */
    private Net loadNet() throws IOException {
        Net net;
        try {
            net = Dnn.readNetFromONNX(path);
            if (net.empty()) {
                throw new IOException("OpenCV read no network from " + path);
            }
            net.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);
            net.setPreferableTarget(Dnn.DNN_TARGET_CPU);
            float[] logits = run(net, new float[3 * ImagePreprocessor.SIZE * ImagePreprocessor.SIZE]);
            if (logits == null) {
                throw new IOException("model output is not 2 logits");
            }
        } catch (RuntimeException e) { // org.opencv.core.CvException
            throw new IOException("OpenCV could not load or run this ONNX model: " + firstLine(e.getMessage())
                    + ". OpenCV's importer does not support every ONNX operator (notably the MatMulInteger/"
                    + "ConvInteger ops of dynamically int8-quantised models). Use a float32 export of the model.", e);
        }
        return net;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "unknown error";
        }
        String t = s.trim();
        int i = t.indexOf('\n');
        return i < 0 ? t : t.substring(0, i);
    }

    /** @return the two logits, or null if the output does not have exactly 2 values */
    private static float[] run(Net net, float[] chw) {
        Mat blob = new Mat(new int[]{1, 3, ImagePreprocessor.SIZE, ImagePreprocessor.SIZE}, CvType.CV_32F);
        Mat out = null;
        try {
            blob.put(new int[]{0, 0, 0, 0}, chw);
            net.setInput(blob);
            out = net.forward();
            if (out.total() != 2) {
                return null;
            }
            float[] v = new float[2];
            out.reshape(1, 1).get(0, 0, v);
            return v;
        } finally {
            blob.release();
            if (out != null) {
                out.release();
            }
        }
    }

    private Net borrow() throws IOException {
        try {
            synchronized (this) {
                Net n = idle.poll();
                if (n != null) {
                    return n;
                }
                if (created < MAX_NETS) {
                    created++;
                    try {
                        return loadNet();
                    } catch (IOException | RuntimeException e) {
                        created--;
                        throw e;
                    }
                }
            }
            return idle.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the classifier", e);
        }
    }

    @Override
    public ClassifierResult classify(InputStream image) throws IOException {
        float[] input = ImagePreprocessor.swinV2Input(image);
        if (input == null) {
            return null;
        }
        Net net = borrow();
        try {
            float[] logits = run(net, input);
            if (logits == null) {
                throw new IOException("Unexpected classifier output shape");
            }
            return ClassifierResult.fromLogits(modelName, logits[0], logits[1]);
        } catch (RuntimeException e) {
            throw new IOException("OpenCV inference failed: " + firstLine(e.getMessage()), e);
        } finally {
            idle.add(net);
        }
    }
}

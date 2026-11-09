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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Entry point: metadata/provenance indicators plus optional pixel statistics
 * for one image, plus an optional ONNX classifier (AiClassifier). Java port of
 * the "AI Image Forensic Checker" Python script, minus its OpenAI provenance
 * upload (which would send case evidence to a third party).
 *
 * The classifier result is reported next to the evidence score but never
 * changes it, as in the Python tool.
 *
 * This is a triage tool, not proof of authorship.
 */
public final class AiImageAnalyzer {

    private AiImageAnalyzer() {
    }

    /** Metadata analysis, plus pixel statistics if {@code pixels} is non-null. */
    public static AiImageReport analyse(ByteSource file, StreamSource pixels) {
        return analyse(file, pixels, pixels != null, null);
    }

    /**
     * Never throws for bad input: problems are recorded as notes in the report.
     *
     * @param file       random access to the file's bytes
     * @param image      opens a stream over the image for pixel work; may be null if neither
     *                   pixel statistics nor a classifier is wanted
     * @param pixelStats compute descriptive pixel statistics
     * @param classifier optional classifier, or null
     */
    public static AiImageReport analyse(ByteSource file, StreamSource image, boolean pixelStats,
            AiClassifier classifier) {
        ExtractedMetadata md = MetadataExtractor.extract(file);

        List<Finding> findings = new ArrayList<>();
        TreeSet<String> providers = new TreeSet<>();
        AiIndicatorRules.collect(md, findings, providers);

        int score = 0;
        for (Finding f : findings) {
            score += f.points;
        }
        score = Math.min(100, score);

        PixelStats stats = null;
        String pixelNote = null;
        if (pixelStats && image != null) {
            try (InputStream in = image.open()) {
                stats = PixelAnalyzer.analyse(in);
                if (stats == null) {
                    pixelNote = "image format not decodable by Java ImageIO";
                }
            } catch (LinkageError e) {
                // Broken ImageIO plugin (e.g. a WebP reader whose native library won't load).
                pixelNote = "image decoder unavailable: " + e;
            } catch (Exception | OutOfMemoryError e) {
                pixelNote = "not computed: " + e;
            }
        }

        ClassifierResult cls = null;
        String classifierNote = null;
        if (classifier != null && image != null) {
            try (InputStream in = image.open()) {
                cls = classifier.classify(in);
                if (cls == null) {
                    classifierNote = "image format not decodable by Java ImageIO";
                }
            } catch (LinkageError e) {
                classifierNote = "image decoder or ONNX runtime unavailable: " + e;
            } catch (Exception | OutOfMemoryError e) {
                classifierNote = "not computed: " + e;
            }
        }
        return new AiImageReport(score, findings, new ArrayList<>(providers), md, stats, pixelNote,
                cls, classifierNote);
    }
}

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Result of analysing one image: evidence score, label, findings and supporting data. */
public final class AiImageReport {

    /** Evidence bands, as in the Python assess(). */
    public enum Band {
        STRONG("Strong provenance evidence detected"),
        INDICATORS("AI-generation indicators detected"),
        SOME("Some AI-related indicators detected"),
        INCONCLUSIVE("Inconclusive - no strong AI provenance indicators found");

        private final String label;

        Band(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    private static final int KEY_FIELDS = 12;

    private final int score;
    private final Band band;
    private final List<Finding> findings;
    private final List<String> providers;
    private final ExtractedMetadata metadata;
    private final PixelStats pixelStats;
    private final String pixelNote;
    private final ClassifierResult classifier;
    private final String classifierNote;

    AiImageReport(int score, List<Finding> findings, List<String> providers,
            ExtractedMetadata metadata, PixelStats pixelStats, String pixelNote,
            ClassifierResult classifier, String classifierNote) {
        this.score = score;
        this.band = score >= 80 ? Band.STRONG : score >= 35 ? Band.INDICATORS : score > 0 ? Band.SOME : Band.INCONCLUSIVE;
        this.findings = Collections.unmodifiableList(new ArrayList<>(findings));
        this.providers = Collections.unmodifiableList(new ArrayList<>(providers));
        this.metadata = metadata;
        this.pixelStats = pixelStats;
        this.pixelNote = pixelNote;
        this.classifier = classifier;
        this.classifierNote = classifierNote;
    }

    public int getScore() { return score; }
    public Band getBand() { return band; }
    public String getLabel() { return band.getLabel(); }
    public boolean isInconclusive() { return band == Band.INCONCLUSIVE; }
    public List<Finding> getFindings() { return findings; }
    public List<String> getProviders() { return providers; }
    public ExtractedMetadata getMetadata() { return metadata; }
    public PixelStats getPixelStats() { return pixelStats; }
    public ClassifierResult getClassifierResult() { return classifier; }

    /** @return true if a classifier ran and rated the image "artificial" at or above the given probability (0..1) */
    public boolean classifierFlags(double minArtificial) {
        return classifier != null && classifier.artificial >= minArtificial;
    }

    /** @return short one-paragraph justification for the blackboard "justification" field */
    public String getJustification() {
        StringBuilder sb = new StringBuilder();
        if (classifier != null && classifier.artificial >= 0.5) {
            sb.append(String.format(java.util.Locale.ROOT, "Classifier rates the image %.0f%% artificial",
                    classifier.artificial * 100.0));
            if (!findings.isEmpty()) {
                sb.append("; ");
            }
        }
        if (findings.isEmpty()) {
            return sb.length() > 0 ? sb.toString() : "No AI provenance indicators found in embedded metadata";
        }
        for (int i = 0; i < findings.size() && i < 3; i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(findings.get(i).description);
        }
        if (findings.size() > 3) {
            sb.append("; +").append(findings.size() - 3).append(" more");
        }
        return sb.toString();
    }

    /** @return the full text used as the artifact comment */
    public String toComment() {
        StringBuilder s = new StringBuilder();
        s.append(getLabel()).append(" (evidence score ").append(score).append("/100)\n");
        s.append("Heuristic triage score, not a calibrated probability that the image is AI-generated.\n");
        s.append("Container: ").append(metadata.getContainer()).append('\n');
        if (!providers.isEmpty()) {
            s.append("Possible provider attributions: ").append(String.join(", ", providers)).append('\n');
        }
        if (findings.isEmpty()) {
            s.append("Findings: none\n");
        } else {
            s.append("Findings:\n");
            for (Finding f : findings) {
                s.append("  ").append(f).append(" (+").append(f.points).append(")\n");
            }
        }
        List<MetaField> fields = metadata.getFields();
        if (!fields.isEmpty()) {
            s.append("Embedded metadata (first ").append(Math.min(KEY_FIELDS, fields.size()))
                    .append(" of ").append(fields.size()).append(" fields):\n");
            for (int i = 0; i < fields.size() && i < KEY_FIELDS; i++) {
                MetaField f = fields.get(i);
                String v = f.value.replace('\r', ' ').replace('\n', ' ');
                if (v.length() > 200) {
                    v = v.substring(0, 200) + "...";
                }
                s.append("  ").append(f.source).append('/').append(f.key).append(": ").append(v).append('\n');
            }
        }
        if (classifier != null) {
            s.append(classifier).append('\n');
        } else if (classifierNote != null) {
            s.append("Classifier: ").append(classifierNote).append('\n');
        }
        if (pixelStats != null) {
            s.append(pixelStats).append('\n');
        } else if (pixelNote != null) {
            s.append("Pixel statistics: ").append(pixelNote).append('\n');
        }
        if (!metadata.getNotes().isEmpty()) {
            s.append("Notes: ").append(String.join("; ", metadata.getNotes())).append('\n');
        }
        s.append("Limitations: metadata can be removed, forged or copied; no indicators does not mean human-made; "
                + "AI-assisted edits of real photos may look like ordinary photos.");
        return s.toString();
    }
}

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
package uk.co.aiimagedetect.autopsy;

import org.sleuthkit.autopsy.ingest.IngestModuleIngestJobSettings;

/** Per-ingest-job options for the AI image detection module. */
public class AiImageDetectIngestJobSettings implements IngestModuleIngestJobSettings {

    // Bump this whenever fields are added: field initialisers do not run when
    // old settings are deserialised, so Autopsy must fall back to the defaults.
    private static final long serialVersionUID = 2L;

    private boolean skipKnownFiles = true;
    private boolean useThumbnail = true;
    private long minSize = 100;
    private boolean pixelStats = false;
    private boolean tagInconclusive = false;
    private boolean useClassifier = false;
    private String modelPath = "";
    private int classifierThresholdPercent = 90;

    AiImageDetectIngestJobSettings() {
    }

    AiImageDetectIngestJobSettings(boolean skipKnownFiles, boolean useThumbnail, long minSize,
            boolean pixelStats, boolean tagInconclusive,
            boolean useClassifier, String modelPath, int classifierThresholdPercent) {
        this.skipKnownFiles = skipKnownFiles;
        this.useThumbnail = useThumbnail;
        this.minSize = minSize;
        this.pixelStats = pixelStats;
        this.tagInconclusive = tagInconclusive;
        this.useClassifier = useClassifier;
        this.modelPath = modelPath == null ? "" : modelPath.trim();
        this.classifierThresholdPercent = Math.max(50, Math.min(99, classifierThresholdPercent));
    }

    @Override
    public long getVersionNumber() {
        return serialVersionUID;
    }

    boolean skipKnownFiles() { return skipKnownFiles; }
    boolean useThumbnail() { return useThumbnail; }
    long minSizeFiles() { return minSize; }
    boolean pixelStats() { return pixelStats; }
    boolean tagInconclusive() { return tagInconclusive; }
    boolean useClassifier() { return useClassifier; }
    String modelPath() { return modelPath; }
    int classifierThresholdPercent() { return classifierThresholdPercent; }
}

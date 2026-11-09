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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.logging.Level;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.casemodule.NoCurrentCaseException;
import org.sleuthkit.autopsy.coreutils.ImageUtils;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.ingest.FileIngestModule;
import org.sleuthkit.autopsy.ingest.IngestJobContext;
import org.sleuthkit.autopsy.ingest.IngestMessage;
import org.sleuthkit.autopsy.ingest.IngestModule;
import org.sleuthkit.autopsy.ingest.IngestModuleReferenceCounter;
import org.sleuthkit.autopsy.ingest.IngestServices;
import org.sleuthkit.datamodel.AbstractFile;
import org.sleuthkit.datamodel.AnalysisResultAdded;
import org.sleuthkit.datamodel.Blackboard;
import org.sleuthkit.datamodel.BlackboardArtifact;
import org.sleuthkit.datamodel.BlackboardAttribute;
import org.sleuthkit.datamodel.ReadContentInputStream;
import org.sleuthkit.datamodel.Score;
import org.sleuthkit.datamodel.TskCoreException;
import org.sleuthkit.datamodel.TskData;
import uk.co.aiimagedetect.AiImageAnalyzer;
import uk.co.aiimagedetect.AiImageReport;
import uk.co.aiimagedetect.ByteSource;
import uk.co.aiimagedetect.OpenCvClassifier;
import uk.co.aiimagedetect.StreamSource;

/**
 * File ingest module that looks for AI-generation provenance indicators in
 * images (embedded metadata, C2PA presence, generator parameters) and
 * optionally records pixel statistics. Each image with at least one indicator
 * is posted as an Interesting Item whose set name carries the evidence band:
 * "AIImageDetect|Strong evidence", "|Indicators" or "|Some indicators".
 *
 * This is triage, not proof of authorship.
 */
class AiImageDetectFileIngestModule implements FileIngestModule {

    // Shared between module instances; access only via the synchronized
    // static methods below.
    private static final HashMap<Long, Long> artifactCountsForIngestJobs = new HashMap<>();
    private static final IngestModuleReferenceCounter refCounter = new IngestModuleReferenceCounter();
    private static final String MODULE_NAME = AiImageDetectIngestModuleFactory.getModuleName();

    /** Enough bytes for the longest signature checked (JPEG 2000: 12). */
    private static final int HEADER_BYTES = 12;

    // Per-instance, per-job settings (must NOT be static: several ingest jobs
    // with different settings can run at the same time).
    private final boolean skipKnownFiles;
    private final boolean useThumbnail;
    private final long minSize;
    private final boolean pixelStats;
    private final boolean tagInconclusive;
    private final boolean useClassifier;
    private final String modelPath;
    private final double classifierThreshold;

    private OpenCvClassifier classifier = null;
    private IngestJobContext context = null;
    private Blackboard blackboard = null;

    AiImageDetectFileIngestModule(AiImageDetectIngestJobSettings settings) {
        this.skipKnownFiles = settings.skipKnownFiles();
        this.useThumbnail = settings.useThumbnail();
        this.minSize = settings.minSizeFiles();
        this.pixelStats = settings.pixelStats();
        this.tagInconclusive = settings.tagInconclusive();
        this.useClassifier = settings.useClassifier();
        this.modelPath = settings.modelPath();
        this.classifierThreshold = settings.classifierThresholdPercent() / 100.0;
    }

    @Override
    public void startUp(IngestJobContext context) throws IngestModuleException {
        this.context = context;
        try {
            blackboard = Case.getCurrentCaseThrows().getSleuthkitCase().getBlackboard();
        } catch (NoCurrentCaseException ex) {
            throw new IngestModuleException("No case is open", ex);
        }
        if (useClassifier) {
            // Fail loudly: the user asked for the classifier, so don't silently run without it.
            try {
                classifier = OpenCvClassifier.acquire(modelPath);
            } catch (IOException ex) {
                throw new IngestModuleException(ex.getMessage(), ex);
            } catch (LinkageError ex) {
                throw new IngestModuleException("OpenCV Java library or native library is not available to this module: "
                        + ex, ex);
            }
        }
        refCounter.incrementAndGet(context.getJobId());
    }

    @Override
    public IngestModule.ProcessResult process(AbstractFile file) {
        // Skip anything other than actual file system files.
        if (file.isDir()
                || file.getType() == TskData.TSK_DB_FILES_TYPE_ENUM.UNALLOC_BLOCKS
                || file.getType() == TskData.TSK_DB_FILES_TYPE_ENUM.UNUSED_BLOCKS) {
            return IngestModule.ProcessResult.OK;
        }
        if (skipKnownFiles && file.getKnown() == TskData.FileKnown.KNOWN) {
            return IngestModule.ProcessResult.OK;
        }
        if (context.fileIngestIsCancelled() || !isImageFile(file)) {
            return IngestModule.ProcessResult.OK;
        }

        try {
            // The analyser does not throw for bad input; problems become notes in the report.
            final AbstractFile f = file;
            AiImageReport report = AiImageAnalyzer.analyse(
                    new AbstractFileByteSource(f),
                    new StreamSource() {
                        @Override
                        public java.io.InputStream open() {
                            return new ReadContentInputStream(f);
                        }
                    },
                    pixelStats,
                    classifier);

            final boolean classifierOnly = report.isInconclusive() && report.classifierFlags(classifierThreshold);
            if (report.isInconclusive() && !classifierOnly && !tagInconclusive) {
                return IngestModule.ProcessResult.OK;
            }

            Collection<BlackboardAttribute> attributes = new ArrayList<>();
            attributes.add(new BlackboardAttribute(
                    new BlackboardAttribute.Type(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_SET_NAME),
                    MODULE_NAME, "AIImageDetect|" + (classifierOnly ? "Classifier only" : bandName(report.getBand()))));
            attributes.add(new BlackboardAttribute(
                    new BlackboardAttribute.Type(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_COMMENT),
                    MODULE_NAME, report.toComment()));

            AnalysisResultAdded resultAdded = file.newAnalysisResult(
                    BlackboardArtifact.Type.TSK_INTERESTING_ITEM,
                    scoreFor(report),
                    classifierOnly ? "Classifier rates image as likely AI-generated; no metadata indicators"
                            : report.getLabel(),                         // conclusion
                    "AIImageDetect metadata/provenance heuristics",      // configuration
                    report.getJustification(),                           // justification
                    attributes);

            // Creating the result only writes it to the case database; posting
            // it notifies the UI and other modules.
            blackboard.postArtifact(resultAdded.getAnalysisResult(), MODULE_NAME, context.getJobId());
            addToBlackboardPostCount(context.getJobId(), 1L);
            return IngestModule.ProcessResult.OK;

        } catch (TskCoreException | Blackboard.BlackboardException ex) {
            Logger logger = IngestServices.getInstance().getLogger(MODULE_NAME);
            logger.log(Level.SEVERE, "Error processing file (id = " + file.getId() + ")", ex);
            return IngestModule.ProcessResult.ERROR;
        }
    }

    @Override
    public void shutDown() {
        if (classifier != null) {
            OpenCvClassifier.release(classifier);
            classifier = null;
        }
        if (context != null) {
            // Always decrement the reference count (even if cancelled) so the
            // per-job entry is released; only post the summary if not cancelled.
            reportBlackboardPostCount(context.getJobId(), !context.fileIngestIsCancelled());
        }
    }

    private static String bandName(AiImageReport.Band band) {
        switch (band) {
            case STRONG:     return "Strong evidence";
            case INDICATORS: return "Indicators";
            case SOME:       return "Some indicators";
            default:         return "Inconclusive";
        }
    }

    /**
     * Metadata can be forged or copied, so even the highest band is only
     * "likely notable"; weaker bands are left unscored.
     */
    private static Score scoreFor(AiImageReport report) {
        switch (report.getBand()) {
            case STRONG:
            case INDICATORS:
                return Score.SCORE_LIKELY_NOTABLE;
            default:
                return Score.SCORE_UNKNOWN;
        }
    }

    synchronized static void addToBlackboardPostCount(long ingestJobId, long countToAdd) {
        Long fileCount = artifactCountsForIngestJobs.get(ingestJobId);
        if (fileCount == null) {
            fileCount = 0L;
        }
        artifactCountsForIngestJobs.put(ingestJobId, fileCount + countToAdd);
    }

    synchronized static void reportBlackboardPostCount(long ingestJobId, boolean postMessage) {
        Long refCount = refCounter.decrementAndGet(ingestJobId);
        if (refCount == 0) {
            Long filesCount = artifactCountsForIngestJobs.remove(ingestJobId);
            if (postMessage) {
                String msgText = String.format("Posted %d times to the blackboard",
                        filesCount == null ? 0L : filesCount);
                IngestServices.getInstance().postMessage(IngestMessage.createMessage(
                        IngestMessage.MessageType.INFO, MODULE_NAME, msgText));
            }
        }
    }

    /**
     * Decides whether to analyse the file: it must be at least minSize bytes
     * and either be thumbnail-capable (if enabled in the settings) or start
     * with a known image signature.
     */
    private boolean isImageFile(AbstractFile file) {
        if (file.getSize() < minSize) {
            return false;
        }
        if (useThumbnail && ImageUtils.isImageThumbnailSupported(file)) {
            return true;
        }

        byte[] header = new byte[HEADER_BYTES];
        int bytesRead;
        try {
            bytesRead = file.read(header, 0, HEADER_BYTES);
        } catch (TskCoreException ex) {
            return false; // can't read the first few bytes: don't parse
        }
        if (bytesRead <= 0) {
            return false;
        }

        // Signatures, most likely first. See
        // http://www.garykessler.net/library/file_sigs.html
        return startsWith(header, bytesRead, 0xFF, 0xD8, 0xFF)                              // JPEG
                || startsWith(header, bytesRead, 0x89, 0x50, 0x4E, 0x47)                    // PNG
                || (startsWith(header, bytesRead, 0x52, 0x49, 0x46, 0x46) && bytesRead >= 12
                        && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') // WebP
                || startsWith(header, bytesRead, 0x47, 0x49, 0x46, 0x38)                    // GIF87a / GIF89a
                || startsWith(header, bytesRead, 0x42, 0x4D)                                // BMP "BM"
                || startsWith(header, bytesRead, 0x49, 0x49, 0x2A, 0x00)                    // TIFF little endian
                || startsWith(header, bytesRead, 0x4D, 0x4D, 0x00, 0x2A)                    // TIFF big endian
                || startsWith(header, bytesRead, 0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20); // JPEG 2000 (JP2)
    }

    /** @return true if the first sig.length bytes read match the signature */
    private static boolean startsWith(byte[] buf, int bytesRead, int... sig) {
        if (bytesRead < sig.length) {
            return false;
        }
        for (int i = 0; i < sig.length; i++) {
            if ((buf[i] & 0xFF) != sig[i]) {
                return false;
            }
        }
        return true;
    }

    /** Lets the Autopsy-free core read an AbstractFile. */
    private static final class AbstractFileByteSource implements ByteSource {

        private final AbstractFile file;

        AbstractFileByteSource(AbstractFile file) {
            this.file = file;
        }

        @Override
        public long length() {
            return file.getSize();
        }

        @Override
        public int read(byte[] buf, long offset, int len) throws IOException {
            try {
                int n = file.read(buf, offset, len);
                return n <= 0 ? -1 : n;
            } catch (TskCoreException ex) {
                throw new IOException("Could not read " + file.getName(), ex);
            }
        }
    }
}

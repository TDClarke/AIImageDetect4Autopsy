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

import org.openide.util.NbBundle;
import org.openide.util.lookup.ServiceProvider;
import org.sleuthkit.autopsy.ingest.DataSourceIngestModule;
import org.sleuthkit.autopsy.ingest.FileIngestModule;
import org.sleuthkit.autopsy.ingest.IngestModuleFactory;
import org.sleuthkit.autopsy.ingest.IngestModuleGlobalSettingsPanel;
import org.sleuthkit.autopsy.ingest.IngestModuleIngestJobSettings;
import org.sleuthkit.autopsy.ingest.IngestModuleIngestJobSettingsPanel;

/**
 * Factory for the AI image detection file ingest module. Autopsy discovers it
 * through the ServiceProvider annotation. Module instances never share state
 * except through the synchronized statics in the file ingest module.
 */
@ServiceProvider(service = IngestModuleFactory.class)
public class AiImageDetectIngestModuleFactory implements IngestModuleFactory {

    private static final String VERSION_NUMBER = "0.1";

    static String getModuleName() {
        return NbBundle.getMessage(AiImageDetectIngestModuleFactory.class,
                "AiImageDetectIngestModuleFactory.moduleName");
    }

    @Override
    public String getModuleDisplayName() {
        return getModuleName();
    }

    @Override
    public String getModuleDescription() {
        return NbBundle.getMessage(AiImageDetectIngestModuleFactory.class,
                "AiImageDetectIngestModuleFactory.moduleDescription");
    }

    @Override
    public String getModuleVersionNumber() {
        return VERSION_NUMBER;
    }

    @Override
    public boolean hasGlobalSettingsPanel() {
        return false;
    }

    @Override
    public IngestModuleGlobalSettingsPanel getGlobalSettingsPanel() {
        throw new UnsupportedOperationException();
    }

    @Override
    public IngestModuleIngestJobSettings getDefaultIngestJobSettings() {
        return new AiImageDetectIngestJobSettings();
    }

    @Override
    public boolean hasIngestJobSettingsPanel() {
        return true;
    }

    @Override
    public IngestModuleIngestJobSettingsPanel getIngestJobSettingsPanel(IngestModuleIngestJobSettings settings) {
        if (!(settings instanceof AiImageDetectIngestJobSettings)) {
            throw new IllegalArgumentException("Expected settings argument to be instanceof AiImageDetectIngestJobSettings");
        }
        return new AiImageDetectIngestJobSettingsPanel((AiImageDetectIngestJobSettings) settings);
    }

    @Override
    public boolean isDataSourceIngestModuleFactory() {
        return false;
    }

    @Override
    public DataSourceIngestModule createDataSourceIngestModule(IngestModuleIngestJobSettings settings) {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public boolean isFileIngestModuleFactory() {
        return true;
    }

    @Override
    public FileIngestModule createFileIngestModule(IngestModuleIngestJobSettings settings) {
        if (!(settings instanceof AiImageDetectIngestJobSettings)) {
            throw new IllegalArgumentException("Expected settings argument to be instanceof AiImageDetectIngestJobSettings");
        }
        return new AiImageDetectFileIngestModule((AiImageDetectIngestJobSettings) settings);
    }
}

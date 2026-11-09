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
import java.util.logging.Level;
import java.util.logging.Logger;
import org.opencv.core.Core;

/**
 * Loads the OpenCV native library once, falling back to extracting it from the
 * module JAR. Same approach as FaceRadarNativeLoader: first try
 * java.library.path, then a copy of the library placed next to this class in
 * the JAR (e.g. uk/co/aiimagedetect/opencv_java490.dll).
 *
 * If another module (or Autopsy itself) has already loaded OpenCV in a
 * different class loader, the JVM refuses a second load; that error is
 * reported as-is.
 */
public final class OpenCvNativeLoader {

    private static final Logger LOG = Logger.getLogger(OpenCvNativeLoader.class.getName());
    private static boolean loaded = false;

    private OpenCvNativeLoader() {
    }

    /** @throws UnsatisfiedLinkError if the library can be found neither on the library path nor in the JAR */
    public static synchronized void load() {
        if (loaded) {
            return;
        }
        final String libName = Core.NATIVE_LIBRARY_NAME; // e.g. opencv_java490
        try {
            System.loadLibrary(libName);
            loaded = true;
            LOG.log(Level.INFO, "Loaded OpenCV native library via java.library.path: {0}", libName);
            return;
        } catch (UnsatisfiedLinkError e) {
            LOG.log(Level.WARNING, "System.loadLibrary({0}) failed, trying bundled copy: {1}",
                    new Object[]{libName, e});
        }

        final String fileName = System.mapLibraryName(libName); // opencv_java490.dll
        try (InputStream in = OpenCvNativeLoader.class.getResourceAsStream(fileName)) {
            if (in == null) {
                throw new UnsatisfiedLinkError("Bundled " + fileName + " not found in module JAR");
            }
            File tmpDir = Files.createTempDirectory("aiimagedetect_native").toFile();
            File tmp = new File(tmpDir, fileName);
            tmp.deleteOnExit();
            tmpDir.deleteOnExit();
            Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.load(tmp.getAbsolutePath());
            loaded = true;
            LOG.log(Level.INFO, "Loaded OpenCV native library from {0}", tmp);
        } catch (IOException ex) {
            throw new UnsatisfiedLinkError("Could not extract " + fileName + ": " + ex);
        }
    }
}

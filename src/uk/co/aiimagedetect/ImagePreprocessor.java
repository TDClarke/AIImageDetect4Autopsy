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

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * Builds the SwinV2 detector's input tensor: RGB, bicubic resize (squashed,
 * no crop) to 256x256 exactly as Pillow does, scaled to 0..1, ImageNet
 * mean/std, planar CHW float32.
 */
public final class ImagePreprocessor {

    public static final int SIZE = 256;
    /** Images above this many pixels are decoded sub-sampled to bound memory. */
    static final long FULL_DECODE_PIXELS = 24_000_000L;
    static final long MAX_SOURCE_PIXELS = 400_000_000L;

    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    private ImagePreprocessor() {
    }

    /** @return float[3*256*256] in CHW order, or null if no ImageIO reader recognises the stream */
    public static float[] swinV2Input(InputStream in) throws IOException {
        try (ImageInputStream iis = new MemoryCacheImageInputStream(in)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, false, true);
                final long w = reader.getWidth(0);
                final long h = reader.getHeight(0);
                if (w * h > MAX_SOURCE_PIXELS) {
                    throw new IOException("Image too large to classify (" + w + "x" + h + ")");
                }
                ImageReadParam param = reader.getDefaultReadParam();
                if (w * h > FULL_DECODE_PIXELS) {
                    int step = (int) Math.ceil(Math.sqrt((double) (w * h) / FULL_DECODE_PIXELS));
                    param.setSourceSubsampling(step, step, 0, 0);
                }
                BufferedImage img = reader.read(0, param);
                return img == null ? null : toTensor(img);
            } finally {
                reader.dispose();
            }
        }
    }

    static float[] toTensor(BufferedImage img) {
        byte[] rgb = PilResize.resizeRgb(img, SIZE, SIZE);
        final int plane = SIZE * SIZE;
        float[] t = new float[3 * plane];
        for (int i = 0; i < plane; i++) {
            for (int c = 0; c < 3; c++) {
                t[c * plane + i] = ((rgb[i * 3 + c] & 0xFF) / 255f - MEAN[c]) / STD[c];
            }
        }
        return t;
    }

    /** Test hook: resized interleaved RGB bytes. */
    static byte[] resizedRgb(BufferedImage img) {
        return PilResize.resizeRgb(img, SIZE, SIZE);
    }
}

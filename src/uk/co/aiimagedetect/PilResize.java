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

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;
import java.util.Arrays;

/**
 * Bit-exact re-implementation of Pillow's Image.resize(..., Image.BICUBIC)
 * for 8-bit RGB (separable horizontal-then-vertical pass, support scaled when
 * shrinking, 22-bit fixed-point coefficients, 8-bit rounding between passes),
 * so the classifier sees the same pixels as the Python reference pipeline.
 */
final class PilResize {

    private static final int PRECISION_BITS = 22;
    private static final double A = -0.5;

    static final class Coeffs {
        final int[] start;
        final int[][] k;

        Coeffs(int[] start, int[][] k) {
            this.start = start;
            this.k = k;
        }
    }

    private PilResize() {
    }

    private static double bicubic(double x) {
        if (x < 0.0) {
            x = -x;
        }
        if (x < 1.0) {
            return ((A + 2.0) * x - (A + 3.0)) * x * x + 1;
        }
        if (x < 2.0) {
            return (((x - 5) * x + 8) * x - 4) * A;
        }
        return 0.0;
    }

    static Coeffs coeffs(int inSize, int outSize) {
        final double scale = (double) inSize / outSize;
        final double filterscale = Math.max(scale, 1.0);
        final double support = 2.0 * filterscale;
        final int[] start = new int[outSize];
        final int[][] k = new int[outSize][];
        for (int xx = 0; xx < outSize; xx++) {
            final double center = (xx + 0.5) * scale;
            final double ss = 1.0 / filterscale;
            int xmin = (int) (center - support + 0.5);
            if (xmin < 0) {
                xmin = 0;
            }
            int xmax = (int) (center + support + 0.5);
            if (xmax > inSize) {
                xmax = inSize;
            }
            xmax -= xmin;
            double[] w = new double[xmax];
            double total = 0;
            for (int x = 0; x < xmax; x++) {
                w[x] = bicubic((x + xmin - center + 0.5) * ss);
                total += w[x];
            }
            int[] fixed = new int[xmax];
            for (int x = 0; x < xmax; x++) {
                double v = total != 0 ? w[x] / total : w[x];
                fixed[x] = v < 0 ? (int) (-0.5 + v * (1 << PRECISION_BITS)) : (int) (0.5 + v * (1 << PRECISION_BITS));
            }
            start[xx] = xmin;
            k[xx] = fixed;
        }
        return new Coeffs(start, k);
    }

    private static int clip8(int s) {
        int v = s >> PRECISION_BITS;
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }

    /** Reads one row as packed 0xRRGGBB; gray images are read from the raster so no gamma conversion is applied. */
    private static void readRow(BufferedImage img, int y, int[] row, int[] scratch) {
        final int w = img.getWidth();
        final ColorModel cm = img.getColorModel();
        if (cm.getColorSpace().getType() == ColorSpace.TYPE_GRAY && !(cm instanceof IndexColorModel)) {
            img.getRaster().getSamples(0, y, w, 1, 0, scratch);
            final int shift = Math.max(0, cm.getComponentSize(0) - 8);
            for (int x = 0; x < w; x++) {
                int g = Math.min(255, scratch[x] >> shift);
                row[x] = (g << 16) | (g << 8) | g;
            }
        } else {
            img.getRGB(0, y, w, 1, row, 0, w);
        }
    }

    /**
     * @return interleaved RGB bytes (dh rows of dw*3). Rows are read one at a
     *         time, so no full-size copy of the pixels is made.
     */
    static byte[] resizeRgb(BufferedImage img, int dw, int dh) {
        final int sw = img.getWidth();
        final int sh = img.getHeight();
        final Coeffs ch = coeffs(sw, dw);
        final Coeffs cv = coeffs(sh, dh);
        final int half = 1 << (PRECISION_BITS - 1);
        final int[] row = new int[sw];
        final int[] scratch = new int[sw];

        // Pillow (Resample.c): run the vertical pass first when the height shrinks by
        // more than twice as many pixels as the width does; otherwise horizontal first.
        final boolean horizontalFirst = !((sh - dh) > 0 && (sh - dh) > (sw - dw) * 2);

        if (horizontalFirst) {
            final byte[] tmp = new byte[sh * dw * 3];
            for (int y = 0; y < sh; y++) {
                readRow(img, y, row, scratch);
                horizontal(row, ch, dw, tmp, y * dw * 3, half);
            }
            return vertical(tmp, dw, cv, dh, half);
        }

        // vertical first: accumulate each source row into the output rows whose kernel covers it
        final int rowBytes = sw * 3;
        final int[][] acc = new int[dh][rowBytes];
        for (int[] a : acc) {
            Arrays.fill(a, half);
        }
        int lo = 0;
        for (int y = 0; y < sh; y++) {
            readRow(img, y, row, scratch);
            while (lo < dh && cv.start[lo] + cv.k[lo].length <= y) {
                lo++;
            }
            for (int yy = lo; yy < dh && cv.start[yy] <= y; yy++) {
                final int kv = cv.k[yy][y - cv.start[yy]];
                final int[] a = acc[yy];
                for (int x = 0, o = 0; x < sw; x++) {
                    int p = row[x];
                    a[o++] += ((p >> 16) & 0xFF) * kv;
                    a[o++] += ((p >> 8) & 0xFF) * kv;
                    a[o++] += (p & 0xFF) * kv;
                }
            }
        }
        final byte[] tmp = new byte[dh * rowBytes];
        for (int yy = 0; yy < dh; yy++) {
            for (int i = 0; i < rowBytes; i++) {
                tmp[yy * rowBytes + i] = (byte) clip8(acc[yy][i]);
            }
        }
        final byte[] out = new byte[dh * dw * 3];
        final int[] packed = new int[sw];
        for (int yy = 0; yy < dh; yy++) {
            for (int x = 0; x < sw; x++) {
                int o = yy * rowBytes + x * 3;
                packed[x] = ((tmp[o] & 0xFF) << 16) | ((tmp[o + 1] & 0xFF) << 8) | (tmp[o + 2] & 0xFF);
            }
            horizontal(packed, ch, dw, out, yy * dw * 3, half);
        }
        return out;
    }

    private static void horizontal(int[] row, Coeffs ch, int dw, byte[] dst, int o, int half) {
        for (int xx = 0; xx < dw; xx++) {
            final int[] k = ch.k[xx];
            final int x0 = ch.start[xx];
            int r = half, g = half, b = half;
            for (int i = 0; i < k.length; i++) {
                int p = row[x0 + i];
                int kv = k[i];
                r += ((p >> 16) & 0xFF) * kv;
                g += ((p >> 8) & 0xFF) * kv;
                b += (p & 0xFF) * kv;
            }
            dst[o++] = (byte) clip8(r);
            dst[o++] = (byte) clip8(g);
            dst[o++] = (byte) clip8(b);
        }
    }

    private static byte[] vertical(byte[] tmp, int dw, Coeffs cv, int dh, int half) {
        final byte[] out = new byte[dh * dw * 3];
        final int stride = dw * 3;
        for (int yy = 0; yy < dh; yy++) {
            final int[] k = cv.k[yy];
            final int y0 = cv.start[yy];
            for (int x = 0; x < stride; x++) {
                int s = half;
                for (int i = 0; i < k.length; i++) {
                    s += (tmp[(y0 + i) * stride + x] & 0xFF) * k[i];
                }
                out[yy * stride + x] = (byte) clip8(s);
            }
        }
        return out;
    }
}

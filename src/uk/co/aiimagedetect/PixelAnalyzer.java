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
import java.util.Arrays;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * Port of the Python pixel_analysis(). Differences from the NumPy version:
 *  - the image is decoded sub-sampled (integer step) so its longer side is at
 *    most 1600 px, instead of Pillow's thumbnail() resampling;
 *  - the FFT runs on a power-of-two centre crop (at most 1024x1024) of the
 *    grayscale sample rather than on the whole thumbnail, which keeps native
 *    high-frequency content.
 * The numbers are therefore comparable between files analysed by this module
 * but are not identical to the Python tool's output.
 */
public final class PixelAnalyzer {

    static final int MAX_DIM = 1600;
    static final long MAX_SOURCE_PIXELS = 400_000_000L;
    private static final int MAX_FFT = 1024;

    private PixelAnalyzer() {
    }

    /** @return the statistics, or null if no ImageIO reader recognises the stream */
    public static PixelStats analyse(InputStream in) throws IOException {
        try (ImageInputStream iis = new MemoryCacheImageInputStream(in)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, false, true);
                final long w0 = reader.getWidth(0);
                final long h0 = reader.getHeight(0);
                if (w0 * h0 > MAX_SOURCE_PIXELS) {
                    throw new IOException("Image too large to analyse (" + w0 + "x" + h0 + ")");
                }
                ImageReadParam param = reader.getDefaultReadParam();
                int step = (int) Math.max(1L, (Math.max(w0, h0) + MAX_DIM - 1) / MAX_DIM);
                if (step > 1) {
                    param.setSourceSubsampling(step, step, 0, 0);
                }
                BufferedImage img = reader.read(0, param);
                return img == null ? null : compute(img);
            } finally {
                reader.dispose();
            }
        }
    }

    static PixelStats compute(BufferedImage img) {
        final int w = img.getWidth();
        final int h = img.getHeight();
        final float[] gray = new float[w * h];
        final int[] row = new int[w];
        final double[] sum = new double[3];
        final double[] sumSq = new double[3];
        final long[] hist = new long[256];

        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int p = row[x];
                double r = ((p >> 16) & 0xFF) / 255.0;
                double g = ((p >> 8) & 0xFF) / 255.0;
                double b = (p & 0xFF) / 255.0;
                sum[0] += r; sumSq[0] += r * r;
                sum[1] += g; sumSq[1] += g * g;
                sum[2] += b; sumSq[2] += b * b;
                float v = (float) (0.299 * r + 0.587 * g + 0.114 * b);
                gray[y * w + x] = v;
                hist[Math.min(255, Math.max(0, (int) (v * 256f)))]++;
            }
        }

        final double n = (double) w * h;
        double[] mean = new double[3];
        double[] std = new double[3];
        for (int c = 0; c < 3; c++) {
            mean[c] = sum[c] / n;
            std[c] = Math.sqrt(Math.max(0.0, sumSq[c] / n - mean[c] * mean[c]));
        }

        double entropy = 0;
        for (long c : hist) {
            if (c > 0) {
                double p = c / n;
                entropy -= p * (Math.log(p) / Math.log(2));
            }
        }

        // Laplacian-like second difference over the interior.
        double residualStd = 0, residualMad = 0;
        if (w > 2 && h > 2) {
            final int lw = w - 2, lh = h - 2;
            float[] lap = new float[lw * lh];
            double ls = 0, lsq = 0;
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    int i = y * w + x;
                    float v = -4f * gray[i] + gray[i - w] + gray[i + w] + gray[i - 1] + gray[i + 1];
                    lap[(y - 1) * lw + (x - 1)] = v;
                    ls += v;
                    lsq += (double) v * v;
                }
            }
            double ln = lap.length;
            double lm = ls / ln;
            residualStd = Math.sqrt(Math.max(0.0, lsq / ln - lm * lm));
            float[] sorted = lap.clone();
            Arrays.sort(sorted);
            double med = median(sorted);
            for (int i = 0; i < sorted.length; i++) {
                sorted[i] = (float) Math.abs(lap[i] - med);
            }
            Arrays.sort(sorted);
            residualMad = median(sorted);
        }

        // JPEG 8px block boundary ratio: mean |row[r+1]-row[r]| at r = 7,15,.. vs r = 0,8,16,..
        double boundary = 0;
        if (h > 8) {
            double bSum = 0, oSum = 0;
            long bCnt = 0, oCnt = 0;
            for (int r = 0; r <= h - 2; r++) {
                int mod = r & 7;
                if (mod != 7 && mod != 0) {
                    continue;
                }
                double d = 0;
                for (int x = 0; x < w; x++) {
                    d += Math.abs(gray[(r + 1) * w + x] - gray[r * w + x]);
                }
                if (mod == 7) {
                    bSum += d;
                    bCnt += w;
                } else {
                    oSum += d;
                    oCnt += w;
                }
            }
            double bh = bCnt == 0 ? 0 : bSum / bCnt;
            double oh = oCnt == 0 ? 0 : oSum / oCnt;
            boundary = bh / (oh + 1e-9);
        }

        // High-frequency energy share of the 2-D spectrum.
        int fft = 1;
        while (fft * 2 <= Math.min(Math.min(w, h), MAX_FFT)) {
            fft *= 2;
        }
        double hf = Double.NaN;
        if (fft >= 16) {
            hf = highFrequencyRatio(gray, w, h, fft);
        } else {
            fft = 0;
        }

        return new PixelStats(w, h, mean, std, entropy, hf, fft, residualStd, residualMad, boundary);
    }

    private static double median(float[] sorted) {
        int n = sorted.length;
        if (n == 0) {
            return 0;
        }
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + (double) sorted[n / 2]) / 2.0;
    }

    private static double highFrequencyRatio(float[] gray, int w, int h, int n) {
        final int x0 = (w - n) / 2, y0 = (h - n) / 2;
        final double[] re = new double[n * n];
        final double[] im = new double[n * n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                re[y * n + x] = gray[(y0 + y) * w + (x0 + x)];
            }
        }
        final double[] tr = new double[n];
        final double[] ti = new double[n];
        for (int y = 0; y < n; y++) {                 // rows
            System.arraycopy(re, y * n, tr, 0, n);
            System.arraycopy(im, y * n, ti, 0, n);
            fft1d(tr, ti);
            System.arraycopy(tr, 0, re, y * n, n);
            System.arraycopy(ti, 0, im, y * n, n);
        }
        for (int x = 0; x < n; x++) {                 // columns
            for (int y = 0; y < n; y++) {
                tr[y] = re[y * n + x];
                ti[y] = im[y * n + x];
            }
            fft1d(tr, ti);
            for (int y = 0; y < n; y++) {
                re[y * n + x] = tr[y];
                im[y * n + x] = ti[y];
            }
        }
        final double maxr = n / 2.0;
        final double cut = maxr * 0.35;
        double total = 1e-12, high = 0;
        for (int ky = 0; ky < n; ky++) {
            int sy = (ky + n / 2) % n;               // fftshift
            for (int kx = 0; kx < n; kx++) {
                int sx = (kx + n / 2) % n;
                double e = re[ky * n + kx] * re[ky * n + kx] + im[ky * n + kx] * im[ky * n + kx];
                total += e;
                double dy = sy - n / 2.0, dx = sx - n / 2.0;
                if (Math.sqrt(dy * dy + dx * dx) > cut) {
                    high += e;
                }
            }
        }
        return high / total;
    }

    /** In-place iterative radix-2 FFT; length must be a power of two. */
    private static void fft1d(double[] re, double[] im) {
        final int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double xr = re[b] * cr - im[b] * ci;
                    double xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    double ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
    }
}

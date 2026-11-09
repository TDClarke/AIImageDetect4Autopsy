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

/**
 * Explainable pixel statistics (port of the Python pixel_analysis()).
 * Descriptive only: they never contribute to the evidence score.
 */
public final class PixelStats {

    public final int width;
    public final int height;
    public final double[] channelMean;   // R, G, B (0..1)
    public final double[] channelStd;
    public final double grayscaleEntropyBits;
    /** NaN if the image was too small for an FFT. */
    public final double highFrequencyEnergyRatio;
    public final int fftSize;
    public final double residualStd;
    public final double residualMad;
    public final double jpeg8pxBoundaryRatio;

    PixelStats(int width, int height, double[] mean, double[] std, double entropy,
            double hfRatio, int fftSize, double residualStd, double residualMad, double boundaryRatio) {
        this.width = width;
        this.height = height;
        this.channelMean = mean;
        this.channelStd = std;
        this.grayscaleEntropyBits = entropy;
        this.highFrequencyEnergyRatio = hfRatio;
        this.fftSize = fftSize;
        this.residualStd = residualStd;
        this.residualMad = residualMad;
        this.jpeg8pxBoundaryRatio = boundaryRatio;
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append("Pixel statistics (descriptive only, not used in the score) on ")
                .append(width).append("x").append(height).append(" sample:\n");
        s.append(String.format(java.util.Locale.ROOT, "  channel mean R/G/B: %.4f / %.4f / %.4f%n", channelMean[0], channelMean[1], channelMean[2]));
        s.append(String.format(java.util.Locale.ROOT, "  channel stddev R/G/B: %.4f / %.4f / %.4f%n", channelStd[0], channelStd[1], channelStd[2]));
        s.append(String.format(java.util.Locale.ROOT, "  grayscale entropy: %.4f bits%n", grayscaleEntropyBits));
        if (Double.isNaN(highFrequencyEnergyRatio)) {
            s.append("  high-frequency energy ratio: n/a (image too small)\n");
        } else {
            s.append(String.format(java.util.Locale.ROOT, "  high-frequency energy ratio: %.6f (%dx%d centre crop)%n",
                    highFrequencyEnergyRatio, fftSize, fftSize));
        }
        s.append(String.format(java.util.Locale.ROOT, "  residual stddev / MAD: %.7f / %.7f%n", residualStd, residualMad));
        s.append(String.format(java.util.Locale.ROOT, "  JPEG 8px boundary ratio: %.5f", jpeg8pxBoundaryRatio));
        return s.toString();
    }
}

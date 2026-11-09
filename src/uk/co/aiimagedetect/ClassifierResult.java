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

/** Output of the ONNX image classifier: softmax probabilities for "artificial" and "real". */
public final class ClassifierResult {

    public final String model;
    public final double artificial;
    public final double real;

    public ClassifierResult(String model, double artificial, double real) {
        this.model = model;
        this.artificial = artificial;
        this.real = real;
    }

    /** Index 0 = artificial, index 1 = real (the clearsnap/SwinV2 detector's label order); stable softmax. */
    public static ClassifierResult fromLogits(String model, double artificialLogit, double realLogit) {
        double m = Math.max(artificialLogit, realLogit);
        double ea = Math.exp(artificialLogit - m), er = Math.exp(realLogit - m);
        return new ClassifierResult(model, ea / (ea + er), er / (ea + er));
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT,
                "Classifier (%s): artificial %.1f%% / real %.1f%%. Model-specific score, not a calibrated "
                + "probability; it needs validation on images representative of your case material.",
                model, artificial * 100.0, real * 100.0);
    }
}

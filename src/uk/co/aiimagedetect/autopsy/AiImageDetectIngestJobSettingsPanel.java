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

import java.awt.FlowLayout;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.SpinnerNumberModel;
import org.openide.awt.Mnemonics;
import org.openide.util.NbBundle;
import org.sleuthkit.autopsy.ingest.IngestModuleIngestJobSettings;
import org.sleuthkit.autopsy.ingest.IngestModuleIngestJobSettingsPanel;

/** UI used to choose the per-ingest-job settings. Built by hand (no Form Editor file). */
public class AiImageDetectIngestJobSettingsPanel extends IngestModuleIngestJobSettingsPanel {

    private final JCheckBox skipKnownFilesCheckBox = new JCheckBox();
    private final JCheckBox useThumbnailCheckBox = new JCheckBox();
    private final JSpinner imageBytesSpinner = new JSpinner(new SpinnerNumberModel(100, 0, Integer.MAX_VALUE, 1));
    private final JLabel minSizeLabel = new JLabel();
    private final JCheckBox pixelStatsCheckBox = new JCheckBox();
    private final JCheckBox tagInconclusiveCheckBox = new JCheckBox();
    private final JCheckBox classifierCheckBox = new JCheckBox();
    private final JTextField modelPathField = new JTextField(28);
    private final JButton browseButton = new JButton();
    private final JLabel thresholdLabel = new JLabel();
    private final JSpinner thresholdSpinner = new JSpinner(new SpinnerNumberModel(90, 50, 99, 1));

    public AiImageDetectIngestJobSettingsPanel(AiImageDetectIngestJobSettings settings) {
        initComponents();
        // The spinner model is Integer-based, so clamp and set an int.
        long min = Math.max(0L, Math.min(settings.minSizeFiles(), Integer.MAX_VALUE));
        skipKnownFilesCheckBox.setSelected(settings.skipKnownFiles());
        useThumbnailCheckBox.setSelected(settings.useThumbnail());
        imageBytesSpinner.setValue((int) min);
        pixelStatsCheckBox.setSelected(settings.pixelStats());
        tagInconclusiveCheckBox.setSelected(settings.tagInconclusive());
        classifierCheckBox.setSelected(settings.useClassifier());
        modelPathField.setText(settings.modelPath());
        thresholdSpinner.setValue(settings.classifierThresholdPercent());
        updateClassifierControls();
    }

    private void updateClassifierControls() {
        boolean on = classifierCheckBox.isSelected();
        modelPathField.setEnabled(on);
        browseButton.setEnabled(on);
        thresholdSpinner.setEnabled(on);
        thresholdLabel.setEnabled(on);
    }

    private void browse() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("ONNX models (*.onnx)", "onnx"));
        String cur = modelPathField.getText().trim();
        if (!cur.isEmpty()) {
            fc.setSelectedFile(new java.io.File(cur));
        }
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            modelPathField.setText(fc.getSelectedFile().getAbsolutePath());
        }
    }

    private void initComponents() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        text(skipKnownFilesCheckBox, "AiImageDetectIngestJobSettingsPanel.skipKnownFilesCheckBox.text");
        text(useThumbnailCheckBox, "AiImageDetectIngestJobSettingsPanel.useThumbnailCheckBox.text");
        Mnemonics.setLocalizedText(minSizeLabel, NbBundle.getMessage(AiImageDetectIngestJobSettingsPanel.class,
                "AiImageDetectIngestJobSettingsPanel.minSizeLabel.text"));
        text(pixelStatsCheckBox, "AiImageDetectIngestJobSettingsPanel.pixelStatsCheckBox.text");
        text(tagInconclusiveCheckBox, "AiImageDetectIngestJobSettingsPanel.tagInconclusiveCheckBox.text");
        imageBytesSpinner.setVerifyInputWhenFocusTarget(false);

        text(classifierCheckBox, "AiImageDetectIngestJobSettingsPanel.classifierCheckBox.text");
        Mnemonics.setLocalizedText(browseButton, NbBundle.getMessage(AiImageDetectIngestJobSettingsPanel.class,
                "AiImageDetectIngestJobSettingsPanel.browseButton.text"));
        Mnemonics.setLocalizedText(thresholdLabel, NbBundle.getMessage(AiImageDetectIngestJobSettingsPanel.class,
                "AiImageDetectIngestJobSettingsPanel.thresholdLabel.text"));
        modelPathField.setToolTipText(NbBundle.getMessage(AiImageDetectIngestJobSettingsPanel.class,
                "AiImageDetectIngestJobSettingsPanel.modelPathField.toolTipText"));
        classifierCheckBox.addActionListener(e -> updateClassifierControls());
        browseButton.addActionListener(e -> browse());

        JPanel modelRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        modelRow.add(modelPathField);
        modelRow.add(browseButton);
        JPanel thresholdRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        thresholdRow.add(thresholdSpinner);
        thresholdRow.add(thresholdLabel);

        JPanel sizeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        sizeRow.add(imageBytesSpinner);
        sizeRow.add(javax.swing.Box.createHorizontalStrut(6));
        sizeRow.add(minSizeLabel);

        add(left(skipKnownFilesCheckBox));
        add(left(sizeRow));
        add(left(useThumbnailCheckBox));
        add(left(pixelStatsCheckBox));
        add(left(tagInconclusiveCheckBox));
        add(javax.swing.Box.createVerticalStrut(8));
        add(left(classifierCheckBox));
        add(left(modelRow));
        add(left(thresholdRow));
    }

    private static void text(javax.swing.AbstractButton b, String key) {
        Mnemonics.setLocalizedText(b, NbBundle.getMessage(AiImageDetectIngestJobSettingsPanel.class, key));
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        return c;
    }

    @Override
    public IngestModuleIngestJobSettings getSettings() {
        try {
            imageBytesSpinner.commitEdit();
        } catch (java.text.ParseException ex) {
            // Not a valid number: revert the editor to the last valid value.
            imageBytesSpinner.setValue(imageBytesSpinner.getValue());
        }
        try {
            thresholdSpinner.commitEdit();
        } catch (java.text.ParseException ex) {
            thresholdSpinner.setValue(thresholdSpinner.getValue());
        }
        return new AiImageDetectIngestJobSettings(
                skipKnownFilesCheckBox.isSelected(),
                useThumbnailCheckBox.isSelected(),
                ((Number) imageBytesSpinner.getValue()).longValue(),
                pixelStatsCheckBox.isSelected(),
                tagInconclusiveCheckBox.isSelected(),
                classifierCheckBox.isSelected(),
                modelPathField.getText(),
                ((Number) thresholdSpinner.getValue()).intValue());
    }
}

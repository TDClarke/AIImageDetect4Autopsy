package uk.co.aiimagedetect;
import java.io.*; import javax.imageio.ImageIO; import java.awt.image.BufferedImage;
public class ResizeDump { public static void main(String[] a) throws Exception {
  for (String f : a) { BufferedImage im = ImageIO.read(new File(f));
    try (FileOutputStream o = new FileOutputStream(f + ".java.raw")) { o.write(ImagePreprocessor.resizedRgb(im)); } } } }

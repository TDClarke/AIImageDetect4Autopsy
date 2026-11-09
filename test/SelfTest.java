import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import javax.imageio.ImageIO;
import uk.co.aiimagedetect.*;

/** Plain-Java self-test (no JUnit needed): java -cp out:testout SelfTest */
public class SelfTest {
    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    static byte[] image(String fmt, boolean noisy) throws Exception {
        BufferedImage bi = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(1);
        for (int y = 0; y < 200; y++) for (int x = 0; x < 300; x++)
            bi.setRGB(x, y, noisy ? r.nextInt(0xFFFFFF) : (x * 255 / 300) << 16 | (y * 255 / 200) << 8 | 128);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(bi, fmt, o);
        return o.toByteArray();
    }

    static byte[] pngChunk(String type, byte[] data) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] t = type.getBytes(StandardCharsets.ISO_8859_1);
        o.write(data.length >>> 24); o.write(data.length >>> 16); o.write(data.length >>> 8); o.write(data.length);
        o.write(t, 0, 4); o.write(data, 0, data.length);
        CRC32 c = new CRC32(); c.update(t); c.update(data);
        long v = c.getValue();
        o.write((int) (v >>> 24)); o.write((int) (v >>> 16)); o.write((int) (v >>> 8)); o.write((int) v);
        return o.toByteArray();
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    /** Inserts extra chunks after IHDR (offset 33). */
    static byte[] pngWith(byte[] png, byte[]... chunks) {
        byte[] head = java.util.Arrays.copyOfRange(png, 0, 33);
        byte[] rest = java.util.Arrays.copyOfRange(png, 33, png.length);
        return cat(head, cat(chunks), rest);
    }

    static byte[] text(String kw, String val) {
        return pngChunk("tEXt", cat(kw.getBytes(StandardCharsets.ISO_8859_1), new byte[]{0}, val.getBytes(StandardCharsets.ISO_8859_1)));
    }

    static byte[] jpegWith(byte[] jpg, byte[] app1Payload) {
        int len = app1Payload.length + 2;
        byte[] seg = cat(new byte[]{(byte) 0xFF, (byte) 0xE1, (byte) (len >> 8), (byte) len}, app1Payload);
        return cat(java.util.Arrays.copyOfRange(jpg, 0, 2), seg, java.util.Arrays.copyOfRange(jpg, 2, jpg.length));
    }

    static byte[] exifSoftware(String sw) {
        // Big-endian TIFF: IFD0 with Make=Canon (inline) and Software (offset)
        byte[] s = (sw + "\0").getBytes(StandardCharsets.ISO_8859_1);
        int ifdOff = 8, entries = 2, dataOff = ifdOff + 2 + 12 * entries + 4;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write("MM".getBytes(), 0, 2); o.write(0); o.write(42); o.write(0); o.write(0); o.write(0); o.write(8);
        o.write(0); o.write(entries);
        // Make 0x010F ASCII count 4 "Can\0"
        o.write(0x01); o.write(0x0F); o.write(0); o.write(2); o.write(0); o.write(0); o.write(0); o.write(4);
        o.write("Can\0".getBytes(), 0, 4);
        // Software 0x0131 ASCII count n offset
        o.write(0x01); o.write(0x31); o.write(0); o.write(2); o.write(0); o.write(0); o.write(0); o.write(s.length);
        o.write(0); o.write(0); o.write(dataOff >> 8); o.write(dataOff);
        o.write(0); o.write(0); o.write(0); o.write(0); // next IFD
        o.write(s, 0, s.length);
        return cat("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1), o.toByteArray());
    }

    static AiImageReport run(byte[] data, boolean pixels) {
        return AiImageAnalyzer.analyse(ByteSource.of(data), pixels ? () -> new ByteArrayInputStream(data) : null);
    }

    static boolean has(AiImageReport r, String type) {
        for (Finding f : r.getFindings()) if (f.type.equals(type)) return true;
        return false;
    }

    static byte[] junk() { byte[] j = new byte[3000]; new Random(5).nextBytes(j); return j; }

    public static void main(String[] a) throws Exception {
        byte[] png = image("png", false);

        AiImageReport r = run(png, false);
        check("plain PNG is inconclusive", r.isInconclusive() && r.getScore() == 0, r.toComment());

        byte[] sd = pngWith(png, text("parameters",
                "a cat\nNegative prompt: blurry\nSteps: 20, Sampler: Euler a, CFG scale: 7, Seed: 1"));
        r = run(sd, false);
        check("A1111 PNG parameters -> generation field + SD provider, score 35",
                has(r, "generation_metadata_field") && r.getProviders().contains("Stable Diffusion ecosystem")
                        && r.getScore() == 35 && r.getBand() == AiImageReport.Band.INDICATORS, r.toComment());

        byte[] itxt = pngChunk("iTXt", cat("XML:com.adobe.xmp".getBytes(), new byte[]{0, 0, 0, 0, 0},
                ("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:Description xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\">"
                        + "<Iptc4xmpExt:DigitalSourceType>http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia"
                        + "</Iptc4xmpExt:DigitalSourceType></rdf:Description></x:xmpmeta>").getBytes()));
        r = run(pngWith(png, itxt), false);
        check("iTXt XMP trainedAlgorithmicMedia -> AI provenance, no Adobe false positive",
                has(r, "AI_provenance_metadata") && r.getProviders().isEmpty() && r.getScore() == 25, r.toComment());

        String camera = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:Description xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" "
                + "xmlns:Iptc4xmpExt=\"x\"><Iptc4xmpExt:DigitalSourceType>http://cv.iptc.org/newscodes/digitalsourcetype/digitalCapture"
                + "</Iptc4xmpExt:DigitalSourceType></rdf:Description></x:xmpmeta>";
        byte[] camXmp = pngChunk("iTXt", cat("XML:com.adobe.xmp".getBytes(), new byte[]{0, 0, 0, 0, 0}, camera.getBytes()));
        r = run(pngWith(png, camXmp), false);
        check("camera DigitalSourceType=digitalCapture is NOT flagged", r.isInconclusive(), r.toComment());

        Deflater d = new Deflater(); d.setInput("Steps: 30, Sampler: DPM++, CFG scale: 5".getBytes()); d.finish();
        byte[] buf = new byte[200]; int n = d.deflate(buf);
        byte[] z = pngChunk("zTXt", cat("parameters".getBytes(), new byte[]{0, 0}, java.util.Arrays.copyOf(buf, n)));
        r = run(pngWith(png, z), false);
        check("zTXt is inflated and scanned", has(r, "generation_metadata_field") && r.getProviders().contains("Stable Diffusion ecosystem"), r.toComment());

        byte[] c2pa = pngChunk("caBX", ("\0\0\0\30jumb\0\0\0\10jumdc2pa.claim_generator OpenAI "
                + "digitalsourcetype/trainedAlgorithmicMedia").getBytes());
        r = run(pngWith(png, c2pa), false);
        check("PNG caBX C2PA: present(+10) + AI marker(+25) + OpenAI(+15) = 50",
                has(r, "c2pa_manifest_present") && has(r, "AI_provenance_metadata") && r.getScore() == 50, r.toComment());

        byte[] jpg = image("jpg", false);
        r = run(jpg, false);
        check("plain JPEG is inconclusive", r.isInconclusive(), r.toComment());
        r = run(jpegWith(jpg, exifSoftware("Midjourney V6")), false);
        check("JPEG EXIF Software=Midjourney parsed -> provider",
                r.getProviders().contains("Midjourney") && r.getMetadata().getFields().size() == 2, r.toComment());
        r = run(jpegWith(jpg, exifSoftware("Adobe Photoshop 25")), false);
        check("JPEG EXIF Software=Photoshop is not flagged", r.isInconclusive(), r.toComment());
        byte[] xmpHdr = "http://ns.adobe.com/xap/1.0/\0".getBytes();
        r = run(jpegWith(jpg, cat(xmpHdr, "<x:xmpmeta><Iptc4xmpExt:DigitalSourceType>trainedAlgorithmicMedia</x:xmpmeta>".getBytes())), false);
        check("JPEG XMP AI marker", has(r, "AI_provenance_metadata"), r.toComment());

        byte[] xmp = "<x:xmpmeta>made with Google AI, Gemini</x:xmpmeta>".getBytes();
        byte[] webp = cat("RIFF".getBytes(), new byte[]{0, 0, 0, 0}, "WEBP".getBytes(), "XMP ".getBytes(),
                new byte[]{(byte) xmp.length, 0, 0, 0}, xmp, new byte[xmp.length % 2]);
        r = run(webp, false);
        check("WebP XMP chunk -> Google Gemini", r.getProviders().contains("Google Gemini / Imagen"), r.toComment());

        // Robustness
        check("empty file ok", run(new byte[0], false).isInconclusive(), "");
        byte[] junk = new byte[5000]; new Random(5).nextBytes(junk);
        check("random bytes ok", run(junk, true).getScore() >= 0, "");
        byte[] trunc = java.util.Arrays.copyOf(sd, 90);
        check("truncated PNG ok", run(trunc, true).getMetadata().getNotes().size() >= 0, "");
        byte[] evil = pngWith(png, java.util.Arrays.copyOf(new byte[]{0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 't', 'E', 'X', 't'}, 8));
        check("PNG chunk with huge length ok", run(evil, false) != null, "");
        byte[] bomb = new byte[50_000_000 / 50]; // zlib bomb: 1MB of zeros compresses to ~1KB; 200MB test below
        Deflater bd = new Deflater(9); bd.setInput(new byte[64 * 1024 * 1024]); bd.finish();
        byte[] bb = new byte[1 << 20]; int bn = bd.deflate(bb);
        byte[] bombChunk = pngChunk("zTXt", cat("x".getBytes(), new byte[]{0, 0}, java.util.Arrays.copyOf(bb, bn)));
        long t0 = System.currentTimeMillis();
        r = run(pngWith(png, bombChunk), false);
        check("zTXt inflate bomb is capped and noted",
                System.currentTimeMillis() - t0 < 3000 && r.getMetadata().getNotes().toString().contains("truncated"),
                r.getMetadata().getNotes().toString());

        // Classifier plumbing (fake classifier; the ONNX model itself is not exercised here)
        AiClassifier fake = in -> ClassifierResult.fromLogits("fake.onnx", 3.0, -1.0);
        r = AiImageAnalyzer.analyse(ByteSource.of(png), () -> new ByteArrayInputStream(png), false, fake);
        check("classifier result attached; metadata score untouched",
                r.getClassifierResult() != null && r.getScore() == 0 && r.isInconclusive()
                        && r.classifierFlags(0.9) && !r.classifierFlags(0.99)
                        && r.toComment().contains("artificial 98.2%") && r.getJustification().contains("98% artificial"),
                r.toComment());
        ClassifierResult sm = ClassifierResult.fromLogits("m", 1000.0, 1000.0);
        check("softmax is stable and symmetric", Math.abs(sm.artificial - 0.5) < 1e-12, String.valueOf(sm.artificial));
        AiClassifier broken = in -> { throw new java.io.IOException("boom"); };
        r = AiImageAnalyzer.analyse(ByteSource.of(png), () -> new ByteArrayInputStream(png), false, broken);
        check("classifier failure becomes a note", r.getClassifierResult() == null && r.toComment().contains("Classifier: not computed"), r.toComment());
        AiClassifier undecodable = in -> null;
        r = AiImageAnalyzer.analyse(ByteSource.of(junk()), () -> new ByteArrayInputStream(junk()), false, undecodable);
        check("undecodable image noted", r.toComment().contains("not decodable"), r.toComment());
        AiClassifier missingLib = in -> { throw new NoClassDefFoundError("ai/onnxruntime/OrtEnvironment"); };
        r = AiImageAnalyzer.analyse(ByteSource.of(png), () -> new ByteArrayInputStream(png), false, missingLib);
        check("missing ONNX runtime (LinkageError) is caught", r.toComment().contains("ONNX runtime unavailable"), r.toComment());

        // Pixel stats
        r = run(png, true);
        PixelStats p = r.getPixelStats();
        check("pixel stats computed for PNG", p != null && p.width == 300 && p.fftSize == 128, String.valueOf(r.toComment()));
        PixelStats noisy = run(image("png", true), true).getPixelStats();
        check("noise image has much higher residual and HF ratio than gradient",
                noisy.residualStd > 10 * p.residualStd && noisy.highFrequencyEnergyRatio > p.highFrequencyEnergyRatio,
                noisy + "\n" + p);
        check("noise entropy ~8 bits", noisy.grayscaleEntropyBits > 7.0, String.valueOf(noisy.grayscaleEntropyBits));
        r = run(image("jpg", false), true);
        check("pixel stats computed for JPEG", r.getPixelStats() != null, r.toComment());
        check("pixel failure is a note, not an exception", run(junk, true).toComment().contains("Pixel statistics:"), "");

        System.out.println("\n--- sample comment ---\n" + run(pngWith(png, text("parameters", "x\nSteps: 20, Sampler: Euler a, CFG scale: 7"), c2pa), true).toComment());
        System.out.println(failures == 0 ? "\nALL PASSED" : "\nFAILURES: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}

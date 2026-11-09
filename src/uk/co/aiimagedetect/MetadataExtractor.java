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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reads embedded metadata by walking the container structure (PNG chunks, JPEG
 * segments, WebP RIFF chunks) so that pixel data is never read or decoded.
 * Replaces the Pillow / ExifTool / c2patool metadata step of the Python tool.
 *
 * C2PA manifests are detected but their signatures are NOT validated.
 *
 * Every size read from the file is bounded, inflated data is capped, and the
 * number of structure items walked is capped, so hostile files cannot cause
 * large allocations or long loops.
 */
public final class MetadataExtractor {

    private static final int MAX_ITEMS = 20_000;
    private static final int MAX_CHUNK_BYTES = 8 * 1024 * 1024;
    private static final int MAX_INFLATED = 1024 * 1024;
    private static final int GENERIC_SCAN_BYTES = 1024 * 1024;
    private static final int MAX_STRINGS_CHARS = 1024 * 1024;
    private static final Charset LATIN1 = StandardCharsets.ISO_8859_1;
    private static final Charset UTF8 = StandardCharsets.UTF_8;
    private static final byte[] PNG_SIG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final String EXIF_HDR = "Exif\0\0";
    private static final String XMP_HDR = "http://ns.adobe.com/xap/1.0/\0";
    private static final String XMP_EXT_HDR = "http://ns.adobe.com/xmp/extension/\0";

    private MetadataExtractor() {
    }

    public static ExtractedMetadata extract(ByteSource src) {
        ExtractedMetadata md = new ExtractedMetadata();
        try {
            final long len = src.length();
            byte[] head = readFully(src, 0, (int) Math.min(12, len));
            if (head == null || head.length < 4) {
                md.notes.add("File too short to contain image metadata");
                return md;
            }
            if (startsWith(head, PNG_SIG)) {
                md.container = "PNG";
                parsePng(src, md);
            } else if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8) {
                md.container = "JPEG";
                parseJpeg(src, md);
            } else if (head.length >= 12 && "RIFF".equals(ascii(head, 0, 4)) && "WEBP".equals(ascii(head, 8, 4))) {
                md.container = "WebP";
                parseWebp(src, md);
            } else {
                md.container = "other";
                parseGeneric(src, md);
            }
        } catch (IOException | RuntimeException e) {
            md.notes.add("Metadata parsing stopped early: " + e);
        }
        return md;
    }

    // ------------------------------------------------------------------ PNG

    private static void parsePng(ByteSource src, ExtractedMetadata md) throws IOException {
        final long len = src.length();
        long pos = 8;
        int items = 0;
        while (pos + 12 <= len && items++ < MAX_ITEMS) {
            byte[] h = readFully(src, pos, 8);
            if (h == null) {
                break;
            }
            long clen = u32be(h, 0);
            String type = ascii(h, 4, 4);
            long dataPos = pos + 8;
            if (clen > len - dataPos - 4) {
                md.notes.add("PNG chunk " + type + " runs past end of file");
                break;
            }
            boolean wanted = "tEXt".equals(type) || "zTXt".equals(type) || "iTXt".equals(type)
                    || "eXIf".equals(type) || "caBX".equals(type);
            if (wanted) {
                if (clen > MAX_CHUNK_BYTES) {
                    md.notes.add("PNG chunk " + type + " too large to read (" + clen + " bytes)");
                } else {
                    byte[] d = readFully(src, dataPos, (int) clen);
                    if (d != null) {
                        pngChunk(md, type, d);
                    }
                }
            }
            if ("IEND".equals(type)) {
                break;
            }
            pos = dataPos + clen + 4;
        }
        if (items >= MAX_ITEMS) {
            md.notes.add("PNG chunk walk stopped after " + MAX_ITEMS + " chunks");
        }
    }

    private static void pngChunk(ExtractedMetadata md, String type, byte[] d) {
        if ("eXIf".equals(type)) {
            parseExif(md, d);
            return;
        }
        if ("caBX".equals(type)) {
            handleC2pa(md, d);
            return;
        }
        int z = indexOf0(d, 0);
        if (z < 0) {
            return;
        }
        String kw = new String(d, 0, z, LATIN1);
        String src = "PNG:" + type;
        String text;
        if ("tEXt".equals(type)) {
            text = new String(d, z + 1, d.length - z - 1, LATIN1);
        } else if ("zTXt".equals(type)) {
            if (z + 2 > d.length) {
                return;
            }
            byte[] raw = inflate(md, d, z + 2, d.length - z - 2);
            text = raw == null ? null : new String(raw, LATIN1);
        } else { // iTXt: keyword\0 flag method lang\0 translated\0 text
            if (z + 3 > d.length) {
                return;
            }
            boolean compressed = d[z + 1] != 0;
            int j = indexOf0(d, z + 3);
            int k = j < 0 ? -1 : indexOf0(d, j + 1);
            if (k < 0) {
                return;
            }
            if (compressed) {
                byte[] raw = inflate(md, d, k + 1, d.length - k - 1);
                text = raw == null ? null : new String(raw, UTF8);
            } else {
                text = new String(d, k + 1, d.length - k - 1, UTF8);
            }
        }
        if ("XML:com.adobe.xmp".equals(kw)) {
            md.add("XMP", "packet", text);
        } else {
            md.add(src, kw, text);
        }
    }

    // ----------------------------------------------------------------- JPEG

    private static void parseJpeg(ByteSource src, ExtractedMetadata md) throws IOException {
        final long len = src.length();
        long pos = 2;
        int items = 0;
        ByteArrayOutputStream jumbf = new ByteArrayOutputStream();
        while (pos + 2 <= len && items++ < MAX_ITEMS) {
            byte[] m = readFully(src, pos, 2);
            if (m == null) {
                break;
            }
            if ((m[0] & 0xFF) != 0xFF) {
                md.notes.add("Unexpected byte in JPEG marker stream; metadata walk stopped");
                break;
            }
            int marker = m[1] & 0xFF;
            if (marker == 0xFF) {            // fill byte
                pos++;
                continue;
            }
            if (marker == 0x00 || marker == 0x01 || marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7)) {
                pos += 2;                    // markers without a length
                continue;
            }
            if (marker == 0xD9 || marker == 0xDA) {
                break;                       // EOI / start of scan: headers are done
            }
            byte[] l = readFully(src, pos + 2, 2);
            if (l == null) {
                break;
            }
            int seglen = u16be(l, 0);
            if (seglen < 2) {
                break;
            }
            long dataPos = pos + 4;
            int dlen = seglen - 2;
            if (dataPos + dlen > len) {
                md.notes.add("JPEG segment runs past end of file");
                break;
            }
            if (marker == 0xE1 || marker == 0xEB || marker == 0xFE || marker == 0xED) {
                byte[] d = readFully(src, dataPos, dlen);
                if (d == null) {
                    break;
                }
                jpegSegment(md, marker, d, jumbf);
            }
            pos = dataPos + dlen;
        }
        if (jumbf.size() > 0) {
            byte[] j = jumbf.toByteArray();
            if (indexOf(j, "c2pa".getBytes(LATIN1)) >= 0) {
                md.c2paPresent = true;
                md.c2paStrings.add(printableStrings(j, 0, j.length));
            } else if (indexOf(j, "jumb".getBytes(LATIN1)) >= 0) {
                md.notes.add("JUMBF container present but it is not labelled c2pa");
            }
        }
    }

    private static void jpegSegment(ExtractedMetadata md, int marker, byte[] d, ByteArrayOutputStream jumbf) {
        switch (marker) {
            case 0xE1:
                if (startsWithAscii(d, EXIF_HDR)) {
                    parseExif(md, Arrays.copyOfRange(d, EXIF_HDR.length(), d.length));
                } else if (startsWithAscii(d, XMP_HDR)) {
                    md.add("XMP", "packet", new String(d, XMP_HDR.length(), d.length - XMP_HDR.length(), UTF8));
                } else if (startsWithAscii(d, XMP_EXT_HDR)) {
                    md.add("XMP", "extension", printableStrings(d, XMP_EXT_HDR.length(), d.length - XMP_EXT_HDR.length()));
                }
                break;
            case 0xEB: // APP11: JUMBF (C2PA) is split across segments
                if (jumbf.size() < 8 * 1024 * 1024) {
                    jumbf.write(d, 0, d.length);
                }
                break;
            case 0xFE:
                md.add("JPEG:COM", "comment", new String(d, UTF8));
                break;
            case 0xED: // APP13: Photoshop resources / IPTC
                md.add("APP13", "strings", printableStrings(d, 0, d.length));
                break;
            default:
                break;
        }
    }

    // ----------------------------------------------------------------- WebP

    private static void parseWebp(ByteSource src, ExtractedMetadata md) throws IOException {
        final long len = src.length();
        long pos = 12;
        int items = 0;
        while (pos + 8 <= len && items++ < MAX_ITEMS) {
            byte[] h = readFully(src, pos, 8);
            if (h == null) {
                break;
            }
            String type = ascii(h, 0, 4);
            long size = u32le(h, 4);
            long dataPos = pos + 8;
            if (size > len - dataPos) {
                md.notes.add("WebP chunk " + type + " runs past end of file");
                break;
            }
            boolean wanted = "EXIF".equals(type) || "XMP ".equals(type) || "C2PA".equals(type);
            if (wanted && size <= MAX_CHUNK_BYTES) {
                byte[] d = readFully(src, dataPos, (int) size);
                if (d != null) {
                    if ("EXIF".equals(type)) {
                        parseExif(md, startsWithAscii(d, EXIF_HDR)
                                ? Arrays.copyOfRange(d, EXIF_HDR.length(), d.length) : d);
                    } else if ("XMP ".equals(type)) {
                        md.add("XMP", "packet", new String(d, UTF8));
                    } else {
                        handleC2pa(md, d);
                    }
                }
            }
            pos = dataPos + size + (size & 1);
        }
    }

    // -------------------------------------------------- other / generic scan

    private static void parseGeneric(ByteSource src, ExtractedMetadata md) throws IOException {
        int n = (int) Math.min(src.length(), GENERIC_SCAN_BYTES);
        byte[] buf = readFully(src, 0, n);
        if (buf == null || buf.length < 8) {
            return;
        }
        boolean tiff = (buf[0] == 'I' && buf[1] == 'I' && buf[2] == 42 && buf[3] == 0)
                || (buf[0] == 'M' && buf[1] == 'M' && buf[2] == 0 && buf[3] == 42);
        if (tiff) {
            md.container = "TIFF";
            parseExif(md, buf);
        }
        String s = new String(buf, LATIN1); // same offsets as the bytes
        int a = s.indexOf("<x:xmpmeta");
        if (a >= 0) {
            int b = s.indexOf("</x:xmpmeta>", a);
            if (b > a) {
                md.add("XMP", "packet", new String(buf, a, b + 12 - a, UTF8));
            }
        }
        if (indexOf(buf, "c2pa".getBytes(LATIN1)) >= 0) {
            md.possibleC2paMarker = true;
        }
    }

    // ----------------------------------------------------------------- C2PA

    private static void handleC2pa(ExtractedMetadata md, byte[] d) {
        if (indexOf(d, "c2pa".getBytes(LATIN1)) >= 0) {
            md.c2paPresent = true;
            md.c2paStrings.add(printableStrings(d, 0, d.length));
        } else {
            md.notes.add("JUMBF container present but it is not labelled c2pa");
        }
    }

    // ----------------------------------------------------------------- EXIF

    private static void parseExif(ExtractedMetadata md, byte[] t) {
        try {
            if (t.length < 8) {
                return;
            }
            boolean le;
            if (t[0] == 'I' && t[1] == 'I') {
                le = true;
            } else if (t[0] == 'M' && t[1] == 'M') {
                le = false;
            } else {
                return;
            }
            if (rd16(t, 2, le) != 42) {
                return; // BigTIFF and other variants are not parsed
            }
            readIfd(md, t, rd32(t, 4, le), le, 0, new HashSet<Long>());
        } catch (RuntimeException e) {
            md.notes.add("EXIF parse error: " + e);
        }
    }

    private static void readIfd(ExtractedMetadata md, byte[] t, long off, boolean le, int depth, Set<Long> seen) {
        if (depth > 3 || off < 8 || off + 2 > t.length || !seen.add(off)) {
            return;
        }
        int n = Math.min(rd16(t, (int) off, le), 1000);
        for (int i = 0; i < n; i++) {
            long e = off + 2 + 12L * i;
            if (e + 12 > t.length) {
                break;
            }
            int ei = (int) e;
            int tag = rd16(t, ei, le);
            int type = rd16(t, ei + 2, le);
            long count = rd32(t, ei + 4, le);
            if (tag == 0x8769) {                       // pointer to the Exif sub-IFD
                readIfd(md, t, rd32(t, ei + 8, le), le, depth + 1, seen);
                continue;
            }
            String name = exifTagName(tag);
            int sz = typeSize(type);
            if (name == null || sz == 0 || count <= 0) {
                continue;
            }
            long total = sz * count;
            long vpos = total <= 4 ? e + 8 : rd32(t, ei + 8, le);
            if (total > 65536 || vpos < 0 || vpos + total > t.length) {
                continue;
            }
            byte[] v = Arrays.copyOfRange(t, (int) vpos, (int) (vpos + total));
            if (tag == 0x02BC) {
                md.add("XMP", "packet", new String(v, UTF8));
            } else if (tag == 0x9286) {
                md.add("EXIF", name, decodeUserComment(v, le));
            } else if (type == 2 || type == 7) {
                md.add("EXIF", name, stripNul(new String(v, UTF8)));
            }
        }
    }

    private static String exifTagName(int tag) {
        switch (tag) {
            case 0x010E: return "ImageDescription";
            case 0x010F: return "Make";
            case 0x0110: return "Model";
            case 0x0131: return "Software";
            case 0x0132: return "DateTime";
            case 0x013B: return "Artist";
            case 0x8298: return "Copyright";
            case 0x9003: return "DateTimeOriginal";
            case 0x9286: return "UserComment";
            case 0xA434: return "LensModel";
            case 0x02BC: return "XMP";
            default: return null;
        }
    }

    private static int typeSize(int type) {
        switch (type) {
            case 1: case 2: case 6: case 7: return 1;
            case 3: case 8: return 2;
            case 4: case 9: case 11: return 4;
            case 5: case 10: case 12: return 8;
            default: return 0;
        }
    }

    private static String decodeUserComment(byte[] v, boolean tiffLittleEndian) {
        if (v.length <= 8) {
            return "";
        }
        String prefix = new String(v, 0, 8, LATIN1);
        int off = 8;
        if (prefix.startsWith("UNICODE")) {
            int even = 0, odd = 0;
            for (int i = off; i < v.length; i++) {
                if (v[i] == 0) {
                    if (((i - off) & 1) == 0) {
                        even++;
                    } else {
                        odd++;
                    }
                }
            }
            Charset cs;
            if (v.length >= off + 2 && (v[off] & 0xFF) == 0xFF && (v[off + 1] & 0xFF) == 0xFE) {
                cs = StandardCharsets.UTF_16LE;
                off += 2;
            } else if (v.length >= off + 2 && (v[off] & 0xFF) == 0xFE && (v[off + 1] & 0xFF) == 0xFF) {
                cs = StandardCharsets.UTF_16BE;
                off += 2;
            } else if (even != odd) {
                cs = even > odd ? StandardCharsets.UTF_16BE : StandardCharsets.UTF_16LE;
            } else {
                cs = tiffLittleEndian ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE;
            }
            return stripNul(new String(v, off, v.length - off, cs));
        }
        return stripNul(new String(v, off, v.length - off, UTF8));
    }

    // -------------------------------------------------------------- helpers

    /** @return exactly len bytes from offset, or null if the data ends first */
    private static byte[] readFully(ByteSource src, long offset, int len) throws IOException {
        if (len < 0) {
            return null;
        }
        byte[] buf = new byte[len];
        int got = 0;
        while (got < len) {
            byte[] tmp = got == 0 ? buf : new byte[len - got];
            int n = src.read(tmp, offset + got, len - got);
            if (n <= 0) {
                return null;
            }
            if (tmp != buf) {
                System.arraycopy(tmp, 0, buf, got, n);
            }
            got += n;
        }
        return buf;
    }

    private static byte[] inflate(ExtractedMetadata md, byte[] d, int off, int len) {
        Inflater inf = new Inflater();
        try {
            inf.setInput(d, off, len);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!inf.finished() && out.size() < MAX_INFLATED) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) {
                    break;
                }
                out.write(buf, 0, n);
            }
            if (!inf.finished() && out.size() >= MAX_INFLATED) {
                md.notes.add("Compressed text chunk truncated at " + MAX_INFLATED + " bytes");
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            md.notes.add("Corrupt compressed text chunk: " + e.getMessage());
            return null;
        } finally {
            inf.end();
        }
    }

    /** Runs of 4+ printable ASCII characters, one per line (like strings(1)). */
    static String printableStrings(byte[] d, int off, int len) {
        StringBuilder out = new StringBuilder();
        StringBuilder run = new StringBuilder();
        int end = Math.min(d.length, off + len);
        for (int i = off; i <= end; i++) {
            int c = i < end ? (d[i] & 0xFF) : -1;
            if (c >= 0x20 && c <= 0x7E) {
                run.append((char) c);
            } else {
                if (run.length() >= 4) {
                    out.append(run).append('\n');
                }
                run.setLength(0);
                if (out.length() > MAX_STRINGS_CHARS) {
                    break;
                }
            }
        }
        return out.toString();
    }

    private static String stripNul(String s) {
        int i = s.indexOf('\0');
        return i >= 0 ? s.substring(0, i) : s;
    }

    private static int indexOf0(byte[] d, int from) {
        for (int i = Math.max(0, from); i < d.length; i++) {
            if (d[i] == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static boolean startsWith(byte[] d, byte[] sig) {
        if (d.length < sig.length) {
            return false;
        }
        for (int i = 0; i < sig.length; i++) {
            if (d[i] != sig[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWithAscii(byte[] d, String s) {
        return startsWith(d, s.getBytes(LATIN1));
    }

    private static String ascii(byte[] d, int off, int len) {
        return new String(d, off, len, LATIN1);
    }

    private static long u32be(byte[] b, int o) {
        return ((long) (b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static long u32le(byte[] b, int o) {
        return ((long) (b[o + 3] & 0xFF) << 24) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 1] & 0xFF) << 8) | (b[o] & 0xFF);
    }

    private static int u16be(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    private static int rd16(byte[] b, int o, boolean le) {
        return le ? ((b[o + 1] & 0xFF) << 8) | (b[o] & 0xFF) : u16be(b, o);
    }

    private static long rd32(byte[] b, int o, boolean le) {
        return le ? u32le(b, o) : u32be(b, o);
    }
}

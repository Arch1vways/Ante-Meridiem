package dev.antemeridiem;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** Minimal sfnt reader: family / style name and the 'wght' axis of TrueType / OpenType (also .ttc, first font). */
final class FontInfo {
    String family, subfamily, fullName;
    boolean hasWeightAxis;
    float weightMin, weightDefault, weightMax;
    /** Outline format, for display: "TrueType", "OpenType CFF", "OpenType CFF2". */
    String format;

    /** Thrown for files that are not usable fonts; the message is shown to the user. */
    static final class BadFontException extends IOException {
        final int reason;
        static final int NOT_A_FONT = 0, WOFF = 1;

        BadFontException(int reason, String message) {
            super(message);
            this.reason = reason;
        }
    }

    private FontInfo() { }

    static FontInfo read(java.io.File file) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            return read(f);
        }
    }

    private static FontInfo read(RandomAccessFile f) throws IOException {
        long base = 0;
        int tag = readInt(f, 0);
        if (tag == 0x774F4646 || tag == 0x774F4632) {             // 'wOFF' / 'wOF2'
            throw new BadFontException(BadFontException.WOFF, "WOFF fonts are not supported");
        }
        if (tag == 0x74746366) {                                     // 'ttcf': use the first font of the collection
            base = readInt(f, 12) & 0xFFFFFFFFL;
            tag = readInt(f, base);
        }
        if (tag != 0x00010000 && tag != 0x4F54544F && tag != 0x74727565) {   // 1.0 / 'OTTO' / 'true'
            throw new BadFontException(BadFontException.NOT_A_FONT, "not a TrueType/OpenType font");
        }
        int numTables = readUShort(f, base + 4);
        if (numTables <= 0 || numTables > 200) throw new BadFontException(BadFontException.NOT_A_FONT, "bad table directory");
        FontInfo info = new FontInfo();
        long nameOff = -1, fvarOff = -1;
        boolean glyf = false, cff = false, cff2 = false;
        for (int i = 0; i < numTables; i++) {
            long rec = base + 12 + 16L * i;
            int t = readInt(f, rec);
            long off = readInt(f, rec + 8) & 0xFFFFFFFFL;
            if (t == 0x6E616D65) nameOff = off;          // 'name'
            else if (t == 0x66766172) fvarOff = off;     // 'fvar'
            else if (t == 0x676C7966) glyf = true;       // 'glyf'
            else if (t == 0x43464620) cff = true;        // 'CFF '
            else if (t == 0x43464632) cff2 = true;       // 'CFF2'
        }
        if (!glyf && !cff && !cff2) throw new BadFontException(BadFontException.NOT_A_FONT, "no glyph outlines");
        info.format = glyf ? "TrueType" : cff2 ? "OpenType CFF2" : "OpenType CFF";
        if (nameOff >= 0) readNames(f, nameOff, info);
        if (fvarOff >= 0) readFvar(f, fvarOff, info);
        return info;
    }

    private static void readNames(RandomAccessFile f, long off, FontInfo info) throws IOException {
        int count = readUShort(f, off + 2);
        long strings = off + readUShort(f, off + 4);
        String[] best = new String[18];
        int[] score = new int[18];
        for (int i = 0; i < Math.min(count, 2000); i++) {
            long r = off + 6 + 12L * i;
            int platform = readUShort(f, r), encoding = readUShort(f, r + 2), lang = readUShort(f, r + 4);
            int nameId = readUShort(f, r + 6), len = readUShort(f, r + 8), so = readUShort(f, r + 10);
            if (nameId >= best.length || len == 0 || len > 1024) continue;
            int s;
            if (platform == 3 && (encoding == 1 || encoding == 10)) s = lang == 0x409 ? 4 : 3;
            else if (platform == 0) s = 2;
            else if (platform == 1 && encoding == 0) s = lang == 0 ? 1 : 0;
            else continue;
            if (s <= score[nameId] && best[nameId] != null) continue;
            byte[] b = new byte[len];
            f.seek(strings + so);
            f.readFully(b);
            String v = platform == 1 ? new String(b, StandardCharsets.ISO_8859_1) : new String(b, StandardCharsets.UTF_16BE);
            v = v.trim();
            if (v.isEmpty()) continue;
            best[nameId] = v;
            score[nameId] = s;
        }
        info.family = clean(best[16] != null ? best[16] : best[1]);
        info.subfamily = clean(best[17] != null ? best[17] : best[2]);
        info.fullName = clean(best[4]);
    }

    /** Apple's system fonts hide their names behind a leading dot. */
    private static String clean(String s) {
        if (s == null) return null;
        while (s.startsWith(".")) s = s.substring(1);
        return s.isEmpty() ? null : s;
    }

    private static void readFvar(RandomAccessFile f, long off, FontInfo info) throws IOException {
        int axesOff = readUShort(f, off + 4);
        int axisCount = readUShort(f, off + 8), axisSize = readUShort(f, off + 10);
        if (axisSize < 20) return;
        for (int i = 0; i < Math.min(axisCount, 64); i++) {
            long a = off + axesOff + (long) axisSize * i;
            if (readInt(f, a) == 0x77676874) {           // 'wght'
                info.hasWeightAxis = true;
                info.weightMin = fixed(readInt(f, a + 4));
                info.weightDefault = fixed(readInt(f, a + 8));
                info.weightMax = fixed(readInt(f, a + 12));
            }
        }
    }

    /** Name for the font list: family, plus the style unless it is the regular one. */
    String displayName() {
        if (family == null) return fullName;
        if (subfamily == null || subfamily.equalsIgnoreCase("Regular") || family.endsWith(subfamily)) return family;
        return family + " " + subfamily;
    }

    private static float fixed(int v) {
        return v / 65536f;
    }

    private static int readInt(RandomAccessFile f, long pos) throws IOException {
        if (pos < 0 || pos + 4 > f.length()) throw new BadFontException(BadFontException.NOT_A_FONT, "truncated font");
        f.seek(pos);
        return f.readInt();
    }

    private static int readUShort(RandomAccessFile f, long pos) throws IOException {
        if (pos < 0 || pos + 2 > f.length()) throw new BadFontException(BadFontException.NOT_A_FONT, "truncated font");
        f.seek(pos);
        return f.readUnsignedShort();
    }
}

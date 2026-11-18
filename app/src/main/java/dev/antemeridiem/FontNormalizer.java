package dev.antemeridiem;

import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Brings a font to the size of vivo's own clock font. The clock styles of the editor use fixed text sizes, so a
 * font whose digits are small in its em square looked smaller than the stock fonts. The fix scales the whole font
 * by rewriting head.unitsPerEm (outlines, advances, kerning and variations stay untouched and Android scales all of
 * them by the new em) and gives it line metrics that fit the scaled digits. The clock-only copy also gets a digit-based head bounding box: Android uses the global box for extra font padding, which otherwise shifts fonts with tall unrelated glyphs. The fonts in the APK are prepared the
 * same way by tools/prepare_fonts.py; this class does it for the user's fonts when they are added.
 */
final class FontNormalizer {
    /** Bumped when the rule changes; part of the cache name of user fonts in the hooked processes. */
    static final int VERSION = 3;
    /** Digit height of vivo's clock font ("vivo 0328 Inline"), in em. */
    static final float TARGET_DIGIT_HEIGHT = 0.72f;
    /** Very wide fonts are scaled by width instead, so the clock does not overflow. */
    static final float MAX_DIGIT_ADVANCE = 0.80f;
    static final float ASCENT = 0.93f, DESCENT = 0.24f, PAD = 0.05f;
    private static final float SIZE = 1000f;

    private FontNormalizer() { }

    /** Digits of a font at its default instance: top, bottom (em, y up) and mean advance (em). */
    static final class Digits {
        final float top, bottom, advance;

        Digits(float top, float bottom, float advance) {
            this.top = top;
            this.bottom = bottom;
            this.advance = advance;
        }
    }

    /** Same rule as scale_for in tools/prepare_fonts.py. */
    static float scaleFor(float top, float bottom, float advance) {
        float height = height(top, bottom);
        if (!(height > 0.01f)) return 1f;
        float s = TARGET_DIGIT_HEIGHT / height;
        if (advance > 0 && advance * s > MAX_DIGIT_ADVANCE) s = MAX_DIGIT_ADVANCE / advance;
        s = Math.max(0.5f, Math.min(2f, s));
        return Math.abs(s - 1f) < 0.02f ? 1f : s;
    }

    /** Digits that clearly hang below the baseline count with their full height. */
    static float height(float top, float bottom) {
        return -bottom > 0.1f * top ? top - bottom : top;
    }

    static Digits measure(File file) throws IOException {
        Font font = new Font.Builder(file).build();
        Typeface tf = new Typeface.CustomFallbackBuilder(new FontFamily.Builder(font).build()).build();
        return measureTypeface(tf, "0123456789");
    }

    private static Digits measureTypeface(Typeface tf, String chars) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(tf);
        p.setTextSize(SIZE);
        Rect r = new Rect();
        p.getTextBounds(chars, 0, chars.length(), r);
        return new Digits(Math.max(0, -r.top) / SIZE, Math.min(0, -r.bottom) / SIZE,
                p.measureText(chars) / chars.length() / SIZE);
    }

    /** Include clock punctuation and both ends of the adjustable weight axis in the safe bounds. */
    static Digits clockBounds(File file) throws IOException {
        String chars = "0123456789:：٫٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹";
        Typeface tf = new Typeface.Builder(file).build();
        Digits d = measureTypeface(tf, chars);
        float top = d.top, bottom = d.bottom;
        FontInfo info = FontInfo.read(file);
        if (info.hasWeightAxis) {
            for (float w : new float[]{info.weightMin, info.weightMax}) {
                if (!Float.isFinite(w)) continue;
                try {
                    Typeface variant = new Typeface.Builder(file).setFontVariationSettings(
                            String.format(java.util.Locale.ROOT, "'wght' %.4f", w)).build();
                    Digits v = measureTypeface(variant, chars);
                    top = Math.max(top, v.top);
                    bottom = Math.min(bottom, v.bottom);
                } catch (RuntimeException ignored) {
                    // Some malformed axes cannot be instantiated. Keep the default instance bounds.
                }
            }
        }
        return new Digits(top, bottom, d.advance);
    }

    /**
     * Copies {@code in} to {@code out} with the size fixed. Returns the scale that was applied (1 when the font
     * already has the right size or its tables could not be patched). Metrics may still be adjusted at scale 1.
     */
    static float normalize(File in, File out) throws IOException {
        Digits d = measure(in);
        Digits bounds = clockBounds(in);
        FontResolver.copyFile(in, out);
        try (RandomAccessFile f = new RandomAccessFile(out, "rw")) {
            return patch(f, d, bounds, scaleFor(d.top, d.bottom, d.advance));
        }
    }

    /** Rewrites metrics and clock-only bounding box of the (first) font in the file. */
    static float patch(RandomAccessFile f, Digits d, float scale) throws IOException {
        return patch(f, d, d, scale);
    }

    static float patch(RandomAccessFile f, Digits d, Digits bounds, float scale) throws IOException {
        long base = 0;
        f.seek(0);
        int tag = f.readInt();
        if (tag == 0x74746366) {          // 'ttcf': patch the first font of the collection, the one Android loads
            f.seek(12);
            base = f.readInt() & 0xFFFFFFFFL;
            f.seek(base);
            f.readInt();
        }
        int numTables = f.readUnsignedShort();
        if (numTables <= 0 || numTables > 512) return 1f;
        long head = -1, hhea = -1, os2 = -1, os2Len = 0;
        for (int i = 0; i < numTables; i++) {
            f.seek(base + 12 + 16L * i);
            int t = f.readInt();
            f.readInt();                                  // checksum: not verified by Android
            long off = f.readInt() & 0xFFFFFFFFL;
            long len = f.readInt() & 0xFFFFFFFFL;
            if (off + len > f.length()) continue;
            if (t == 0x68656164 && len >= 54) head = off;             // 'head'
            else if (t == 0x68686561 && len >= 36) hhea = off;        // 'hhea'
            else if (t == 0x4F532F32) {                              // 'OS/2'
                os2 = off;
                os2Len = len;
            }
        }
        if (head < 0) return 1f;
        f.seek(head + 18);
        int old = f.readUnsignedShort();
        if (old < 16) return 1f;
        int upem = Math.max(16, Math.min(16384, Math.round(old / scale)));
        float s = old / (float) upem;
        f.seek(head + 18);
        f.writeShort(upem);
        int asc = Math.round(Math.max(ASCENT, bounds.top * s + PAD) * upem);
        int desc = Math.round(Math.min(-DESCENT, bounds.bottom * s - PAD) * upem);
        asc = Math.min(asc, Short.MAX_VALUE);
        desc = Math.max(desc, Short.MIN_VALUE);
        // Android uses head.yMin/yMax for Paint.FontMetrics.top/bottom, not just hhea.
        // Other glyphs remain intact, but their original global bounds are intentionally not used:
        // this copy is solely for a clock and must not inherit unrelated accents' padding.
        f.seek(head + 38);
        f.writeShort(desc);
        f.seek(head + 42);
        f.writeShort(asc);
        if (hhea >= 0) {
            f.seek(hhea + 4);
            f.writeShort(asc);
            f.writeShort(desc);
            f.writeShort(0);
        }
        if (os2 >= 0 && os2Len >= 78) {
            f.seek(os2 + 68);
            f.writeShort(asc);
            f.writeShort(desc);
            f.writeShort(0);
            f.writeShort(asc);
            f.writeShort(-desc);
        }
        return s;
    }
}

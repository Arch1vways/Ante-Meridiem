package dev.antemeridiem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowNativeFont.ShadowNativeFontBuilder;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/** Uses Android's real font stack (Robolectric native graphics: minikin, HarfBuzz, FreeType, Skia). */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35)
public class NativeFontTest {
    private static Bitmap render(Typeface tf, String text) {
        Bitmap b = Bitmap.createBitmap(400, 160, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTypeface(tf);
        p.setTextSize(90);
        c.drawText(text, 10, 120, p);
        return b;
    }

    private static int ink(Bitmap b) {
        int n = 0;
        for (int y = 0; y < b.getHeight(); y++) {
            for (int x = 0; x < b.getWidth(); x++) {
                if (Color.red(b.getPixel(x, y)) < 128) n++;
            }
        }
        return n;
    }

    @Test public void everyBuiltinFontLoadsAndDrawsDigits() throws Exception {
        for (FontSpec s : BuiltinFonts.ALL) {
            File f = BuiltinFontsTest.asset(s);
            new Font.Builder(f).build();              // throws when Android cannot read the font
            FontStore.checkRenders(f);                // has 0-9 itself
            Typeface tf = new Typeface.Builder(f).setFontVariationSettings(s.variationSettings(s.weightDefault)).build();
            assertTrue(s.id + " draws nothing", ink(render(tf, "10:08")) > 300);
            if (s.supportsWeight()) {
                int thin = ink(render(new Typeface.Builder(f).setFontVariationSettings(s.variationSettings(s.weightMin)).build(), "10:08"));
                int bold = ink(render(new Typeface.Builder(f).setFontVariationSettings(s.variationSettings(s.weightMax)).build(), "10:08"));
                assertTrue(s.id + " weight axis has no effect: " + thin + " vs " + bold,
                        Math.abs(bold - thin) > 0.1 * Math.max(bold, thin));
            }
        }
    }

    @Test public void thumbnailDrawsTheFont() {
        FontSpec s = BuiltinFonts.byId("lf.dm_serif");
        Typeface tf = new Typeface.Builder(BuiltinFontsTest.asset(s)).build();
        FontThumb d = new FontThumb(tf, Color.BLACK, 108);
        assertEquals(108, d.getIntrinsicWidth());
        Bitmap b = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.WHITE);
        d.setBounds(0, 0, 108, 108);
        d.draw(c);
        int top = -1, bottom = -1;
        for (int y = 0; y < 108; y++) {
            for (int x = 0; x < 108; x++) {
                if (Color.red(b.getPixel(x, y)) < 128) {
                    if (top < 0) top = y;
                    bottom = y;
                }
            }
        }
        assertTrue(top > 10 && bottom < 98);
        assertEquals(0.6 * 108, bottom - top, 6);
        assertEquals(Color.BLACK, FontThumb.sampleColor(d, Color.WHITE));
    }

    private static float digitHeight(File f) throws Exception {
        FontNormalizer.Digits d = FontNormalizer.measure(f);
        return FontNormalizer.height(d.top, d.bottom);
    }

    /** All fonts come out about as big as vivo's own clock font (very wide ones are limited by width). */
    @Test public void builtinFontsHaveTheSizeOfTheStockFont() throws Exception {
        StringBuilder bad = new StringBuilder();
        for (FontSpec s : BuiltinFonts.ALL) {
            File f = BuiltinFontsTest.asset(s);
            FontNormalizer.Digits d = FontNormalizer.measure(f);
            float h = digitHeight(f);
            boolean byHeight = Math.abs(h - FontNormalizer.TARGET_DIGIT_HEIGHT) < 0.03f;
            boolean byWidth = Math.abs(d.advance - FontNormalizer.MAX_DIGIT_ADVANCE) < 0.03f
                    && h < FontNormalizer.TARGET_DIGIT_HEIGHT;
            if (!byHeight && !byWidth) bad.append(s.id).append(": digits ").append(h).append(" em high, ")
                    .append(d.advance).append(" em wide; ");
        }
        assertEquals("", bad.toString());
    }

    /** A copy of a built-in font whose digits are 1.5 times smaller. */
    private static File smallFont(String id, String name) throws Exception {
        File src = BuiltinFontsTest.asset(BuiltinFonts.byId(id));
        File f = new File(RuntimeEnvironment.getApplication().getCacheDir(), name);
        Files.copy(src.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "rw")) {
            FontNormalizer.patch(r, FontNormalizer.measure(src), 1 / 1.5f);
        }
        assertEquals(0.72 / 1.5, digitHeight(f), 0.03);
        return f;
    }

    @Test @Config(shadows = FontFileSnapshot.class)
    public void userFontsGetTheSizeOfTheStockFont() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        FontStore store = new FontStore(ctx);
        FontSpec small = store.importFont(ctx.getContentResolver(), Uri.fromFile(smallFont("lf.dm_serif", "s.ttf")));
        assertEquals(0.72, digitHeight(store.customFile(small.id)), 0.03);
        FontSpec cff = store.importFont(ctx.getContentResolver(), Uri.fromFile(smallFont("lf.sf_pro", "s.otf")));
        assertEquals(0.72, digitHeight(store.customFile(cff.id)), 0.03);
        new Typeface.Builder(store.customFile(cff.id)).setFontVariationSettings("'wght' 900").build();
        assertTrue(ink(render(new Typeface.Builder(store.customFile(cff.id)).build(), "10:08")) > 300);

        // fonts added by version 1.0 were stored as they were
        File f = store.customFile(small.id);
        // Android may still map the current font. Publish the older fixture instead of deleting/truncating it.
        FontResolver.copyFile(smallFont("lf.dm_serif", "old.ttf"), f);
        android.content.SharedPreferences prefs = ctx.createDeviceProtectedStorageContext()
                .getSharedPreferences("lockfonts", Context.MODE_PRIVATE);
        prefs.edit().putString("custom_fonts", prefs.getString("custom_fonts", "").replace("\"v\":" + FontNormalizer.VERSION, "\"v\":0")).commit();
        assertTrue(store.normalizeCustoms());
        assertEquals(0.72, digitHeight(f), 0.03);
        assertTrue(!store.normalizeCustoms());                 // only once
        assertEquals(2, store.customs().size());
    }

    private static Paint.FontMetrics metrics(File f) {
        Paint p = new Paint();
        p.setTypeface(new Typeface.Builder(f).build());
        p.setTextSize(1000);
        return p.getFontMetrics();
    }

    /** Give a known font extreme global bounds, simulating unrelated tall or deep glyphs. */
    private static File fontWithExtraPadding() throws Exception {
        File src = BuiltinFontsTest.asset(BuiltinFonts.byId("lf.sf_pro"));
        File f = new File(RuntimeEnvironment.getApplication().getCacheDir(), "padded.otf");
        Files.copy(src.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "rw")) {
            r.seek(4); int count = r.readUnsignedShort();
            for (int i = 0; i < count; i++) {
                r.seek(12 + 16L * i); int tag = r.readInt(); r.readInt();
                long offset = r.readInt() & 0xffffffffL;
                if (tag == 0x68656164) {
                    r.seek(offset + 38); r.writeShort(-1109);
                    r.seek(offset + 42); r.writeShort(2616);
                    return f;
                }
            }
        }
        throw new AssertionError("missing head");
    }

    @Test @Config(shadows = FontFileSnapshot.class)
    public void alignsPerFontEvenWhenSizeNeedsNoChangeAndMigratesOldCopies() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        FontStore store = new FontStore(ctx);
        File raw = fontWithExtraPadding();
        assertTrue(metrics(raw).top < -1200);
        Bitmap before = render(new Typeface.Builder(raw).build(), "10:08");
        FontSpec spec = store.importFont(ctx.getContentResolver(), Uri.fromFile(raw));
        File fixed = store.customFile(spec.id);
        assertEquals(-930, metrics(fixed).top, 3);
        assertEquals(240, metrics(fixed).bottom, 3);
        Bitmap after = render(new Typeface.Builder(fixed).build(), "10:08");
        assertTrue("digit outlines changed", before.sameAs(after));

        // Simulate an already imported v2 font: scale is already 1, but bounds still need the fix.
        FontResolver.copyFile(raw, fixed);
        android.content.SharedPreferences prefs = ctx.createDeviceProtectedStorageContext()
                .getSharedPreferences("lockfonts", Context.MODE_PRIVATE);
        prefs.edit().putString("custom_fonts", prefs.getString("custom_fonts", "")
                .replace("\"v\":" + FontNormalizer.VERSION, "\"v\":2")).commit();
        assertTrue(store.normalizeCustoms());
        assertEquals(-930, metrics(fixed).top, 3);
        assertTrue(!store.normalizeCustoms());
    }

    @Test public void builtinPaddingContainsClockGlyphsAtWeightExtremes() throws Exception {
        for (FontSpec s : BuiltinFonts.ALL) {
            File f = BuiltinFontsTest.asset(s);
            float[] weights = s.supportsWeight() ? new float[]{s.weightMin, s.weightDefault, s.weightMax}
                    : new float[]{0};
            for (float weight : weights) {
                Typeface.Builder b = new Typeface.Builder(f);
                if (s.supportsWeight()) b.setFontVariationSettings(s.variationSettings((int) weight));
                Paint p = new Paint(); p.setTypeface(b.build()); p.setTextSize(1000);
                android.graphics.Rect r = new android.graphics.Rect();
                p.getTextBounds("0123456789:", 0, 11, r);
                Paint.FontMetrics m = p.getFontMetrics();
                assertTrue(s.id + " top clips weight " + weight, m.top <= r.top + 2);
                assertTrue(s.id + " bottom clips weight " + weight, m.bottom >= r.bottom - 2);
                assertTrue(s.id + " has unnormalized top " + m.top, m.top >= -1200);
            }
        }
    }

    private static Uri copyToTemp(File src, String name) throws Exception {
        File f = new File(RuntimeEnvironment.getApplication().getCacheDir(), name);
        Files.copy(src.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return Uri.fromFile(f);
    }

    @Test public void importsUserFontsAndServesThem() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        FontStore store = new FontStore(ctx);
        ContentResolver cr = ctx.getContentResolver();
        FontSpec geist = store.importFont(cr, copyToTemp(BuiltinFontsTest.asset(BuiltinFonts.byId("lf.geist")), "a.ttf"));
        assertTrue(geist.id.startsWith(FontSpec.CUSTOM_PREFIX));
        assertEquals("Geist", geist.name);
        assertEquals(100, geist.weightMin);
        assertEquals(900, geist.weightMax);
        assertEquals(BuiltinFonts.CUSTOM, geist.category);
        FontSpec dm = store.importFont(cr, copyToTemp(BuiltinFontsTest.asset(BuiltinFonts.byId("lf.dm_serif")), "b.ttf"));
        assertTrue(!dm.supportsWeight());

        try {
            store.importFont(cr, copyToTemp(BuiltinFontsTest.asset(BuiltinFonts.byId("lf.geist")), "c.ttf"));
            fail();
        } catch (FontStore.ImportException e) {
            assertEquals(FontStore.ImportException.DUPLICATE, e.reason);
            assertEquals(geist.id, e.existing.id);
        }
        File junk = new File(ctx.getCacheDir(), "junk.ttf");
        try (FileOutputStream o = new FileOutputStream(junk)) {
            o.write("this is not a font at all, just text".getBytes());
        }
        try {
            store.importFont(cr, Uri.fromFile(junk));
            fail();
        } catch (FontStore.ImportException e) {
            assertEquals(FontStore.ImportException.NOT_A_FONT, e.reason);
        }

        // shown list: built-ins by category, user fonts last; hiding works
        List<FontSpec> shown = store.shown();
        assertEquals(BuiltinFonts.ALL.size() + 2, shown.size());
        assertEquals(geist.id, shown.get(shown.size() - 2).id);
        store.setShown("lf.sf_pro", false);
        store.setCategoryShown(BuiltinFonts.NOTHING, false);
        assertEquals(BuiltinFonts.ALL.size() + 2 - 1 - 3, store.shown().size());

        // the provider hands out the list and the files of user fonts
        FontsProvider p = Robolectric.setupContentProvider(FontsProvider.class, FontsProvider.AUTHORITY);
        Bundle b = p.call(FontsProvider.METHOD_HELLO, null, null);
        assertEquals(store.shown().size(), FontList.fromBundle(b).size());
        assertTrue(store.lastSeen(ctx.getPackageName()) > 0);
        try (ParcelFileDescriptor fd = p.openFile(FontsProvider.customUri(geist.id), "r")) {
            assertNotNull(fd);
            assertEquals(BuiltinFontsTest.asset(BuiltinFonts.byId("lf.geist")).length(), fd.getStatSize());
        }
        try {
            p.openFile(FontsProvider.customUri("lf.c.ffffffffffffffff"), "r");
            fail();
        } catch (java.io.FileNotFoundException expected) {
            // unknown font
        }
        try {
            p.openFile(FontsProvider.customUri(geist.id), "w");
            fail();
        } catch (java.io.FileNotFoundException expected) {
            // read only
        }

        store.rename(dm.id, "My serif");
        assertEquals("My serif", store.find(dm.id).name);
        store.removeCustom(geist.id);
        assertNull(store.find(geist.id));
        assertTrue(!store.customFile(geist.id).exists());
    }

    @Test public void concurrentImportsPublishEachFontOnlyOnce() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        File geist = BuiltinFontsTest.asset(BuiltinFonts.byId("lf.geist"));
        File serif = BuiltinFontsTest.asset(BuiltinFonts.byId("lf.dm_serif"));
        File[] inputs = {geist, serif, geist, serif};
        CountDownLatch ready = new CountDownLatch(inputs.length), start = new CountDownLatch(1);
        AtomicInteger duplicates = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(inputs.length);
        List<Future<FontSpec>> tasks = new ArrayList<>();
        try {
            for (File input : inputs) {
                tasks.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("imports did not start");
                    try {
                        return new FontStore(ctx).importFont(ctx.getContentResolver(), Uri.fromFile(input));
                    } catch (FontStore.ImportException e) {
                        if (e.reason != FontStore.ImportException.DUPLICATE) throw e;
                        duplicates.incrementAndGet();
                        return e.existing;
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            FontStore store = new FontStore(ctx);
            List<FontSpec> results = new ArrayList<>();
            for (int i = 0; i < tasks.size(); i++) {
                FontSpec spec = tasks.get(i).get(30, TimeUnit.SECONDS);
                assertNotNull(spec);
                results.add(spec);
                File stored = store.customFile(spec.id);
                assertEquals(FontInfo.read(inputs[i]).displayName(), FontInfo.read(stored).displayName());
                FontStore.checkRenders(stored);
            }
            assertEquals(results.get(0).id, results.get(2).id);
            assertEquals(results.get(1).id, results.get(3).id);
            assertTrue(!results.get(0).id.equals(results.get(1).id));
            assertEquals(2, duplicates.get());
            assertEquals(2, store.customs().size());
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    @Test public void systemFallbackCannotSupplyMissingFontDigits() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        File font = new File(ctx.getCacheDir(), "missing-zero.ttf");
        Files.copy(BuiltinFontsTest.asset(BuiltinFonts.byId("lf.dm_serif")).toPath(), font.toPath());
        boolean removed = false;
        try (java.io.RandomAccessFile data = new java.io.RandomAccessFile(font, "rw")) {
            data.seek(4);
            int tables = data.readUnsignedShort();
            for (int i = 0; i < tables; i++) {
                data.seek(12 + 16L * i);
                if (data.readInt() != 0x636d6170) continue;
                data.skipBytes(4);
                long cmap = data.readInt() & 0xffffffffL;
                data.seek(cmap + 2);
                int maps = data.readUnsignedShort();
                for (int j = 0; j < maps; j++) {
                    data.seek(cmap + 4 + 8L * j + 4);
                    long subtable = cmap + (data.readInt() & 0xffffffffL);
                    data.seek(subtable);
                    assertEquals("fixture uses format-4 character maps", 4, data.readUnsignedShort());
                    data.seek(subtable + 6);
                    int segments = data.readUnsignedShort() / 2;
                    for (int s = 0; s < segments; s++) {
                        data.seek(subtable + 14 + 2L * s);
                        int end = data.readUnsignedShort();
                        data.seek(subtable + 16 + 2L * segments + 2L * s);
                        int start = data.readUnsignedShort();
                        if (start > '0' || end < '0') continue;
                        data.seek(subtable + 16 + 6L * segments + 2L * s);
                        assertEquals("fixture uses a direct glyph delta", 0, data.readUnsignedShort());
                        data.seek(subtable + 16 + 4L * segments + 2L * s);
                        data.writeShort(-'0'); // a valid cmap whose zero resolves to .notdef
                        removed = true;
                    }
                }
                break;
            }
        }
        assertTrue(removed);
        new Font.Builder(font).build();
        Paint fallback = new Paint();
        fallback.setTypeface(new Typeface.Builder(font).build());
        assertTrue("system fallback still supplies zero", fallback.hasGlyph("0"));
        try {
            new FontStore(ctx).importFont(ctx.getContentResolver(), Uri.fromFile(font));
            fail("a font without its own digits was accepted");
        } catch (FontStore.ImportException e) {
            assertEquals(FontStore.ImportException.NO_DIGITS, e.reason);
        }
    }

    /**
     * Windows cannot replace a memory-mapped file, unlike Android. Migration tests give the real native font
     * stack a snapshot of the same bytes, so old Typefaces survive replacement as they do on the device.
     * Font validation, normalization, file publication and rendering still run through the production code.
     */
    @Implements(value = Font.Builder.class, minSdk = 31, callNativeMethodsByDefault = true)
    public static class FontFileSnapshot extends ShadowNativeFontBuilder {
        @RealObject private Font.Builder builder;

        @Implementation protected void __constructor__(File file) throws IOException {
            __constructor__(file, "");
        }

        @Implementation protected void __constructor__(File file, String localeList) throws IOException {
            byte[] contents = Files.readAllBytes(file.toPath());
            ByteBuffer buffer = ByteBuffer.allocateDirect(contents.length);
            buffer.put(contents).flip();
            Shadow.invokeConstructor(Font.Builder.class, builder, ClassParameter.from(ByteBuffer.class, buffer));
            Shadow.invokeConstructor(Font.Builder.class, builder, ClassParameter.from(ByteBuffer.class, buffer),
                    ClassParameter.from(File.class, file), ClassParameter.from(String.class, localeList));
        }
    }
}

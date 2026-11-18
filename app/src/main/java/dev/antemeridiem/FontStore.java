package dev.antemeridiem;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.text.PositionedGlyphs;
import android.graphics.text.TextRunShaper;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Settings and user fonts of the app. Everything lives in device protected storage: System UI asks for the fonts
 * (through {@link FontsProvider}) right after a reboot, before the phone is unlocked for the first time.
 */
final class FontStore {
    static final long MAX_FONT_BYTES = 40L * 1024 * 1024;
    private static final String PREFS = "lockfonts";
    private static final String KEY_HIDDEN = "hidden", KEY_CUSTOM = "custom_fonts", KEY_SEEN = "seen_",
            KEY_VERSION = "v", KEY_TRIED = "tried", KEY_ANALOG = "clock_analog", KEY_HANDS = "clock_hands";
    private static final Object LOCK = new Object();
    private static final String[] DIGITS = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9"};

    private final Context de;
    private final SharedPreferences prefs;

    /** Why a font could not be added; shown by the app. */
    static final class ImportException extends Exception {
        static final int TOO_BIG = 1, NOT_A_FONT = 2, WOFF = 3, NO_DIGITS = 4, DUPLICATE = 5, IO = 6;
        final int reason;
        final FontSpec existing;

        ImportException(int reason, FontSpec existing, Throwable cause) {
            super("import failed: " + reason, cause);
            this.reason = reason;
            this.existing = existing;
        }
    }

    FontStore(Context context) {
        Context c = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        de = c.isDeviceProtectedStorage() ? c : c.createDeviceProtectedStorageContext();
        prefs = de.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ catalog

    /** Built-in fonts followed by the user's fonts. */
    List<FontSpec> all() {
        List<FontSpec> l = new ArrayList<>(BuiltinFonts.ALL);
        l.addAll(customs());
        return l;
    }

    /** Fonts the editor should offer, in display order. */
    List<FontSpec> shown() {
        Set<String> hidden = hidden();
        List<FontSpec> all = all();
        List<FontSpec> out = new ArrayList<>();
        for (String cat : BuiltinFonts.CATEGORIES) {
            for (FontSpec s : all) {
                if (s.category.equals(cat) && !hidden.contains(s.id)) out.add(s);
            }
        }
        return out;
    }

    boolean isShown(String id) {
        return !hidden().contains(id);
    }

    void setShown(String id, boolean shown) {
        synchronized (LOCK) {
            Set<String> h = new HashSet<>(hidden());
            boolean changed = shown ? h.remove(id) : h.add(id);
            if (!changed) return;
            prefs.edit().putStringSet(KEY_HIDDEN, h).apply();
        }
        notifyChanged();
    }

    void setCategoryShown(String category, boolean shown) {
        synchronized (LOCK) {
            Set<String> h = new HashSet<>(hidden());
            for (FontSpec s : all()) {
                if (!s.category.equals(category)) continue;
                if (shown) h.remove(s.id);
                else h.add(s.id);
            }
            prefs.edit().putStringSet(KEY_HIDDEN, h).apply();
        }
        notifyChanged();
    }

    private Set<String> hidden() {
        Set<String> s = prefs.getStringSet(KEY_HIDDEN, null);
        return s != null ? s : new HashSet<>();
    }

    FontSpec find(String id) {
        for (FontSpec s : all()) {
            if (s.id.equals(id)) return s;
        }
        return null;
    }

    // ------------------------------------------------------------------ user fonts

    List<FontSpec> customs() {
        List<FontSpec> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString(KEY_CUSTOM, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String id = o.getString("id");
                if (!FontSpec.isValidId(id) || !id.startsWith(FontSpec.CUSTOM_PREFIX)) continue;
                out.add(new FontSpec(id, o.optString("name", id), BuiltinFonts.CUSTOM, null,
                        o.optInt("min"), o.optInt("max"), o.optInt("def")));
            }
        } catch (JSONException ignored) {
            // corrupt list: behave as if empty, the files stay and can be re-added
        }
        return out;
    }

    /** Cheap UI change token; normalizer versions are included so stale custom previews can be discarded. */
    String customRevision() {
        return prefs.getString(KEY_CUSTOM, "[]");
    }

    File customFile(String id) {
        if (!FontSpec.isValidId(id) || !id.startsWith(FontSpec.CUSTOM_PREFIX)) return null;
        return new File(customDir(), id.substring(FontSpec.CUSTOM_PREFIX.length()) + ".font");
    }

    private File customDir() {
        File d = new File(de.getFilesDir(), "custom");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** Copies a font picked by the user, checks it and adds it to the list. Runs on a worker thread. */
    FontSpec importFont(ContentResolver cr, Uri uri) throws ImportException {
        File tmp = null, prepared = null;
        try {
            tmp = File.createTempFile("import-", ".font", customDir());
            String hash;
            try (InputStream in = cr.openInputStream(uri)) {
                if (in == null) throw new ImportException(ImportException.IO, null, null);
                hash = copyHashed(in, tmp);
            }
            String id = FontSpec.CUSTOM_PREFIX + hash.substring(0, 16);
            FontSpec existing = find(id);
            if (existing != null) throw new ImportException(ImportException.DUPLICATE, existing, null);
            FontInfo info;
            try {
                info = FontInfo.read(tmp);
                checkRenders(tmp);
            } catch (FontInfo.BadFontException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                throw new ImportException(ImportException.NOT_A_FONT, null, e);
            }
            String name = info.displayName();
            if (name == null) name = documentName(cr, uri);
            if (name == null) name = "Font " + hash.substring(0, 6).toUpperCase(Locale.ROOT);
            FontSpec spec = new FontSpec(id, name, BuiltinFonts.CUSTOM, null,
                    info.hasWeightAxis ? clampWeight(info.weightMin) : 0,
                    info.hasWeightAxis ? clampWeight(info.weightMax) : 0,
                    info.hasWeightAxis ? clampWeight(info.weightDefault) : 0);
            prepared = File.createTempFile("normalized-", ".font", customDir());
            try {
                FontNormalizer.normalize(tmp, prepared);
            } catch (IOException | RuntimeException e) {
                deleteQuietly(prepared);
                prepared = tmp;   // the original was already validated by Android
                tmp = null;
            }
            synchronized (LOCK) {
                // Another Activity/import may have published the same content while this one was normalizing it.
                existing = find(id);
                if (existing != null) throw new ImportException(ImportException.DUPLICATE, existing, null);
                JSONArray a = new JSONArray(prefs.getString(KEY_CUSTOM, "[]"));
                a.put(new JSONObject().put("id", id).put("name", name).put("min", spec.weightMin)
                        .put("max", spec.weightMax).put("def", spec.weightDefault)
                        .put(KEY_VERSION, FontNormalizer.VERSION));
                File dest = customFile(id);
                if (dest == null) throw new IOException("invalid font id");
                Files.move(prepared.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                prefs.edit().putString(KEY_CUSTOM, a.toString()).apply();
            }
            notifyChanged();
            return spec;
        } catch (FontInfo.BadFontException e) {
            throw new ImportException(e.reason == FontInfo.BadFontException.WOFF ? ImportException.WOFF
                    : ImportException.NOT_A_FONT, null, e);
        } catch (IOException e) {
            boolean big = e.getMessage() != null && e.getMessage().startsWith("too large");
            throw new ImportException(big ? ImportException.TOO_BIG : ImportException.IO, null, e);
        } catch (JSONException | RuntimeException e) {
            throw new ImportException(ImportException.IO, null, e);
        } finally {
            deleteQuietly(tmp);
            deleteQuietly(prepared);
        }
    }

    /**
     * Update old user-font copies once when the normalization rule changes. Called by the app
     * and, before handing out a font, by the provider (System UI may ask for it before the app is opened).
     * Returns true when a file changed.
     */
    boolean normalizeCustoms() {
        boolean changed = false;
        for (FontSpec spec : customs()) changed |= normalizeCustomFile(spec.id);
        if (changed) notifyChanged();
        return changed;
    }

    /** The provider only needs this font, not a migration of the entire library on the caller's critical path. */
    void normalizeCustom(String id) {
        if (normalizeCustomFile(id)) notifyChanged();
    }

    private boolean normalizeCustomFile(String id) {
        File file = customFile(id), tmp = null;
        if (file == null || !file.isFile()) return false;
        try {
            JSONObject before;
            synchronized (LOCK) {
                before = customEntry(new JSONArray(prefs.getString(KEY_CUSTOM, "[]")), id);
                if (before == null || before.optInt(KEY_VERSION) >= FontNormalizer.VERSION
                        || before.optInt(KEY_TRIED) >= FontNormalizer.VERSION) return false;
            }
            tmp = File.createTempFile("migration-", ".font", customDir());
            boolean ready;
            try {
                // Expensive font loading and copying must not hold the settings/rename/delete lock.
                FontNormalizer.normalize(file, tmp);
                ready = true;
            } catch (IOException | RuntimeException ignored) {
                ready = false;
            }
            synchronized (LOCK) {
                JSONArray list = new JSONArray(prefs.getString(KEY_CUSTOM, "[]"));
                JSONObject current = customEntry(list, id);
                if (current == null || !file.isFile() || current.optInt(KEY_VERSION) != before.optInt(KEY_VERSION)
                        || current.optInt(KEY_TRIED) >= FontNormalizer.VERSION) return false;
                if (ready) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    current.put(KEY_VERSION, FontNormalizer.VERSION);
                }
                // Valid fonts with unsupported normalizer tables should not be retried on every provider open.
                current.put(KEY_TRIED, FontNormalizer.VERSION);
                prefs.edit().putString(KEY_CUSTOM, list.toString()).apply();
            }
            return ready;
        } catch (IOException | JSONException ignored) {
            return false;
        } finally {
            deleteQuietly(tmp);
        }
    }

    private static JSONObject customEntry(JSONArray list, String id) throws JSONException {
        for (int i = 0; i < list.length(); i++) {
            JSONObject entry = list.getJSONObject(i);
            if (id.equals(entry.optString("id"))) return entry;
        }
        return null;
    }

    void rename(String id, String name) {
        if (name == null || name.trim().isEmpty()) return;
        updateCustoms(id, name.trim(), false);
    }

    void removeCustom(String id) {
        synchronized (LOCK) {
            updateCustomsLocked(id, null, true);
            deleteQuietly(customFile(id));
            Set<String> h = new HashSet<>(hidden());
            if (h.remove(id)) prefs.edit().putStringSet(KEY_HIDDEN, h).apply();
        }
        notifyChanged();
    }

    private void updateCustoms(String id, String newName, boolean remove) {
        synchronized (LOCK) {
            updateCustomsLocked(id, newName, remove);
        }
        notifyChanged();
    }

    private void updateCustomsLocked(String id, String newName, boolean remove) {
        try {
            JSONArray a = new JSONArray(prefs.getString(KEY_CUSTOM, "[]"));
            JSONArray out = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (id.equals(o.optString("id"))) {
                    if (remove) continue;
                    o.put("name", newName);
                }
                out.put(o);
            }
            prefs.edit().putString(KEY_CUSTOM, out.toString()).apply();
        } catch (JSONException ignored) {
            // nothing sensible to do with a corrupt list
        }
    }

    /** The weight slider of the editor works with whole numbers in 1..1000. */
    static int clampWeight(float w) {
        return Math.max(1, Math.min(1000, Math.round(w)));
    }

    /** Android must be able to load the font and the font itself must have all ten digits. */
    static void checkRenders(File file) throws IOException, ImportException {
        Font font = new Font.Builder(file).build();
        Typeface tf = new Typeface.CustomFallbackBuilder(new FontFamily.Builder(font).build()).build();
        Paint p = new Paint();
        p.setTypeface(tf);
        for (String d : DIGITS) {
            if (!p.hasGlyph(d)) throw new ImportException(ImportException.NO_DIGITS, null, null);
            PositionedGlyphs glyphs = TextRunShaper.shapeTextRun(d, 0, d.length(), 0, d.length(), 0f, 0f, false, p);
            if (glyphs.glyphCount() == 0) throw new ImportException(ImportException.NO_DIGITS, null, null);
            for (int i = 0; i < glyphs.glyphCount(); i++) {
                if (glyphs.getGlyphId(i) == 0 || !font.equals(glyphs.getFont(i))) {
                    throw new ImportException(ImportException.NO_DIGITS, null, null);
                }
            }
        }
    }

    private static String copyHashed(InputStream in, File out) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        long total = 0;
        byte[] buf = new byte[64 * 1024];
        try (OutputStream os = new FileOutputStream(out)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_FONT_BYTES) throw new IOException("too large");
                md.update(buf, 0, n);
                os.write(buf, 0, n);
            }
        }
        if (total < 12) throw new FontInfo.BadFontException(FontInfo.BadFontException.NOT_A_FONT, "empty file");
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
        return sb.toString();
    }

    private static String documentName(ContentResolver cr, Uri uri) {
        String name = null;
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (RuntimeException ignored) {
            // fall back to the last path segment
        }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) return null;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = name.trim();
        return name.isEmpty() ? null : name;
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists() && !f.delete()) f.deleteOnExit();
    }

    // ------------------------------------------------------------------ status

    /** Called by a hooked process through the provider. */
    void markSeen(String pkg) {
        if (pkg == null || pkg.length() > 100) return;
        prefs.edit().putLong(KEY_SEEN + pkg, System.currentTimeMillis()).apply();
    }

    long lastSeen(String pkg) {
        return prefs.getLong(KEY_SEEN + pkg, 0);
    }

    /** Analog lock screen clock: only 12, 3, 6 and 9 around a dial instead of the digital time. */
    boolean analogLayout() {
        return prefs.getBoolean(KEY_ANALOG, false);
    }

    void setAnalogLayout(boolean on) {
        prefs.edit().putBoolean(KEY_ANALOG, on).apply();
        notifyChanged();
    }

    /** Hour and minute hands on the analog dial. */
    boolean analogHands() {
        return prefs.getBoolean(KEY_HANDS, true);
    }

    void setAnalogHands(boolean on) {
        prefs.edit().putBoolean(KEY_HANDS, on).apply();
        notifyChanged();
    }

    private static final String KEY_SCALE = "clock_scale", KEY_DIGITAL_SCALE = "digital_scale",
            KEY_DATE_HIDDEN = "date_hidden", KEY_DATE_FONT = "date_font";

    /** Analog dial size only. Keep the old preference key so existing analog settings survive an update. */
    int analogScale() {
        return AnalogClock.clampScale(prefs.getInt(KEY_SCALE, AnalogClock.DEFAULT_SCALE));
    }

    void setAnalogScale(int percent) {
        int v = AnalogClock.clampScale(percent);
        if (v == analogScale()) return;
        prefs.edit().putInt(KEY_SCALE, v).apply();
        notifyChanged();
    }

    /** Digital clock font scale percentage. */
    int digitalScale() {
        return ClockLayout.clampScale(prefs.getInt(KEY_DIGITAL_SCALE, ClockLayout.DEFAULT_DIGITAL_SCALE));
    }

    void setDigitalScale(int percent) {
        int v = ClockLayout.clampScale(percent);
        if (v == digitalScale()) return;
        prefs.edit().putInt(KEY_DIGITAL_SCALE, v).apply();
        notifyChanged();
    }

    /** The lock screen date is hidden. */
    boolean dateHidden() {
        return prefs.getBoolean(KEY_DATE_HIDDEN, false);
    }

    void setDateHidden(boolean on) {
        prefs.edit().putBoolean(KEY_DATE_HIDDEN, on).apply();
        notifyChanged();
    }

    /** The date is drawn with the clock's font. */
    boolean dateFont() {
        return prefs.getBoolean(KEY_DATE_FONT, false);
    }

    void setDateFont(boolean on) {
        prefs.edit().putBoolean(KEY_DATE_FONT, on).apply();
        notifyChanged();
    }

    private void notifyChanged() {
        try {
            de.getContentResolver().notifyChange(FontsProvider.CONTENT_URI, null);
        } catch (RuntimeException ignored) {
            // the editor re-reads the list anyway when its font panel opens
        }
    }
}

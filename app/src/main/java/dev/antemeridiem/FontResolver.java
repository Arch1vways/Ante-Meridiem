package dev.antemeridiem;

import android.content.Context;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Runs inside the hooked processes: turns a pseudo path ("/lockfonts/lf.sf_pro") into a real font file in the
 * process' own device protected cache. Built-in fonts are unpacked from the module APK, user fonts are fetched
 * once from {@link FontsProvider}. Anything that fails ends up as vivo's default clock font.
 */
final class FontResolver {
    private static final long RETRY_MS = 10_000;
    private static final Map<String, String> RESOLVED = new HashMap<>();
    private static final Map<String, Long> FAILED = new HashMap<>();

    private FontResolver() { }

    /** Real path for a pseudo path; other paths are returned unchanged. */
    static String resolve(String path) {
        String id = FontSpec.idFromPath(path);
        if (id == null) return path;
        String real = realPath(id);
        return real != null ? real : FontSpec.FALLBACK_PATH;
    }

    /** Real file of one of our fonts, or null when it is not available (unknown id, deleted user font, ...). */
    static synchronized String realPath(String id) {
        String done = RESOLVED.get(id);
        if (done != null && new File(done).isFile()) return done;
        Long failedAt = FAILED.get(id);
        long now = SystemClock.uptimeMillis();
        if (failedAt != null && now - failedAt < RETRY_MS) return null;
        File f = null;
        try {
            f = materialize(HookEntry.appContext(), id);
        } catch (IOException | RuntimeException e) {
            HookEntry.log("cannot load font " + id, e);
        }
        if (f == null) {
            FAILED.put(id, now);
            return null;
        }
        FAILED.remove(id);
        RESOLVED.put(id, f.getPath());
        return f.getPath();
    }

    private static File cacheDir(Context ctx) {
        File base;
        if (ctx != null) {
            Context de = ctx.isDeviceProtectedStorage() ? ctx : ctx.createDeviceProtectedStorageContext();
            base = de.getCacheDir();
        } else {
            base = new File(System.getProperty("java.io.tmpdir", "/data/local/tmp"));
        }
        File dir = new File(base, "lockfonts");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        return dir;
    }

    private static File materialize(Context ctx, String id) throws IOException {
        File dir = cacheDir(ctx);
        if (dir == null) return null;
        FontSpec builtin = BuiltinFonts.byId(legacyBuiltinId(id));
        if (builtin != null) return unpackBuiltin(dir, builtin);
        if (id.startsWith(FontSpec.CUSTOM_PREFIX) && ctx != null) return fetchCustom(ctx, dir, id);
        return null;
    }

    /** Old duplicate IDs remain readable after an upgrade without reappearing in the catalog. */
    static String legacyBuiltinId(String id) {
        if (id == null) return null;
        switch (id) {
            case "lf.samsung_clock_2021_fixed": return "lf.samsung_clock_2021";
            case "lf.samsung_modex_mono": return "lf.samsung_modex";
            case "lf.samsung_stamp_mono": return "lf.samsung_stamp";
            case "lf.samsung_trimex_mono": return "lf.samsung_trimex";
            case "lf.samsung_secnum_fixed": return "lf.samsung_secnum_semibold";
            case "lf.samsung_clock_mono": return "lf.samsung_clock_default";
            case "lf.samsung_clock_h": return "lf.samsung_clock_compact";
            default: return id;
        }
    }

    private static File unpackBuiltin(File dir, FontSpec spec) throws IOException {
        String apk = HookEntry.modulePath;
        if (apk == null) return null;
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry e = zip.getEntry("assets/" + spec.asset);
            if (e == null) return null;
            String ext = spec.asset.substring(spec.asset.lastIndexOf('.'));
            String prefix = spec.key() + "-";
            File out = new File(dir, prefix + Long.toHexString(e.getCrc()) + ext);
            if (out.isFile() && out.length() == e.getSize()) return out;
            try (InputStream in = zip.getInputStream(e)) {
                copy(in, out);
            }
            // fonts of older module versions
            File[] old = dir.listFiles((d, name) -> name.startsWith(prefix) && !name.equals(out.getName()));
            if (old != null) {
                for (File o : old) {
                    if (!o.delete()) o.deleteOnExit();
                }
            }
            return out;
        }
    }

    private static File fetchCustom(Context ctx, File dir, String id) throws IOException {
        // user fonts are content addressed (the id is a hash of the file), so a cached copy never goes stale,
        // and the app rewrites a font only once, when FontNormalizer.VERSION changes (the cache name changes with it)
        String key = id.substring(FontSpec.ID_PREFIX.length());
        File out = new File(dir, key + "-n" + FontNormalizer.VERSION + ".font");
        if (out.isFile() && out.length() > 0) return out;
        File[] old = dir.listFiles((d, name) -> name.startsWith(key + ".") || name.startsWith(key + "-n"));
        if (old != null) {
            for (File o : old) {
                if (!o.delete()) o.deleteOnExit();
            }
        }
        try (InputStream in = ctx.getContentResolver().openInputStream(FontsProvider.customUri(id))) {
            if (in == null) return null;
            copy(in, out);
        } catch (SecurityException e) {
            throw new IOException(e);
        }
        return out;
    }

    static void copyFile(File in, File out) throws IOException {
        try (InputStream is = new java.io.FileInputStream(in)) {
            copy(is, out);
        }
    }

    private static void copy(InputStream in, File out) throws IOException {
        File tmp = File.createTempFile(out.getName() + "-", ".tmp", out.getAbsoluteFile().getParentFile());
        try {
            long total = 0;
            try (OutputStream os = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > FontStore.MAX_FONT_BYTES) throw new IOException("font too large");
                    os.write(buf, 0, n);
                }
            }
            // Normalization may supply an already-created destination. Publish the complete copy in one move.
            Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (tmp.exists() && !tmp.delete()) tmp.deleteOnExit();
        }
    }

    /** Tests only. */
    static synchronized void reset() {
        RESOLVED.clear();
        FAILED.clear();
    }
}

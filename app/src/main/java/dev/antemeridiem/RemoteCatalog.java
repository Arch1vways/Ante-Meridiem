package dev.antemeridiem;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import java.util.List;

/**
 * Runs inside the hooked processes: the fonts to add to the editor, asked from the app's {@link FontsProvider}.
 * Kept for a moment, because the editor builds its font list several times in a row. When the app cannot be reached
 * all built-in fonts are offered.
 */
final class RemoteCatalog {
    private static final long TTL_MS = 2_000;
    private static List<FontSpec> cached;
    private static long cachedAt;
    private static boolean greeted;

    private RemoteCatalog() { }

    static synchronized List<FontSpec> get(Context ctx) {
        long now = SystemClock.uptimeMillis();
        if (cached != null && now - cachedAt < TTL_MS) return cached;
        List<FontSpec> fresh = fetch(ctx, greeted ? FontsProvider.METHOD_LIST : FontsProvider.METHOD_HELLO);
        if (fresh != null) {
            cached = fresh;
            greeted = true;
        } else if (cached == null) {
            cached = BuiltinFonts.ALL;
        }
        cachedAt = now;
        return cached;
    }

    /** Tells the app that the module works in this process (shown on its status card). Off the main thread. */
    static void helloAsync() {
        Thread t = new Thread(() -> {
            Context ctx = HookEntry.appContext();
            for (int i = 0; ctx == null && i < 20; i++) {
                SystemClock.sleep(500);
                ctx = HookEntry.appContext();
            }
            if (ctx == null) return;
            List<FontSpec> l = fetch(ctx, FontsProvider.METHOD_HELLO);
            synchronized (RemoteCatalog.class) {
                if (l != null) {
                    greeted = true;
                    cached = l;
                    cachedAt = SystemClock.uptimeMillis();
                }
            }
        }, "LockFonts-hello");
        t.setDaemon(true);
        t.start();
    }

    private static List<FontSpec> fetch(Context ctx, String method) {
        if (ctx == null) return null;
        try {
            Bundle b = ctx.getContentResolver().call(FontsProvider.CONTENT_URI, method, null, null);
            return FontList.fromBundle(b);
        } catch (RuntimeException e) {
            return null;    // app not installed / not visible / provider crashed
        }
    }
}

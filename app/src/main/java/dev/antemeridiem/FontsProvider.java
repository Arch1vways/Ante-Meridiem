package dev.antemeridiem;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.List;

/**
 * Read-only bridge for the hooked processes: the list of fonts to offer and the files of the fonts added by the user.
 * Built-in fonts are read by the hooks straight from the module APK. The provider has to be exported (the callers
 * are other apps), but it answers only the module's own scope - Themes, System UI and the vivo System UI plugin -
 * and this app itself; everybody else gets nothing. Direct boot aware, so the lock screen gets the fonts before the
 * first unlock.
 */
public final class FontsProvider extends ContentProvider {
    static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".fonts";
    static final android.net.Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);
    static final String METHOD_LIST = "list", METHOD_HELLO = "hello", METHOD_CLOCK = "clock";
    static final String PATH_CUSTOM = "custom";
    /** The packages the module hooks (see assets/xposed_scope). Also listed under <queries> in the manifest. */
    static final List<String> CLIENTS = Arrays.asList(HookEntry.THEMES, HookEntry.SYSTEMUI, HookEntry.PLUGIN);

    private FontStore store;

    @Override public boolean onCreate() {
        return true;
    }

    private synchronized FontStore store() {
        if (store == null) store = new FontStore(getContext());
        return store;
    }

    static Uri customUri(String id) {
        return CONTENT_URI.buildUpon().appendPath(PATH_CUSTOM).appendPath(id).build();
    }

    /** The caller's package if it is allowed to read fonts, otherwise null. */
    private String allowedCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return BuildConfig.APPLICATION_ID;
        PackageManager pm = getContext() != null ? getContext().getPackageManager() : null;
        String[] pkgs = pm != null ? pm.getPackagesForUid(uid) : null;
        if (pkgs == null) return null;
        for (String p : pkgs) {
            if (CLIENTS.contains(p)) return p;
        }
        return null;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        String caller = allowedCaller();
        if (caller == null) return null;
        if (METHOD_HELLO.equals(method)) {
            store().markSeen(caller);
            return FontList.toBundle(store().shown());
        }
        if (METHOD_LIST.equals(method)) return FontList.toBundle(store().shown());
        if (METHOD_CLOCK.equals(method)) {
            Bundle b = new Bundle();
            b.putBoolean(AnalogClock.KEY_ANALOG, store().analogLayout());
            b.putBoolean(AnalogClock.KEY_HANDS, store().analogHands());
            b.putInt(AnalogClock.KEY_SCALE, store().analogScale());
            b.putInt(ClockLayout.KEY_DIGITAL_SCALE, store().digitalScale());
            b.putBoolean(ClockLayout.KEY_DATE_HIDDEN, store().dateHidden());
            b.putBoolean(ClockLayout.KEY_DATE_FONT, store().dateFont());
            return b;
        }
        return null;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (allowedCaller() == null) throw new SecurityException("not allowed");
        if (mode == null || !mode.equals("r")) throw new FileNotFoundException("read only");
        List<String> seg = uri.getPathSegments();
        if (seg.size() != 2 || !PATH_CUSTOM.equals(seg.get(0))) throw new FileNotFoundException(uri.toString());
        String id = seg.get(1);
        store().normalizeCustom(id);
        boolean known = false;
        for (FontSpec s : store().customs()) {
            if (s.id.equals(id)) known = true;
        }
        File f = known ? store().customFile(id) : null;
        if (f == null || !f.isFile()) throw new FileNotFoundException(id);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        return null;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}

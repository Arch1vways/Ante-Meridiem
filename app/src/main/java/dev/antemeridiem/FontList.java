package dev.antemeridiem;

import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;

/** The font list as it travels from the app to the hooked processes (a Bundle returned by {@link FontsProvider}). */
final class FontList {
    static final String IDS = "ids", NAMES = "names", CATEGORIES = "categories", MIN = "wmin", MAX = "wmax", DEF = "wdef";

    private FontList() { }

    static Bundle toBundle(List<FontSpec> fonts) {
        int n = fonts.size();
        String[] ids = new String[n], names = new String[n], cats = new String[n];
        int[] min = new int[n], max = new int[n], def = new int[n];
        for (int i = 0; i < n; i++) {
            FontSpec s = fonts.get(i);
            ids[i] = s.id;
            names[i] = s.name;
            cats[i] = s.category;
            min[i] = s.weightMin;
            max[i] = s.weightMax;
            def[i] = s.weightDefault;
        }
        Bundle b = new Bundle();
        b.putStringArray(IDS, ids);
        b.putStringArray(NAMES, names);
        b.putStringArray(CATEGORIES, cats);
        b.putIntArray(MIN, min);
        b.putIntArray(MAX, max);
        b.putIntArray(DEF, def);
        return b;
    }

    /** Null when the bundle is not a font list. Unknown or malformed entries are skipped. */
    static List<FontSpec> fromBundle(Bundle b) {
        if (b == null) return null;
        String[] ids = b.getStringArray(IDS), names = b.getStringArray(NAMES), cats = b.getStringArray(CATEGORIES);
        int[] min = b.getIntArray(MIN), max = b.getIntArray(MAX), def = b.getIntArray(DEF);
        if (ids == null || names == null || cats == null || min == null || max == null || def == null) return null;
        int n = ids.length;
        if (names.length != n || cats.length != n || min.length != n || max.length != n || def.length != n) return null;
        List<FontSpec> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (!FontSpec.isValidId(ids[i])) continue;
            FontSpec builtin = BuiltinFonts.byId(ids[i]);
            if (builtin != null) {
                out.add(builtin);
            } else if (ids[i].startsWith(FontSpec.CUSTOM_PREFIX)) {
                out.add(new FontSpec(ids[i], names[i] != null ? names[i] : ids[i], BuiltinFonts.CUSTOM, null,
                        min[i], max[i], def[i]));
            }
        }
        return out;
    }
}

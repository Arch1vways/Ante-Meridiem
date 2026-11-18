package dev.antemeridiem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fonts shipped in the APK (assets/fonts; see the preparation scripts in tools/).
 * Weight ranges follow each font's 'wght' axis.
 */
final class BuiltinFonts {
    static final String APPLE = "apple", SAMSUNG = "samsung", GOOGLE = "google", XIAOMI = "xiaomi", OPPO = "oppo", HUAWEI = "huawei",
            NOTHING = "nothing", MOTOROLA = "motorola", OTHER = "other", CUSTOM = "custom";
    /** Display order of the categories (phone brands), both in the app and in the editor's font list. */
    static final String[] CATEGORIES = {APPLE, SAMSUNG, GOOGLE, XIAOMI, OPPO, HUAWEI, NOTHING, MOTOROLA, OTHER, CUSTOM};

    static final List<FontSpec> ALL;

    static {
        List<FontSpec> l = new ArrayList<>();
        // Apple: iOS 26 lock screen clock fonts (from the user's file)
        add(l, "sf_pro", "SF Pro", APPLE, "otf", 1, 1000, 400);
        add(l, "sf_rounded", "SF Pro Rounded", APPLE, "otf", 1, 1000, 400);
        add(l, "sf_soft", "SF Soft", APPLE, "otf", 1, 1000, 400);
        add(l, "sf_compact_soft", "SF Compact Soft", APPLE, "otf", 100, 900, 500);
        add(l, "sf_stencil", "SF Stencil", APPLE, "otf", 1, 1000, 400);
        add(l, "sf_rail", "SF Rail", APPLE, "otf", 1, 400, 400);
        add(l, "sf_rail_rounded", "SF Rail Rounded", APPLE, "otf", 0, 0, 0);   // its wght axis does not change the digits
        add(l, "sf_adaptive", "SF Adaptive", APPLE, "otf", 1, 1000, 400);
        add(l, "sf_adaptive_soft", "SF Adaptive Soft", APPLE, "otf", 1, 1000, 400);
        add(l, "new_york", "New York", APPLE, "otf", 300, 1000, 400);
        add(l, "new_york_soft", "New York Soft", APPLE, "otf", 300, 1000, 400);
        add(l, "adt_slab", "ADT Slab", APPLE, "otf", 300, 1000, 400);
        add(l, "adt_slab_soft", "ADT Slab Soft", APPLE, "otf", 300, 1000, 400);
        // Samsung AOD fonts, without Stretch and closely repeating fixed/mono variants.
        // The two extra axes in some files are preserved, but the editor exposes only 'wght'.
        add(l, "samsung_big_clock", "Big Clock (Beta)", SAMSUNG, "ttf", 0, 0, 0);
        add(l, "samsung_capriola", "Capriola", SAMSUNG, "ttf", 0, 0, 0);
        add(l, "samsung_clock_classic", "Clock Classic", SAMSUNG, "ttf", 0, 0, 0);
        add(l, "samsung_clock_stripe", "Clock Stripe", SAMSUNG, "ttf", 0, 0, 0);
        add(l, "samsung_clock_2021", "Clock 2021", SAMSUNG, "ttf", 0, 0, 0);
        add(l, "samsung_modex", "Clock Modex", SAMSUNG, "ttf", 100, 900, 400);
        add(l, "samsung_stamp", "Clock Stamp", SAMSUNG, "ttf", 100, 900, 400);
        add(l, "samsung_trimex", "Clock Trimex", SAMSUNG, "ttf", 100, 900, 400);
        add(l, "samsung_fredericka", "Fredericka the Great", SAMSUNG, "ttf", 0, 0, 0);
        add(l, "samsung_gwnum", "GWNum 2019", SAMSUNG, "ttf", 0, 0, 0);
        add(l, "samsung_lato", "Lato", SAMSUNG, "ttf", 0, 0, 0);
        add(l, "samsung_secnum_regular", "New SEC Num Regular", SAMSUNG, "ttf", 0, 0, 0);
        add(l, "samsung_secnum_semibold", "New SEC Num SemiBold", SAMSUNG, "ttf", 0, 0, 0);
        add(l, "samsung_clock_default", "One UI Clock Default", SAMSUNG, "ttf", 100, 900, 400);
        add(l, "samsung_clock_compact", "One UI Clock Compact Rounded", SAMSUNG, "ttf", 100, 900, 400);
        add(l, "samsung_sfit", "SFit Num Medium", SAMSUNG, "ttf", 0, 0, 0);
        add(l, "samsung_fluid_regular", "Samsung One Fluid Regular", SAMSUNG, "ttf", 0, 0, 0);
        add(l, "samsung_fluid_light", "Samsung One Fluid XLight", SAMSUNG, "ttf", 0, 0, 0);
        add(l, "samsung_thom_browne", "Thom Browne", SAMSUNG, "otf", 0, 0, 0);
        add(l, "samsung_sfit_bold", "SFit Num Bold", SAMSUNG, "ttf", 0, 0, 0);
        add(l, "samsung_snum_regular", "SNum 3 Regular", SAMSUNG, "ttf", 0, 0, 0);
        add(l, "samsung_snum_light", "SNum 3 Light", SAMSUNG, "ttf", 0, 0, 0);
        add(l, "samsung_moxie", "Moxie Bold", SAMSUNG, "ttf", 0, 0, 0);
        add(l, "samsung_snum_thin", "SNum 3 Thin", SAMSUNG, "ttf", 0, 0, 0);
        // Lock screen fonts of other Android skins (OneUI-Lockscreen-Font-EXT)
        add(l, "pixel_inflate", "Google Pixel Inflate", GOOGLE, "ttf", 0, 1000, 0);
        add(l, "mi_serif", "Mi Serif", XIAOMI, "ttf", 150, 700, 330);
        add(l, "oppo_clock", "OPPO Big Clock", OPPO, "ttf", 1, 1000, 700);
        add(l, "harmony_bold", "HarmonyOS Sans Super Bold", HUAWEI, "ttf", 0, 0, 0);
        add(l, "nothing_ntype", "Nothing NType 82", NOTHING, "ttf", 0, 0, 0);
        add(l, "nothing_ndot", "Nothing Ndot 55", NOTHING, "ttf", 0, 0, 0);
        add(l, "nothing_thin", "NothingOS Thin", NOTHING, "ttf", 0, 0, 0);
        add(l, "moto_milky", "Moto Milky Stacked", MOTOROLA, "ttf", 0, 0, 0);
        // Not tied to a phone brand (also from OneUI-Lockscreen-Font-EXT)
        add(l, "bodoni_moda", "Bodoni Moda", OTHER, "ttf", 400, 900, 400);
        add(l, "dm_serif", "DM Serif Display", OTHER, "ttf", 0, 0, 0);
        add(l, "gravitas_one", "Gravitas One", OTHER, "ttf", 0, 0, 0);
        add(l, "chamberi", "Chamberi Super Display", OTHER, "ttf", 0, 0, 0);
        add(l, "geist", "Geist", OTHER, "ttf", 100, 900, 400);
        add(l, "geist_mono", "Geist Mono", OTHER, "ttf", 100, 900, 400);
        add(l, "creato", "Creato Display", OTHER, "ttf", 0, 0, 0);
        add(l, "creato_bold", "Creato Display Bold", OTHER, "ttf", 0, 0, 0);
        add(l, "poppins", "Poppins", OTHER, "ttf", 0, 0, 0);
        add(l, "comfortaa", "Comfortaa", OTHER, "ttf", 0, 0, 0);
        add(l, "badeen", "Badeen Display", OTHER, "ttf", 0, 0, 0);
        add(l, "cookierun", "CookieRun Bold", OTHER, "ttf", 0, 0, 0);
        add(l, "monoton", "Monoton", OTHER, "ttf", 0, 0, 0);
        add(l, "riviera", "Riviera", OTHER, "ttf", 0, 0, 0);
        ALL = Collections.unmodifiableList(l);
    }

    private BuiltinFonts() { }

    private static void add(List<FontSpec> l, String key, String name, String category, String ext,
                            int min, int max, int def) {
        l.add(new FontSpec(FontSpec.ID_PREFIX + key, name, category, "fonts/" + key + "." + ext, min, max, def));
    }

    static FontSpec byId(String id) {
        for (FontSpec s : ALL) {
            if (s.id.equals(id)) return s;
        }
        return null;
    }

    static int categoryOrder(String category) {
        for (int i = 0; i < CATEGORIES.length; i++) {
            if (CATEGORIES[i].equals(category)) return i;
        }
        return CATEGORIES.length;
    }
}

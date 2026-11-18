package dev.antemeridiem;

import java.util.Locale;

/**
 * One clock font as the lock screen editor sees it.
 *
 * <p>The editor stores {@code font_id} and {@code font_path} of the chosen font in the lock screen style. For our fonts
 * the id is {@code "lf.<key>"} and the path is a pseudo path {@code "/lockfonts/lf.<key>"}: it is not a real file,
 * every process that draws the clock (Themes, System UI) maps it to its own copy of the font file on the fly.
 */
final class FontSpec {
    static final String ID_PREFIX = "lf.";
    static final String CUSTOM_PREFIX = ID_PREFIX + "c.";
    static final String PATH_PREFIX = "/lockfonts/";
    /** vivo's default clock font, used when one of our fonts cannot be loaded. */
    static final String FALLBACK_PATH = "/system/fonts/vivoSansClockVF.ttf";
    /** Weight used for fonts without a weight axis (the editor hides the slider for them). */
    static final int STATIC_WEIGHT = 400;

    final String id;
    final String name;
    final String category;
    /** Asset path inside the module APK ("fonts/x.ttf"), null for fonts added by the user. */
    final String asset;
    final int weightMin, weightMax, weightDefault;

    FontSpec(String id, String name, String category, String asset, int weightMin, int weightMax, int weightDefault) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.asset = asset;
        if (weightMax > weightMin) {
            this.weightMin = weightMin;
            this.weightMax = weightMax;
            this.weightDefault = Math.max(weightMin, Math.min(weightMax, weightDefault));
        } else {
            this.weightMin = this.weightMax = this.weightDefault = STATIC_WEIGHT;
        }
    }

    boolean supportsWeight() {
        return weightMax > weightMin;
    }

    boolean isCustom() {
        return asset == null;
    }

    String pseudoPath() {
        return PATH_PREFIX + id;
    }

    /** {@code 'wght' N} for variable fonts, "" otherwise. */
    String variationSettings(int weight) {
        return supportsWeight() ? String.format(Locale.ROOT, "'wght' %d", weight) : "";
    }

    static boolean isOurId(Object id) {
        return id instanceof String && ((String) id).startsWith(ID_PREFIX) && isValidId((String) id);
    }

    static boolean isPseudoPath(Object path) {
        return idFromPath(path) != null;
    }

    /** The font id inside a pseudo path, or null when the path is a normal file path. */
    static String idFromPath(Object path) {
        if (!(path instanceof String)) return null;
        String p = (String) path;
        if (!p.startsWith(PATH_PREFIX)) return null;
        String id = p.substring(PATH_PREFIX.length());
        return isValidId(id) ? id : null;
    }

    /** Ids end up in file names and URIs: only a small, safe alphabet is accepted. */
    static boolean isValidId(String id) {
        if (id == null || !id.startsWith(ID_PREFIX) || id.length() <= ID_PREFIX.length() || id.length() > 64) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.';
            if (!ok) return false;
        }
        return !id.contains("..");
    }

    /** Last part of the id, usable as a file name. */
    String key() {
        return id.substring(ID_PREFIX.length());
    }

    @Override public String toString() {
        return id + " (" + name + ")";
    }
}

package dev.antemeridiem;

import android.graphics.Typeface;
import android.widget.ImageView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * The hooks. Names come from the vivo System UI plugin of OriginOS 6 (com.vivo.systemuiplugin 16.x).
 *
 * <ul>
 *   <li>{@code ClockFontManager.getClassicFonts}: the editor's font list. Our fonts are appended.</li>
 *   <li>{@code TimeComponent.normalFontPathList}: the clock styles accept only fonts from this list and fall back
 *       to the first one otherwise. It is replaced by a list that also accepts our pseudo paths.</li>
 *   <li>{@code Typeface.Builder} / {@code Typeface.createFromFile} (framework): pseudo paths are mapped to real files
 *       by {@link FontResolver}, whatever plugin code loads the font.</li>
 *   <li>Font list adapters: the editor shows a picture per font; ours get a "10" drawn in the font itself.</li>
 *   <li>{@code TimeColorToAodEntity}: the always-on display gets vivo's default font instead of a pseudo path
 *       it could not open.</li>
 * </ul>
 * Every hook body is wrapped: a failure must never break the lock screen.
 */
final class PluginHooks {
    private static final String P = "com.vivo.systemuiplugin.keyguard.";
    static final String CLOCK_FONT_MANAGER = P + "time.ClockFontManager";
    static final String CLOCK_FONT_STYLE = P + "time.model.ClockFontStyle";
    static final String TIME_COMPONENT = P + "time.style.TimeComponent";
    static final String AOD_ENTITY = P + "time.model.TimeColorToAodEntity";
    static final String TYPEFACE_MANAGER = P + "util.PluginTypefaceManager";
    static final String TYPEFACE_CACHE = P + "util.TypefaceCacheUtils";
    static final String[] FONT_ADAPTERS = {
            P + "settings.preview.time.adapter.TimeRecyclerFont2Adapter",
            P + "settings.preview.time.adapter.TimeRecyclerFontAdapter",
    };
    private static final String PLUGIN_CLASS_PREFIX = HookEntry.PLUGIN + ".";

    private static final AtomicBoolean FRAMEWORK = new AtomicBoolean(), WATCHING = new AtomicBoolean();
    private static final Set<ClassLoader> SEEN = Collections.newSetFromMap(new WeakHashMap<>());
    // Includes derived instances used by clock animations. Weak keys do not retain old Typefaces.
    private static final Set<Typeface> MODULE_TYPEFACES = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    private PluginHooks() { }

    // ------------------------------------------------------------------ framework

    /** Pseudo path → real file, for every way the plugin opens a font file. */
    static void installFramework() {
        if (!FRAMEWORK.compareAndSet(false, true)) return;
        Hooker redirect = chain -> {
            Object[] args = chain.getArgs().toArray();
            if (args.length > 0) {
                Object replaced = args[0];
                try {
                    replaced = redirectArg(args[0]);
                } catch (Throwable t) {
                    HookEntry.log("redirect", t);
                }
                if (replaced != args[0]) {
                    args[0] = replaced;
                    Object result = chain.proceed(args);
                    if (result instanceof Typeface && result != Typeface.DEFAULT) rememberTypeface((Typeface) result);
                    return result;
                }
            }
            return chain.proceed();
        };
        try {
            HookEntry.interceptConstructors(Typeface.Builder.class, redirect);
        } catch (Throwable t) {
            HookEntry.log("Typeface.Builder", t);
        }
        try {
            HookEntry.interceptMethods(Typeface.class, "createFromFile", redirect);
        } catch (Throwable t) {
            HookEntry.log("Typeface.createFromFile", t);
        }
    }

    /** A String or File argument holding a pseudo path is replaced with the real file; anything else is kept. */
    static Object redirectArg(Object arg) {
        if (arg instanceof String && FontSpec.isPseudoPath(arg)) return FontResolver.resolve((String) arg);
        if (arg instanceof File) {
            String path = ((File) arg).getPath().replace(File.separatorChar, '/');
            if (FontSpec.isPseudoPath(path)) return new File(FontResolver.resolve(path));
        }
        return arg;
    }

    // ------------------------------------------------------------------ plugin class loader

    /** The plugin's classes are loaded by its own class loader: catch the first one and hook through it. */
    static void watchClassLoading() {
        if (!WATCHING.compareAndSet(false, true)) return;
        try {
            HookEntry.interceptMethods(ClassLoader.class, "loadClass", chain -> {
                Object r = chain.proceed();
                if (r instanceof Class && ((Class<?>) r).getName().startsWith(PLUGIN_CLASS_PREFIX)) {
                    try {
                        tryLoader(((Class<?>) r).getClassLoader());
                        AnalogClock.onPluginClass((Class<?>) r);   // AOD clocks are loaded lazily
                    } catch (Throwable t) {
                        HookEntry.log("plugin class loader", t);
                    }
                }
                return r;
            });
        } catch (Throwable t) {
            HookEntry.log("class loading watcher", t);
        }
    }

    static void tryLoader(ClassLoader cl) {
        if (cl == null) return;
        synchronized (SEEN) {
            if (!SEEN.add(cl)) return;
        }
        Class<?> manager = find(cl, CLOCK_FONT_MANAGER);
        if (manager == null) return;     // not the plugin (or a plugin version without this class)
        hookFontList(manager, cl);
        hookAllowList(find(cl, TIME_COMPONENT));
        hookTypefaceCache(find(cl, TYPEFACE_MANAGER), find(cl, TYPEFACE_CACHE));
        for (String a : FONT_ADAPTERS) hookAdapter(find(cl, a));
        hookAod(find(cl, AOD_ENTITY));
        BigClock.install(cl);
        AnalogClock.install(cl);
        RemoteCatalog.helloAsync();
    }

    private static Class<?> find(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ editor font list

    private static void hookFontList(Class<?> manager, ClassLoader cl) {
        final Class<?> style = find(cl, CLOCK_FONT_STYLE);
        if (style == null) return;
        try {
            HookEntry.interceptMethods(manager, "getClassicFonts", chain -> {
                Object r = chain.proceed();
                if (!(r instanceof List)) return r;
                try {
                    List<FontSpec> fonts = RemoteCatalog.get(HookEntry.appContext());
                    return extendFontList((List<?>) r, style, fonts);
                } catch (Throwable t) {
                    HookEntry.log("getClassicFonts", t);
                    return r;
                }
            });
        } catch (Throwable t) {
            HookEntry.log("hook getClassicFonts", t);
        }
    }

    /** vivo's fonts followed by ours, as new {@code ClockFontStyle} objects (the editor modifies them). */
    static List<Object> extendFontList(List<?> vivo, Class<?> styleClass, List<FontSpec> fonts) throws ReflectiveOperationException {
        List<Object> out = new ArrayList<>(vivo.size() + fonts.size());
        for (Object o : vivo) {
            if (!FontSpec.isOurId(readString(o, "fontId"))) out.add(o);
        }
        for (FontSpec s : fonts) out.add(newStyle(styleClass, s));
        return out;
    }

    static Object newStyle(Class<?> c, FontSpec s) throws ReflectiveOperationException {
        int support = s.supportsWeight() ? 1 : 0;
        try {
            Constructor<?> k = c.getDeclaredConstructor(String.class, String.class, int.class, int.class, int.class,
                    int.class, int.class, boolean.class);
            k.setAccessible(true);
            return k.newInstance(s.id, s.pseudoPath(), support, s.weightMin, s.weightMax, s.weightDefault, 0, false);
        } catch (NoSuchMethodException e) {
            // another plugin version: fill the Gson fields by name
            Constructor<?> k = c.getDeclaredConstructor();
            k.setAccessible(true);
            Object o = k.newInstance();
            setField(o, "fontId", s.id);
            setField(o, "fontPath", s.pseudoPath());
            setField(o, "supportWeight", support);
            setField(o, "weightMin", s.weightMin);
            setField(o, "weightMax", s.weightMax);
            setField(o, "weightDefault", s.weightDefault);
            return o;
        }
    }

    // ------------------------------------------------------------------ font allow list of the clock styles

    /** Accepts everything the original list holds plus our pseudo paths. */
    static final class AllowList extends ArrayList<Object> {
        AllowList(Collection<?> original) {
            super(original);
        }

        @Override public boolean contains(Object o) {
            return super.contains(o) || FontSpec.isPseudoPath(o);
        }

        @Override public int indexOf(Object o) {
            int i = super.indexOf(o);
            return i >= 0 || !FontSpec.isPseudoPath(o) ? i : 0;
        }
    }

    private static void hookAllowList(final Class<?> component) {
        if (component == null) return;
        final Field field = findField(component, "normalFontPathList");
        if (field != null) {
            try {
                HookEntry.interceptConstructors(component, chain -> {
                    Object result = chain.proceed();
                    try {
                        Object self = chain.getThisObject();
                        Object v = field.get(self);
                        if (v instanceof List && !(v instanceof AllowList)) {
                            field.set(self, new AllowList((List<?>) v));
                        }
                    } catch (Throwable t) {
                        HookEntry.log("normalFontPathList", t);
                    }
                    return result;
                });
            } catch (Throwable t) {
                HookEntry.log("hook TimeComponent", t);
            }
        }
        // in case the getter is called before the constructor hook took effect
        try {
            HookEntry.interceptMethods(component, "getNormalFontPathList", chain -> {
                Object r = chain.proceed();
                return r instanceof List && !(r instanceof AllowList) ? new AllowList((List<?>) r) : r;
            });
        } catch (Throwable t) {
            HookEntry.log("hook getNormalFontPathList", t);
        }
    }

    // ------------------------------------------------------------------ weight cache isolation

    /** The vivo cache is indexed only by weight, not by font. Preview and lock screen can therefore reuse
     * a cached Typeface belonging to another font. Keep module fonts (and their animated variations) out of it. */
    private static void hookTypefaceCache(Class<?> manager, final Class<?> cache) {
        if (manager == null || cache == null) return;
        final Method create;
        try {
            create = cache.getDeclaredMethod("createTypefaceForWght", Typeface.class, int.class);
            create.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            HookEntry.log("weight cache method", e);
            return;
        }
        try {
            HookEntry.interceptMethods(manager, "getTypeface", chain -> {
                Object result = chain.proceed();
                try {
                    List<Object> a = chain.getArgs();
                    if (!a.isEmpty() && FontSpec.isPseudoPath(a.get(0))) {
                        String id = FontSpec.idFromPath(a.get(0));
                        if (result instanceof Typeface && result != Typeface.DEFAULT && FontResolver.realPath(id) != null)
                            rememberTypeface((Typeface) result);
                    }
                } catch (Throwable t) { HookEntry.log("remember typeface", t); }
                return result;
            });
            HookEntry.interceptMethods(cache, "getTypefaceForWght", chain -> {
                Object own = null;
                try {
                    List<Object> a = chain.getArgs();
                    if (a.size() >= 4 && a.get(0) instanceof Integer && isModuleTypeface(a.get(1))) {
                        int weight = (Integer) a.get(0);
                        own = moduleWeightTypeface(create, chain.getThisObject(), (Typeface) a.get(1), weight);
                        if (own != null && Boolean.TRUE.equals(a.get(3))) {
                            try {
                                Field current = cache.getDeclaredField("mCurrentWght");
                                current.setAccessible(true);
                                current.setInt(null, weight);
                            } catch (ReflectiveOperationException | RuntimeException ignored) { }
                        }
                    }
                } catch (Throwable t) {
                    HookEntry.log("weight cache bypass", t);
                    own = null;
                }
                return own != null ? own : chain.proceed();   // module fonts never go through the stock cache
            });
            HookEntry.interceptMethods(cache, "loadTypefaceForWgth", chain -> {
                try {
                    List<Object> a = chain.getArgs();
                    if (a.size() > 2 && isModuleTypeface(a.get(2))) return null;   // nothing to preload
                } catch (Throwable t) { HookEntry.log("weight cache preload", t); }
                return chain.proceed();
            });
        } catch (Throwable t) { HookEntry.log("hook weight cache", t); }
    }

    static void rememberTypeface(Typeface typeface) {
        if (typeface != null) MODULE_TYPEFACES.add(typeface);
    }

    static boolean isModuleTypeface(Object typeface) {
        return typeface instanceof Typeface && MODULE_TYPEFACES.contains(typeface);
    }

    /** Use the plugin's own variation logic, rather than its global cache; tag derivatives for animation frames. */
    static Typeface moduleWeightTypeface(Method create, Object cache, Typeface base, int weight)
            throws ReflectiveOperationException {
        Object result = create.invoke(cache, base, weight);
        if (!(result instanceof Typeface)) return null;
        Typeface typeface = (Typeface) result;
        rememberTypeface(typeface);
        return typeface;
    }

    // ------------------------------------------------------------------ previews in the font list

    private static void hookAdapter(Class<?> adapter) {
        if (adapter == null) return;
        final Field list = findField(adapter, "fontList");
        if (list == null) return;
        try {
            HookEntry.interceptMethods(adapter, "onBindViewHolder", chain -> {
                Object bound = chain.proceed();
                try {
                    List<Object> args = chain.getArgs();
                    if (args.size() < 2 || !(args.get(1) instanceof Integer)) return bound;
                    Object adapterObject = chain.getThisObject();
                    Object fonts = list.get(adapterObject);
                    int pos = (Integer) args.get(1);
                    if (!(fonts instanceof List) || pos < 0 || pos >= ((List<?>) fonts).size()) return bound;
                    Object item = ((List<?>) fonts).get(pos);
                    String id = readString(item, "fontId");
                    View itemView = itemViewOf(args.get(0));
                    if (itemView != null) {
                        // The item is not necessarily attached at bind time. Re-check the current selection
                        // after attachment, rather than keeping a recycled holder's old position.
                        itemView.post(() -> {
                            try {
                                ViewParent parent = itemView.getParent();
                                if (parent instanceof ViewGroup && isRecyclerView((ViewGroup) parent)) {
                                    FontNameOverlay.show((ViewGroup) parent, selectedFontName(adapterObject));
                                }
                            } catch (Throwable t) { HookEntry.log("font name", t); }
                        });
                    }
                    if (!FontSpec.isOurId(id)) return bound;
                    ImageView iv = imageViewOf(args.get(0));
                    if (iv == null) return bound;
                    Integer weight = readInt(item, "weightDefault");
                    Integer support = readInt(item, "supportWeight");
                    android.graphics.drawable.Drawable thumb = FontThumb.create(iv, id,
                            support != null && support != 0 && weight != null ? weight : -1);
                    if (thumb != null) iv.setImageDrawable(thumb);
                } catch (Throwable t) {
                    HookEntry.log("font preview", t);
                }
                return bound;
            });
        } catch (Throwable t) {
            HookEntry.log("hook " + adapter.getName(), t);
        }
    }

    /** The initial selection uses isSelected; subsequent clicks use the adapter's selectedPosition. */
    static String selectedFontName(Object adapter) {
        Object data = readField(adapter, "fontList");
        if (!(data instanceof List)) return null;
        List<?> fonts = (List<?>) data;
        Integer firstData = readInt(adapter, "isFirstData");
        Object selected = null;
        if (firstData == null || firstData != 0) {
            for (Object style : fonts) {
                if (Integer.valueOf(1).equals(readInt(style, "isSelected"))) { selected = style; break; }
            }
        }
        if (selected == null) {
            Integer pos = readInt(adapter, "selectedPosition");
            if (pos != null && pos >= 0 && pos < fonts.size()) selected = fonts.get(pos);
        }
        if (selected == null) return null;
        String id = readString(selected, "fontId");
        if (FontSpec.isOurId(id)) {
            for (FontSpec font : RemoteCatalog.get(HookEntry.appContext())) {
                if (font.id.equals(id)) return font.name;
            }
            FontSpec builtin = BuiltinFonts.byId(FontResolver.legacyBuiltinId(id));
            return builtin == null ? null : builtin.name;
        }
        // Stock styles expose a path rather than a human-readable name.
        String path = readString(selected, "fontPath");
        if (path == null || path.isEmpty()) return null;
        String filename = path.substring(path.lastIndexOf('/') + 1);
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static boolean isRecyclerView(View view) {
        for (Class<?> c = view.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().endsWith(".RecyclerView")) return true;
        }
        return false;
    }

    private static View itemViewOf(Object holder) {
        Object value = readField(holder, "itemView");
        return value instanceof View ? (View) value : null;
    }

    private static ImageView imageViewOf(Object holder) {
        if (holder == null) return null;
        try {
            Method m = holder.getClass().getMethod("getImageView");
            Object v = m.invoke(holder);
            if (v instanceof ImageView) return (ImageView) v;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // try the field
        }
        Field f = findField(holder.getClass(), "imageView");
        try {
            Object v = f != null ? f.get(holder) : null;
            return v instanceof ImageView ? (ImageView) v : null;
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ always-on display

    private static void hookAod(Class<?> entity) {
        if (entity == null) return;
        Hooker fix = chain -> {
            Object[] a = chain.getArgs().toArray();
            boolean changed = false;
            for (int i = 0; i < a.length; i++) {
                if (FontSpec.isPseudoPath(a[i])) {
                    a[i] = FontSpec.FALLBACK_PATH;
                    changed = true;
                }
            }
            return changed ? chain.proceed(a) : chain.proceed();
        };
        try {
            HookEntry.interceptConstructors(entity, fix);
            HookEntry.interceptMethods(entity, "setFont", fix);
        } catch (Throwable t) {
            HookEntry.log("hook AOD", t);
        }
    }

    // ------------------------------------------------------------------ reflection helpers

    static Field findField(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                if (Modifier.isStatic(f.getModifiers())) return null;
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // look in the superclass
            }
        }
        return null;
    }

    private static Object readField(Object o, String name) {
        if (o == null) return null;
        Field f = findField(o.getClass(), name);
        try {
            return f != null ? f.get(o) : null;
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static String readString(Object o, String name) {
        Object v = readField(o, name);
        return v instanceof String ? (String) v : null;
    }

    private static Integer readInt(Object o, String name) {
        Object v = readField(o, name);
        if (v instanceof Integer) return (Integer) v;
        if (v instanceof Boolean) return (Boolean) v ? 1 : 0;
        return null;
    }

    private static void setField(Object o, String name, Object value) throws IllegalAccessException {
        Field f = findField(o.getClass(), name);
        if (f != null) f.set(o, value);
    }
}

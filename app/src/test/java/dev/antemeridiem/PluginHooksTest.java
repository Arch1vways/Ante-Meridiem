package dev.antemeridiem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Typeface;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Robolectric;
import android.app.Activity;
import io.github.libxposed.api.XposedInterface;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class PluginHooksTest {
    /** Same shape as the plugin's ClockFontStyle. */
    @SuppressWarnings("unused")
    public static final class FakeStyle {
        private final String fontId;
        private final String fontPath;
        private int fontRes;
        private boolean isSelected;
        private final int supportWeight;
        private int weightDefault;
        private final int weightMax;
        private final int weightMin;

        public FakeStyle(String id, String path, int support, int min, int max, int def, int res, boolean sel) {
            fontId = id;
            fontPath = path;
            supportWeight = support;
            weightMin = min;
            weightMax = max;
            weightDefault = def;
            fontRes = res;
            isSelected = sel;
        }
    }

    /** A plugin version with another constructor: fields are filled by name. */
    @SuppressWarnings("unused")
    public static final class OtherStyle {
        private String fontId;
        private String fontPath;
        private int supportWeight;
        private int weightMin, weightMax, weightDefault;
    }

    @After public void tearDown() {
        HookEntry.modulePath = null;
        HookEntry.xposed = null;
        HOOKS.clear();
        FontResolver.reset();
    }

    @Test public void appendsOurFontsAfterVivos() throws Exception {
        List<Object> vivo = new ArrayList<>();
        vivo.add(new FakeStyle("0", "/system/fonts/vivoSansClockVF.ttf", 1, 100, 850, 400, 0, true));
        vivo.add(new FakeStyle("lf.geist", "/lockfonts/lf.geist", 1, 100, 900, 400, 0, false));  // stale copy
        List<FontSpec> ours = Arrays.asList(BuiltinFonts.byId("lf.geist"), BuiltinFonts.byId("lf.dm_serif"));
        List<Object> out = PluginHooks.extendFontList(vivo, FakeStyle.class, ours);
        assertEquals(3, out.size());
        assertSame(vivo.get(0), out.get(0));
        FakeStyle g = (FakeStyle) out.get(1);
        assertEquals("lf.geist", g.fontId);
        assertEquals("/lockfonts/lf.geist", g.fontPath);
        assertEquals(1, g.supportWeight);
        assertEquals(100, g.weightMin);
        assertEquals(900, g.weightMax);
        assertEquals(400, g.weightDefault);
        FakeStyle d = (FakeStyle) out.get(2);
        assertEquals(0, d.supportWeight);
        assertFalse(d.isSelected);
    }

    @Test public void fillsFieldsWhenTheConstructorDiffers() throws Exception {
        OtherStyle o = (OtherStyle) PluginHooks.newStyle(OtherStyle.class, BuiltinFonts.byId("lf.sf_pro"));
        assertEquals("lf.sf_pro", o.fontId);
        assertEquals("/lockfonts/lf.sf_pro", o.fontPath);
        assertEquals(1, o.supportWeight);
        assertEquals(1000, o.weightMax);
    }

    @Test public void allowListAcceptsPseudoPaths() {
        List<String> vivo = Arrays.asList("/system/fonts/vivoSansClockVF.ttf", "/system/fonts/vivoSansClockRoundedVF.ttf");
        PluginHooks.AllowList l = new PluginHooks.AllowList(vivo);
        assertTrue(l.contains("/system/fonts/vivoSansClockRoundedVF.ttf"));
        assertTrue(l.contains("/lockfonts/lf.sf_pro"));
        assertFalse(l.contains("/sdcard/evil.ttf"));
        assertEquals(1, l.indexOf("/system/fonts/vivoSansClockRoundedVF.ttf"));
        assertEquals(-1, l.indexOf("/sdcard/evil.ttf"));
        assertEquals("/system/fonts/vivoSansClockVF.ttf", l.get(0));
        assertEquals(2, l.size());
    }

    public static final class FakeFontAdapter {
        List<FakeStyle> fontList;
        boolean isFirstData = true;
        int selectedPosition;
    }

    @Test public void fontNameTracksInitialAndChangedSelection() {
        FakeFontAdapter adapter = new FakeFontAdapter();
        adapter.fontList = Arrays.asList(
                new FakeStyle("0", "/system/fonts/vivoSansClockVF.ttf", 1, 100, 850, 400, 0, true),
                new FakeStyle("lf.sf_pro", "/lockfonts/lf.sf_pro", 1, 1, 1000, 400, 0, false));
        assertEquals("vivoSansClockVF", PluginHooks.selectedFontName(adapter));
        adapter.isFirstData = false;
        adapter.selectedPosition = 1;
        assertEquals("SF Pro", PluginHooks.selectedFontName(adapter));
        adapter.selectedPosition = -1;
        assertNull(PluginHooks.selectedFontName(adapter));
    }

    @Test public void captionUpdatesWithoutInterceptingTouches() {
        Activity context = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout parent = new FrameLayout(context);
        FrameLayout list = new FrameLayout(context);
        parent.addView(list, new FrameLayout.LayoutParams(360, 100));
        context.setContentView(parent);
        parent.layout(0, 0, 360, 200);
        list.layout(0, 0, 360, 100);
        FontNameOverlay.show(list, "SF Pro");
        FontNameOverlay overlay = FontNameOverlay.currentForTest(list);
        assertEquals("SF Pro", overlay.label.getText().toString());
        assertFalse(overlay.label.isClickable());
        assertTrue(overlay.label.getWidth() > 0);
        assertTrue("caption is below the list", overlay.label.getTop() >= list.getBottom());
        assertNull(overlay.label.getBackground());
        FontNameOverlay.show(list, "DM Serif Display");
        assertSame(overlay, FontNameOverlay.currentForTest(list));
        assertEquals("DM Serif Display", overlay.label.getText().toString());
        list.setVisibility(View.GONE);
        overlay.onGlobalLayout();
        assertEquals(View.GONE, overlay.label.getVisibility());
        list.setVisibility(View.VISIBLE);
        overlay.onGlobalLayout();
        assertEquals(View.VISIBLE, overlay.label.getVisibility());
        FontNameOverlay.show(list, null);
        assertEquals(View.GONE, overlay.label.getVisibility());
        overlay.onViewDetachedFromWindow(list);
        assertNull(FontNameOverlay.currentForTest(list));
    }

    public static class FakeWeightCache {
        static int mCurrentWght;
        Typeface seen;
        int weight;
        Typeface derived = Typeface.create("serif", Typeface.BOLD);
        public Typeface createTypefaceForWght(Typeface base, int weight) {
            this.seen = base;
            this.weight = weight;
            return derived;
        }
        // the stock cache entry points, only hooked here
        public Typeface getTypefaceForWght(int weight, Typeface base, boolean animate, boolean current) { return base; }
        public void loadTypefaceForWgth(int from, int to, Typeface base, boolean animate) { }
    }

    @Test public void moduleWeightBypassesTheUnrelatedStockCache() throws Exception {
        Typeface module = Typeface.create("monospace", Typeface.NORMAL);
        Typeface stock = Typeface.create("sans-serif", Typeface.NORMAL);
        assertFalse(PluginHooks.isModuleTypeface(stock));
        PluginHooks.rememberTypeface(module);
        assertTrue(PluginHooks.isModuleTypeface(module));
        FakeWeightCache cache = new FakeWeightCache();
        Typeface result = PluginHooks.moduleWeightTypeface(FakeWeightCache.class.getMethod(
                "createTypefaceForWght", Typeface.class, int.class), cache, module, 600);
        assertSame(module, cache.seen);
        assertEquals(600, cache.weight);
        assertSame(cache.derived, result);
        assertTrue(PluginHooks.isModuleTypeface(result)); // the animator feeds it into the next frame
        assertFalse(PluginHooks.isModuleTypeface(stock));
    }

    public static class FakeManager {
        public Typeface getTypeface(String path, String variation) { return null; }
    }

    /** Hookers installed through HookEntry, keyed "Class#method" (constructors: "Class#<init>"). */
    private static final Map<String, XposedInterface.Hooker> HOOKS = new HashMap<>();

    /** Stand-in for the framework: records every hook(target)...intercept(hooker). */
    private static XposedInterface recordingXposed() {
        ClassLoader loader = XposedInterface.class.getClassLoader();
        return (XposedInterface) Proxy.newProxyInstance(loader, new Class<?>[]{XposedInterface.class},
                (proxy, method, args) -> {
                    if (!"hook".equals(method.getName())) return null;
                    Executable target = (Executable) args[0];
                    String key = target.getDeclaringClass().getName() + "#"
                            + (target instanceof Constructor ? "<init>" : target.getName());
                    return Proxy.newProxyInstance(loader, new Class<?>[]{method.getReturnType()}, (builder, m, a) -> {
                        if ("intercept".equals(m.getName())) {
                            HOOKS.put(key, (XposedInterface.Hooker) a[0]);
                            return null;
                        }
                        return builder;   // setPriority / setExceptionMode / setId
                    });
                });
    }

    /** One call of a hooked method whose original returns {@code original}. */
    private static final class FakeChain implements XposedInterface.Chain {
        private final Object self;
        private final Object original;
        private final List<Object> args;
        boolean proceeded;

        FakeChain(Object self, Object original, Object... args) {
            this.self = self;
            this.original = original;
            this.args = Collections.unmodifiableList(Arrays.asList(args));
        }

        @Override public Executable getExecutable() { return null; }
        @Override public Object getThisObject() { return self; }
        @Override public List<Object> getArgs() { return args; }
        @Override public Object getArg(int index) { return args.get(index); }
        @Override public Object proceed() { proceeded = true; return original; }
        @Override public Object proceed(Object[] newArgs) { proceeded = true; return original; }
        @Override public Object proceedWith(Object thisObject) { proceeded = true; return original; }
        @Override public Object proceedWith(Object thisObject, Object[] newArgs) { proceeded = true; return original; }
    }

    @Test public void pluginHooksIsolatePreviewAndAnimationFromStockCache() throws Throwable {
        FontSpec spec = BuiltinFonts.byId("lf.geist");
        File apk = File.createTempFile("module", ".apk");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(apk))) {
            z.putNextEntry(new ZipEntry("assets/" + spec.asset));
            z.write(Files.readAllBytes(BuiltinFontsTest.asset(spec).toPath()));
            z.closeEntry();
        }
        HookEntry.modulePath = apk.getPath();
        HOOKS.clear();
        HookEntry.xposed = recordingXposed();
        java.lang.reflect.Method install = PluginHooks.class.getDeclaredMethod("hookTypefaceCache", Class.class, Class.class);
        install.setAccessible(true); install.invoke(null, FakeManager.class, FakeWeightCache.class);
        String manager = FakeManager.class.getName(), cacheClass = FakeWeightCache.class.getName();

        Typeface base = Typeface.create("monospace", Typeface.BOLD);
        FakeChain loaded = new FakeChain(new FakeManager(), base, spec.pseudoPath(), "");
        assertSame(base, HOOKS.get(manager + "#getTypeface").intercept(loaded));
        assertTrue(loaded.proceeded);
        assertTrue(PluginHooks.isModuleTypeface(base));

        FakeWeightCache cache = new FakeWeightCache();
        Object stock = new Object();
        FakeChain request = new FakeChain(cache, stock, 600, base, true, true);
        assertSame(cache.derived, HOOKS.get(cacheClass + "#getTypefaceForWght").intercept(request));
        assertFalse("the stock cache is skipped", request.proceeded);
        assertSame(base, cache.seen); assertEquals(600, FakeWeightCache.mCurrentWght);

        FakeChain preload = new FakeChain(cache, stock, 400, 600, base, false);
        assertNull(HOOKS.get(cacheClass + "#loadTypefaceForWgth").intercept(preload));
        assertFalse(preload.proceeded);

        FakeChain normal = new FakeChain(cache, stock, 600, Typeface.DEFAULT, true, true);
        assertSame(stock, HOOKS.get(cacheClass + "#getTypefaceForWght").intercept(normal));
        assertTrue(normal.proceeded);
        assertTrue(apk.delete());
    }

    @Test public void requestedFontsAreRemoved() throws Exception {
        assertNull(BuiltinFonts.byId("lf.mi_neu_rounded"));
        assertNull(BuiltinFonts.byId("lf.oppo_clock_tall"));
        assertEquals(FontSpec.FALLBACK_PATH, FontResolver.resolve("/lockfonts/lf.mi_neu_rounded"));
        assertEquals(FontSpec.FALLBACK_PATH, FontResolver.resolve("/lockfonts/lf.oppo_clock_tall"));
        List<FontSpec> fonts = Arrays.asList(BuiltinFonts.byId("lf.mi_serif"), BuiltinFonts.byId("lf.oppo_clock"));
        List<Object> out = PluginHooks.extendFontList(new ArrayList<>(), FakeStyle.class, fonts);
        assertEquals("lf.mi_serif", ((FakeStyle) out.get(0)).fontId);
        assertEquals("lf.oppo_clock", ((FakeStyle) out.get(1)).fontId);
    }

    @Test public void redirectsPseudoPathsToUnpackedFonts() throws Exception {
        FontSpec geist = BuiltinFonts.byId("lf.geist");
        File src = BuiltinFontsTest.asset(geist);
        File apk = File.createTempFile("module", ".apk");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(apk))) {
            z.putNextEntry(new ZipEntry("assets/" + geist.asset));
            z.write(Files.readAllBytes(src.toPath()));
            z.closeEntry();
        }
        HookEntry.modulePath = apk.getPath();

        Object real = PluginHooks.redirectArg(geist.pseudoPath());
        assertTrue(real instanceof String);
        File f = new File((String) real);
        assertTrue(f.isFile());
        assertEquals(src.length(), f.length());
        Object asFile = PluginHooks.redirectArg(new File(geist.pseudoPath()));
        assertEquals(f, asFile);

        // fonts that are not there fall back to vivo's clock font, other paths stay as they are
        assertEquals(FontSpec.FALLBACK_PATH, PluginHooks.redirectArg("/lockfonts/lf.sf_pro"));
        assertEquals(FontSpec.FALLBACK_PATH, PluginHooks.redirectArg("/lockfonts/lf.c.0000"));
        assertEquals("/system/fonts/Roboto.ttf", PluginHooks.redirectArg("/system/fonts/Roboto.ttf"));
        Integer other = 7;
        assertSame(other, PluginHooks.redirectArg(other));
        assertTrue(apk.delete());
    }
}

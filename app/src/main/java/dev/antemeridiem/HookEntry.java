package dev.antemeridiem;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;

/**
 * LSPosed entry point (libxposed API 102).
 *
 * <p>The OriginOS lock screen and its editor (pinch the lock screen → Clock → Font) come from the vivo System UI
 * plugin. The plugin runs inside Themes (the editor) and inside System UI (the real lock screen), loaded by its own
 * class loader, so the hooks are installed as soon as a plugin class shows up - see {@link PluginHooks}.
 */
public final class HookEntry extends XposedModule {
    static final String THEMES = "com.bbk.theme";
    static final String SYSTEMUI = "com.android.systemui";
    static final String PLUGIN = "com.vivo.systemuiplugin";
    static final String TAG = "LockFonts";
    private static final List<String> SCOPE = Arrays.asList(THEMES, SYSTEMUI, PLUGIN);
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    /** The module APK: the built-in fonts are read straight from it. */
    static volatile String modulePath;

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        xposed = this;
        modulePath = getModuleApplicationInfo().sourceDir;   // the module APK
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        if (!SCOPE.contains(param.getPackageName())) return;
        if (STARTED.compareAndSet(false, true)) {
            PluginHooks.installFramework();
            PluginHooks.watchClassLoading();
        }
        // System UI and Themes load the plugin APK as a package of its own: its class loader arrives here directly.
        PluginHooks.tryLoader(param.getClassLoader());
    }

    /** The hooked process' application, or null very early during start-up. */
    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    static Context appContext() {
        try {
            Object app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static void log(String msg, Throwable t) {
        Log.w(TAG, msg, t);
    }

    // ------------------------------------------------------------------ libxposed API 102 hooks

    private static final String HOOK_TAG = "LockFonts";

    /** Framework interface of the loaded module, set in onModuleLoaded (tests put a fake here). */
    static volatile XposedInterface xposed;

    /** Intercepts one method or constructor: {@code hook(target).intercept(hooker)}. */
    static HookHandle intercept(Executable target, Hooker hooker) {
        XposedInterface x = xposed;
        if (x == null) throw new IllegalStateException("Xposed module is not loaded");
        return x.hook(target)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker);
    }

    /** Every non-abstract method with this name declared by {@code type}; fails only if none could be hooked. */
    static List<HookHandle> interceptMethods(Class<?> type, String name, Hooker hooker) {
        List<Executable> targets = new ArrayList<>();
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(name) && !Modifier.isAbstract(m.getModifiers())) targets.add(m);
        }
        return interceptAll(targets, hooker);
    }

    /** Every constructor declared by {@code type}; fails only if none could be hooked. */
    static List<HookHandle> interceptConstructors(Class<?> type, Hooker hooker) {
        List<Executable> targets = new ArrayList<>();
        for (Constructor<?> c : type.getDeclaredConstructors()) targets.add(c);
        return interceptAll(targets, hooker);
    }

    private static List<HookHandle> interceptAll(List<Executable> targets, Hooker hooker) {
        List<HookHandle> out = new ArrayList<>(targets.size());
        Throwable failure = null;
        for (Executable e : targets) {
            try {
                out.add(intercept(e, hooker));
            } catch (Throwable t) {
                failure = t;
                hookError("hook " + e, t);
            }
        }
        if (out.isEmpty() && failure != null) {
            if (failure instanceof Error) throw (Error) failure;
            throw failure instanceof RuntimeException ? (RuntimeException) failure : new RuntimeException(failure);
        }
        return out;
    }

    private static void hookError(String what, Throwable t) {
        XposedInterface x = xposed;
        if (x != null) x.log(Log.ERROR, HOOK_TAG, what, t);
        else Log.e(HOOK_TAG, what, t);
    }
}

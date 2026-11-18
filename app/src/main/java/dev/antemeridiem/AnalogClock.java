package dev.antemeridiem;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.NoCopySpan;
import android.text.SpanWatcher;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.style.CharacterStyle;
import android.text.style.ReplacementSpan;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Analog layout of the lock screen clock: only 12, 3, 6 and 9 around a dial, plus optional hands.
 *
 * <p>The dial is a {@link ReplacementSpan} inside the plugin's own clock TextView. The TextView keeps drawing with its
 * own paint, so the font picked in the editor (and its weight), the colour, the depth effect copy ("Dof" views) and
 * the always-on display tint all stay exactly as they are: only the position of the digits changes. Hour and minute
 * views of stacked styles are merged: the dial goes into the hour view, the minute view becomes empty.
 *
 * <p>The switch lives in the app; hooked processes ask {@link FontsProvider} off the main thread and listen for
 * changes. Every hook body is wrapped: a failure must never break the lock screen.
 */
final class AnalogClock {
    private static final String ROOT = "com.vivo.systemuiplugin.keyguard.time.style.";
    /** Hour view (gets the dial) and the matching minute view (gets emptied). */
    private static final String[][] PAIRS = {{"hourView", "minuteView"}, {"hourViewDof", "minuteViewDof"}};
    static final String KEY_ANALOG = "analog", KEY_HANDS = "hands", KEY_SCALE = "clock_scale";
    static final int MIN_SCALE = 50, MAX_SCALE = 150, DEFAULT_SCALE = 100;

    /** Maximum dial diameter as a share of the short screen side (lock screen / always-on display). */
    static final float DIAL = 0.80f, AOD_DIAL = 0.62f;
    /** Minute hand length (pivot to tip) as a share of the dial diameter. */
    static final float MINUTE_HAND = 0.27f;
    /**
     * The hands of design/clock-hands.svg in its own units: pivot at (0, 0), pointing up (12 o'clock), as rounded
     * rects {left, top, right, bottom, corner radius}. Body and stem make the black outline; the inner part (beige
     * in the drawing) is painted in the clock colour.
     */
    private static final float[] STEM = {-13.5f, -140.5f, 13.5f, 13.5f, 13.5f},
            HOUR_BODY = {-27.5f, -322.5f, 27.5f, -50.5f, 27.5f},
            HOUR_INNER = {-12.5f, -306.5f, 12.5f, -69.5f, 12.5f},
            MINUTE_BODY = {-27.5f, -578.5f, 27.5f, -50.5f, 27.5f},
            MINUTE_INNER = {-12.5f, -565.5f, 12.5f, -64.5f, 12.5f},
            SECOND_BAR = {-3.5f, -510f, 3.5f, -9f, 3.5f},
            SECOND_TAIL = {-3.5f, 7f, 3.5f, 121f, 3.5f};
    /** Second hand ring and its hole, the minute hand length in drawing units, the shadow under the hands. */
    private static final float RING = 12f, RING_HOLE = 3.717f, HAND_UNITS = 578.5f, SHADOW_DY = 4f, SHADOW_BLUR = 3.5f;
    /** Least room between two neighbouring numbers at every size, as a share of the dial. */
    static final float TOUCH_GAP = 0.02f;
    /** The numbers of the dial: top, right, bottom, left. */
    private static final String[] LABELS = {"12", "3", "6", "9"};
    private static final int SECOND_COLOR = 0xFFFF0000;
    /** The outline on the always-on display: the clock colour, dimmed (black would vanish on black). */
    private static final float AOD_OUTLINE = 0.35f;
    private static final long REFRESH_MS = 15_000;

    private static final Pattern TIME = Pattern.compile("\\s*\\p{Nd}{1,2}\\s*[:\uFF1A]?\\s*\\p{Nd}{2}\\s*");
    private static final Pattern PART = Pattern.compile("\\s*\\p{Nd}{1,2}\\s*");

    /** How a tracked clock TextView is drawn. */
    static final class Role {
        final boolean primary, aod;
        final WeakReference<TextView> partner;
        final WeakReference<Object> component;

        Role(boolean primary, boolean aod, TextView partner, Object component) {
            this.primary = primary;
            this.aod = aod;
            this.partner = new WeakReference<>(partner);
            this.component = new WeakReference<>(component);
        }
    }

    private static final Map<TextView, Role> TRACKED = Collections.synchronizedMap(new WeakHashMap<>());
    /** Lock screen dials with a second hand: redrawn every second while they are on screen. */
    private static final Set<TextView> TICKING = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Runnable TICK = AnalogClock::tick;
    private static volatile boolean ticking;
    private static final Set<Object> COMPONENTS = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Set<View> AOD_ROOTS = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Set<Class<?>> AOD_CLASSES = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));
    private static final AtomicBoolean SET_TEXT = new AtomicBoolean(), FETCHING = new AtomicBoolean(),
            AGAIN = new AtomicBoolean(), OBSERVING = new AtomicBoolean();
    private static volatile Handler main;
    private static final ThreadLocal<Boolean> WRITING = ThreadLocal.withInitial(() -> false);
    private static final View.OnAttachStateChangeListener CLOCK_ATTACH = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View v) {
            try { trackedTextChanged((TextView) v); } catch (Throwable t) { HookEntry.log("analog attach", t); }
        }
        @Override public void onViewDetachedFromWindow(View v) { TICKING.remove(v); }
    };
    private static final View.OnLayoutChangeListener CLOCK_LAYOUT = (v, l, t, r, b, ol, ot, or, ob) -> {
        if (!AnalogClock.analog || l == r || t == b) return;
        try {
            TextView text = (TextView) v;
            Role role = TRACKED.get(text);
            if (role == null || !role.primary) return;
            boolean aod = role.aod || inAod(text);
            CharSequence value = text.getText();
            DialSpan[] spans = value instanceof Spanned
                    ? ((Spanned) value).getSpans(0, value.length(), DialSpan.class) : new DialSpan[0];
            if (spans.length == 0 || spans[0].dial != dialSize(text, aod) || spans[0].aod != aod) apply(text, role, true);
        } catch (Throwable e) { HookEntry.log("analog layout", e); }
    };

    /** Settings from the app; digital until the first answer arrives. */
    static volatile boolean analog, hands = true;
    static volatile int scale = DEFAULT_SCALE;
    private static volatile long fetchedAt = Long.MIN_VALUE / 2;
    private static boolean reapplyPosted;

    private AnalogClock() { }

    // ------------------------------------------------------------------ installation

    /** S1..S3 time components go through {@link BigClock}'s hooks; other style numbers are hooked here. */
    static void install(ClassLoader loader) {
        hookSetText();
        for (int n = 4; n <= 9; n++) {
            Class<?> type;
            try {
                type = Class.forName(ROOT + "s" + n + ".S" + n + "TimeComponent", false, loader);
            } catch (Throwable t) {
                continue;
            }
            Hooker hook = chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    attach(self);
                    if (enabled()) render(self);
                } catch (Throwable t) { HookEntry.log("analog component", t); }
                return result;
            };
            for (String method : new String[]{"updateFont", "updateTime"}) {
                try {
                    HookEntry.interceptMethods(type, method, hook);
                } catch (Throwable t) { HookEntry.log("analog " + type.getName() + "." + method, t); }
            }
        }
        refreshAsync();
    }

    /** Whatever the plugin writes into a tracked clock view is laid out as a dial again right away. */
    private static void hookSetText() {
        if (!SET_TEXT.compareAndSet(false, true)) return;
        try {
            Method m = TextView.class.getMethod("setText", CharSequence.class, TextView.BufferType.class);
            HookEntry.intercept(m, chain -> {
                Object result = chain.proceed();
                try {
                    Object o = chain.getThisObject();
                    if (o instanceof TextView && !WRITING.get() && !TRACKED.isEmpty() && enabled()) {
                        trackedTextChanged((TextView) o);
                    }
                } catch (Throwable t) { HookEntry.log("analog text", t); }
                return result;
            });
        } catch (Throwable t) {
            HookEntry.log("hook TextView.setText", t);
        }
    }

    /** Called for every class the plugin loads: AOD clock views are loaded lazily, after the keyguard. */
    static void onPluginClass(Class<?> type) {
        if (!isAodView(type)) return;
        if (!AOD_CLASSES.add(type)) return;
        Hooker hook = chain -> {
            Object result = chain.proceed();
            try {
                Object o = chain.getThisObject();
                if (o instanceof View) scheduleAodScan((View) o);
            } catch (Throwable t) { HookEntry.log("analog AOD", t); }
            return result;
        };
        Hooker visibilityHook = chain -> {
            Object result = chain.proceed();
            try {
                Object o = chain.getThisObject();
                List<Object> args = chain.getArgs();
                if (o instanceof View && !args.isEmpty() && Integer.valueOf(View.VISIBLE).equals(args.get(0))) {
                    scheduleAodScan((View) o);
                }
            } catch (Throwable t) { HookEntry.log("analog AOD visibility", t); }
            return result;
        };
        try {
            HookEntry.interceptConstructors(type, hook);
        } catch (Throwable ignored) {
            // the lifecycle hooks below still cover attachment
        }
        for (String method : new String[]{"onFinishInflate", "onAttachedToWindow"}) {
            try {
                HookEntry.interceptMethods(type, method, hook);
            } catch (Throwable ignored) {
                // not declared by this class
            }
        }
        try {
            HookEntry.interceptMethods(type, "onWindowVisibilityChanged", visibilityHook);
        } catch (Throwable ignored) {
            // not declared by this class
        }
    }

    static boolean isAodView(Class<?> type) {
        if (type == null || !View.class.isAssignableFrom(type)) return false;
        String name = type.getName().toLowerCase(Locale.ROOT);
        return name.startsWith("com.vivo.systemuiplugin.") && name.contains("aod")
                && (name.contains("clock") || name.contains("time"));
    }

    // ------------------------------------------------------------------ settings

    static boolean enabled() {
        if (!FETCHING.get() && SystemClock.uptimeMillis() - fetchedAt > REFRESH_MS) refreshAsync();
        return analog;
    }

    private static Handler main() {
        Handler h = main;
        if (h == null) main = h = new Handler(Looper.getMainLooper());
        return h;
    }

    /** Asks the app for the switches, off the main thread; applies them on the main thread when they changed. */
    static void refreshAsync() {
        AGAIN.set(true);
        if (!FETCHING.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            Context ctx = null;
            try {
                ctx = HookEntry.appContext();
                for (int i = 0; ctx == null && i < 20; i++) {
                    SystemClock.sleep(500);
                    ctx = HookEntry.appContext();
                }
                if (ctx == null) return;
                observe(ctx);
                while (AGAIN.getAndSet(false)) {
                    Bundle b = null;
                    try {
                        b = ctx.getContentResolver().call(FontsProvider.CONTENT_URI, FontsProvider.METHOD_CLOCK, null, null);
                    } catch (RuntimeException ignored) {
                        // app not installed / not visible / provider crashed: keep the last known state
                    }
                    fetchedAt = SystemClock.uptimeMillis();
                    if (b != null) {
                        Bundle settings = b;
                        main().post(() -> {
                            update(settings.getBoolean(KEY_ANALOG, false), settings.getBoolean(KEY_HANDS, true),
                                    settings.getInt(KEY_SCALE, DEFAULT_SCALE));
                            ClockLayout.update(settings.getBoolean(ClockLayout.KEY_DATE_HIDDEN, false),
                                    settings.getBoolean(ClockLayout.KEY_DATE_FONT, false),
                                    settings.getInt(ClockLayout.KEY_DIGITAL_SCALE, ClockLayout.DEFAULT_DIGITAL_SCALE));
                        });
                    }
                }
            } catch (Throwable e) {
                HookEntry.log("analog settings", e);
            } finally {
                fetchedAt = SystemClock.uptimeMillis();
                FETCHING.set(false);
                // A notification may arrive between the last loop check and releasing FETCHING.
                if (ctx != null && AGAIN.get()) refreshAsync();
            }
        }, "LockFonts-analog");
        t.setDaemon(true);
        t.start();
    }

    static int clampScale(int percent) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, percent));
    }

    static void update(boolean analogLayout, boolean showHands, int percent) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main().post(() -> update(analogLayout, showHands, percent));
            return;
        }
        int size = clampScale(percent);
        boolean changed = analogLayout != analog || (analogLayout && (showHands != hands || size != scale));
        analog = analogLayout;
        hands = showHands;
        scale = size;
        if (!analog || !hands) {
            main().removeCallbacks(TICK);
            ticking = false;
            TICKING.clear();
        }
        if (changed) {
            reapplyAll();
            ClockLayout.applyNow();
        }
    }

    private static void observe(Context ctx) {
        if (!OBSERVING.compareAndSet(false, true)) return;
        try {
            ctx.getContentResolver().registerContentObserver(FontsProvider.CONTENT_URI, true,
                    new ContentObserver(main()) {
                        @Override public void onChange(boolean selfChange) {
                            refreshAsync();
                        }
                    });
        } catch (Throwable t) {
            OBSERVING.set(false);
            HookEntry.log("analog observer", t);   // the periodic refresh still picks changes up
        }
    }

    // ------------------------------------------------------------------ lock screen components

    /** Remembers the clock views of a time component (hour view with its minute partner). */
    static void attach(Object component) {
        if (component == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main().post(() -> attach(component));
            return;
        }
        COMPONENTS.add(component);
        ClockLayout.component(component);
        for (String[] pair : PAIRS) {
            TextView hour = textField(component, pair[0]), minute = textField(component, pair[1]);
            if (hour != null) track(hour, new Role(true, false, minute, component));
            if (minute != null) track(minute, new Role(false, false, hour, component));
        }
    }

    private static void track(TextView view, Role role) {
        Role old = TRACKED.get(view);
        if (old != null && old.primary == role.primary && old.aod == role.aod
                && old.partner.get() == role.partner.get() && old.component.get() == role.component.get()) return;
        TRACKED.put(view, role);
        if (old == null) {
            view.addOnAttachStateChangeListener(CLOCK_ATTACH);
            view.addOnLayoutChangeListener(CLOCK_LAYOUT);
        }
    }

    /** Lays the component's clock out as a dial now (new font, new time). */
    static void render(Object component) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main().post(() -> render(component));
            return;
        }
        for (String[] pair : PAIRS) {
            for (String name : pair) {
                TextView v = textField(component, name);
                Role role = v != null ? TRACKED.get(v) : null;
                if (role != null) apply(v, role, true);
            }
        }
    }

    private static TextView textField(Object o, String name) {
        Field f = PluginHooks.findField(o.getClass(), name);
        try {
            Object v = f != null ? f.get(o) : null;
            return v instanceof TextView ? (TextView) v : null;
        } catch (IllegalAccessException | RuntimeException e) {
            return null;
        }
    }

    /** After a switch in the app: every known clock follows at once. */
    static void reapplyAll() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main().post(AnalogClock::reapplyAll);
            return;
        }
        List<Map.Entry<TextView, Role>> views;
        synchronized (TRACKED) {
            views = new ArrayList<>(TRACKED.entrySet());
        }
        for (Map.Entry<TextView, Role> e : views) {
            try {
                if (analog) apply(e.getKey(), e.getValue(), true);
                else strip(e.getKey());
            } catch (Throwable t) { HookEntry.log("analog reapply", t); }
        }
        List<Object> components;
        synchronized (COMPONENTS) {
            components = new ArrayList<>(COMPONENTS);
        }
        if (!analog) {
            for (Object c : components) {
                try { BigClock.reapply(c); } catch (Throwable t) { HookEntry.log("analog restore", t); }
            }
        }
        List<View> roots;
        synchronized (AOD_ROOTS) {
            roots = new ArrayList<>(AOD_ROOTS);
        }
        if (analog) {
            for (View r : roots) {
                try { scanAod(r); } catch (Throwable t) { HookEntry.log("analog AOD rescan", t); }
            }
        }
        ClockLayout.schedule();
    }

    // ------------------------------------------------------------------ second hand

    /** Starts the once-a-second redraw (aligned to the wall clock second) unless it is already running. */
    static void kickTicker() {
        if (ticking || !analog || !hands || TICKING.isEmpty()) return;
        ticking = true;
        main().postDelayed(TICK, nextSecond());
    }

    private static long nextSecond() {
        return 1000 - System.currentTimeMillis() % 1000 + 5;
    }

    /** Redraws the visible dials; stops by itself when none is on a lit screen (the next draw starts it again). */
    private static void tick() {
        List<TextView> views;
        synchronized (TICKING) {
            views = new ArrayList<>(TICKING);
        }
        boolean any = false;
        for (TextView v : views) {
            try {
                if (!analog || !hands || !hasSeconds(v.getText())) {
                    TICKING.remove(v);
                    continue;
                }
                if (!v.isAttachedToWindow() || !v.isShown() || v.getAlpha() <= 0f) continue;
                Display display = v.getDisplay();
                if (display != null && display.getState() != Display.STATE_ON) continue;
                v.invalidate();
                any = true;
            } catch (Throwable t) { HookEntry.log("analog tick", t); }
        }
        if (any) main().postDelayed(TICK, nextSecond());
        else ticking = false;
    }

    private static boolean hasSeconds(CharSequence text) {
        if (!(text instanceof Spanned)) return false;
        for (DialSpan s : ((Spanned) text).getSpans(0, text.length(), DialSpan.class)) {
            if (!s.aod) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ always-on display

    private static void scheduleAodScan(View root) {
        AOD_ROOTS.add(root);
        Runnable scan = () -> {
            try {
                if (enabled()) scanAod(root);
                ClockLayout.aodRoot(root);
            } catch (Throwable t) { HookEntry.log("analog AOD scan", t); }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) scan.run();
        firstFrames(root, scan);
        root.post(scan);
        root.postDelayed(() -> {
            if (root.isAttachedToWindow() && root.getWindowVisibility() == View.VISIBLE) scan.run();
        }, 600);   // the time text is often set right after attachment
    }

    /**
     * Runs {@code scan} and {@link ClockLayout#applyNow()} on the first pre-draw pass without cancelling the draw:
     * returning {@code false} on the shared window {@code ViewTreeObserver} drops frames during AOD transitions.
     */
    private static void firstFrames(View root, Runnable scan) {
        android.view.ViewTreeObserver o = root.getViewTreeObserver();
        if (!o.isAlive()) return;
        o.addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                try {
                    scan.run();
                    ClockLayout.applyNow();
                } catch (Throwable t) {
                    HookEntry.log("analog AOD first frame", t);
                }
                android.view.ViewTreeObserver now = root.getViewTreeObserver();
                if (now.isAlive()) now.removeOnPreDrawListener(this);
                return true;
            }
        });
    }

    /** The big time text(s) of an AOD clock: [whole time] or [hour, minute] or [hour]; empty when there is none. */
    static List<TextView> aodTimes(View root) {
        float minPx = 36f * root.getResources().getDisplayMetrics().density;
        List<TextView> found = new ArrayList<>();
        collect(root, found, minPx);
        List<TextView> out = new ArrayList<>();
        if (found.isEmpty()) return out;
        TextView whole = null;
        List<TextView> parts = new ArrayList<>();
        float biggest = 0;
        for (TextView t : found) biggest = Math.max(biggest, t.getTextSize());
        for (TextView t : found) {
            if (t.getTextSize() < biggest * 0.8f) continue;
            String s = t.getText().toString();
            if (digits(s).length() >= 3) {
                if (whole == null) whole = t;
            } else {
                parts.add(t);
            }
        }
        if (whole != null) {
            out.add(whole);
            return out;
        }
        Collections.sort(parts, (a, b) -> {
            int[] la = new int[2], lb = new int[2];
            a.getLocationOnScreen(la);
            b.getLocationOnScreen(lb);
            return la[1] != lb[1] ? Integer.compare(la[1], lb[1]) : Integer.compare(la[0], lb[0]);
        });
        for (int i = 0; i < parts.size() && i < 2; i++) out.add(parts.get(i));
        return out;
    }

    /** Finds the big time text(s) of an AOD clock and turns them into a dial. */
    static void scanAod(View root) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main().post(() -> scanAod(root));
            return;
        }
        List<TextView> times = aodTimes(root);
        if (times.isEmpty()) return;
        TextView hour = times.get(0), minute = times.size() > 1 ? times.get(1) : null;
        track(hour, new Role(true, true, minute, null));
        if (minute != null) track(minute, new Role(false, true, hour, null));
        Role hr = TRACKED.get(hour);
        if (hr != null) apply(hour, hr, false);
        Role mr = minute != null ? TRACKED.get(minute) : null;
        if (mr != null) apply(minute, mr, false);
    }

    private static void collect(View v, List<TextView> out, float minPx) {
        if (v.getVisibility() == View.GONE) return;
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            CharSequence s = t.getText();
            if (TRACKED.containsKey(t) || (s != null && t.getTextSize() >= minPx
                    && (TIME.matcher(s).matches() || PART.matcher(s).matches()))) {
                if (s != null && s.length() > 0) out.add(t);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out, minPx);
        }
    }

    // ------------------------------------------------------------------ spans

    /** Our spans; the original text is kept so the digital layout can be given back untouched. */
    abstract static class OwnSpan extends ReplacementSpan {
        final CharSequence original;

        OwnSpan(CharSequence original) {
            this.original = original;
        }
    }

    static boolean hasOwn(CharSequence text) {
        return text instanceof Spanned && ((Spanned) text).getSpans(0, text.length(), OwnSpan.class).length > 0;
    }

    /** Split AOD clocks may update only the minute view; refresh the hour view's dial in the same callback. */
    static void trackedTextChanged(TextView view) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main().post(() -> trackedTextChanged(view));
            return;
        }
        if (!analog || WRITING.get()) return;
        Role role = TRACKED.get(view);
        if (role == null) return;
        apply(view, role, false);
        TextView partner = role.partner.get();
        Role other = partner != null ? TRACKED.get(partner) : null;
        if (!role.primary && other != null && other.primary) apply(partner, other, true);
    }

    /** Replaces the view's text layout by the dial (hour view) or by nothing (minute view). */
    static void apply(TextView view, Role role, boolean force) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main().post(() -> apply(view, role, force));
            return;
        }
        CharSequence current = view.getText();
        if (current == null || current.length() == 0) return;
        CharSequence original = originalSource(current);
        boolean aod = role.aod || inAod(view);
        if (!force && current instanceof Spanned) {
            OwnSpan[] old = ((Spanned) current).getSpans(0, current.length(), OwnSpan.class);
            if (old.length == 1 && old[0].original == original
                    && (role.primary ? old[0] instanceof DialSpan && ((DialSpan) old[0]).aod == aod
                    : old[0] instanceof BlankSpan)) return;
        }
        // A line break would split the span into two paragraphs: the same number of characters, on one line.
        SpannableString text = new SpannableString(original.toString().replace('\n', ' ').replace('\r', ' '));
        copySpans(original, text);
        OwnSpan span;
        if (role.primary) {
            TextView partner = role.partner.get();
            int[] time = parseTime(original, partner != null ? originalText(partner.getText()) : null);
            int full = dialSize(view, aod);
            Bitmap[] big = BigClock.digitsFor(role.component.get());
            // the ring and the digit height are found once here: drawing them again must not measure or search
            DialSpan dial = new DialSpan(original, full, time[0], time[1], aod, big, view.getPaint(), scale);
            DialSpan[] old = current instanceof Spanned
                    ? ((Spanned) current).getSpans(0, current.length(), DialSpan.class) : new DialSpan[0];
            if (old.length > 0) dial.layer = old[0].layer;   // one hands bitmap per view, not a new one every minute
            span = dial;
        } else {
            span = new BlankSpan(original);
        }
        text.setSpan(span, 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        setText(view, text);
        if (span instanceof DialSpan && !((DialSpan) span).aod && hands) TICKING.add(view);
        else TICKING.remove(view);
    }

    /** Gives the plain digital text back (other spans kept). */
    static void strip(TextView view) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main().post(() -> strip(view));
            return;
        }
        CharSequence current = view.getText();
        if (!hasOwn(current)) return;
        setText(view, originalSource(current));
        TICKING.remove(view);
    }

    private static void setText(TextView view, CharSequence text) {
        boolean writing = WRITING.get();
        WRITING.set(true);
        try { view.setText(text, TextView.BufferType.SPANNABLE); }
        finally { WRITING.set(writing); }
    }

    private static String originalText(CharSequence text) {
        return originalSource(text).toString();
    }

    private static CharSequence originalSource(CharSequence text) {
        if (text == null) return "";
        if (text instanceof Spanned) {
            OwnSpan[] own = ((Spanned) text).getSpans(0, text.length(), OwnSpan.class);
            if (own.length == 1 && sameDisplayText(own[0].original, text)) return own[0].original;
            SpannableString copy = new SpannableString(text.toString());
            Spanned source = (Spanned) text;
            for (Object span : source.getSpans(0, source.length(), Object.class)) {
                if (span instanceof OwnSpan || span instanceof NoCopySpan || span instanceof SpanWatcher) continue;
                int start = source.getSpanStart(span), end = source.getSpanEnd(span);
                if (start >= 0 && end >= start && end <= copy.length()) {
                    copy.setSpan(span, start, end, source.getSpanFlags(span));
                }
            }
            return new SpannedString(copy);
        }
        return text.toString();
    }

    private static boolean sameDisplayText(CharSequence original, CharSequence current) {
        if (original.length() != current.length()) return false;
        for (int i = 0; i < original.length(); i++) {
            char c = original.charAt(i);
            if (c == '\n' || c == '\r') c = ' ';
            if (c != current.charAt(i)) return false;
        }
        return true;
    }

    private static void copySpans(CharSequence from, Spannable to) {
        if (!(from instanceof Spanned)) return;
        Spanned s = (Spanned) from;
        int length = s.length();
        for (Object span : s.getSpans(0, length, Object.class)) {
            if (span instanceof ReplacementSpan) continue;   // ours, Big Clock digit images
            // Do not copy TextView's internal change watchers to a detached buffer.
            if (span instanceof NoCopySpan || span instanceof SpanWatcher) continue;
            int start = s.getSpanStart(span), end = s.getSpanEnd(span);
            if (start < 0 || end > to.length()) continue;
            // Partial character styles split TextLine runs and can draw the replacement more than once.
            // They stay in OwnSpan.original and are restored when returning to digital.
            if (span instanceof CharacterStyle && (start > 0 || end < length)) continue;
            try { to.setSpan(span, start, end, s.getSpanFlags(span)); }
            catch (RuntimeException ignored) { /* paragraph boundaries may have been flattened */ }
        }
    }

    static boolean inAod(View v) {
        for (ViewParent p = v.getParent(); p != null; p = p.getParent()) {
            if (p.getClass().getName().toLowerCase(Locale.ROOT).contains("aod")) return true;
        }
        return false;
    }

    /** Maximum dial diameter: a share of the short screen side, limited to the clock's available space. */
    static int dialSize(TextView view, boolean aod) {
        DisplayMetrics dm = view.getResources().getDisplayMetrics();
        int shortSide = Math.min(dm.widthPixels, dm.heightPixels);
        float d = shortSide * (aod ? AOD_DIAL : DIAL);
        int available = availableWidth(view);
        if (available > 0) d = Math.min(d, available);
        return Math.max(1, Math.round(d));
    }

    /** Width of the nearest laid out ancestor (or the view) that does not just wrap its content, -1 if unknown. */
    private static int availableWidth(TextView view) {
        int inner = view.getCompoundPaddingLeft() + view.getCompoundPaddingRight();
        View cur = view;
        while (cur != null) {
            ViewGroup.LayoutParams lp = cur.getLayoutParams();
            if (cur.getWidth() > 0 && lp != null && lp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                int padding = cur == view ? inner : cur.getPaddingLeft() + cur.getPaddingRight() + inner;
                return Math.max(1, cur.getWidth() - padding);
            }
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) lp;
                inner += margins.leftMargin + margins.rightMargin;
            }
            if (cur != view) inner += cur.getPaddingLeft() + cur.getPaddingRight();
            ViewParent p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        return -1;
    }

    static String digits(CharSequence s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(4);
        for (int i = 0; i < s.length(); i++) {
            int d = Character.digit(s.charAt(i), 10);
            if (d >= 0) b.append((char) ('0' + d));
        }
        return b.toString();
    }

    /** Hour and minute shown by the clock text ("10:08", or "10" + "08"); the current time when unreadable. */
    static int[] parseTime(CharSequence primary, CharSequence partner) {
        int hour = -1, minute = -1;
        String a = digits(primary), b = digits(partner);
        try {
            if (a.length() >= 3 && a.length() <= 4) {
                hour = Integer.parseInt(a.substring(0, a.length() - 2));
                minute = Integer.parseInt(a.substring(a.length() - 2));
            } else if (a.length() >= 1 && a.length() <= 2) {
                hour = Integer.parseInt(a);
                if (b.length() >= 1 && b.length() <= 2) minute = Integer.parseInt(b);
            }
        } catch (NumberFormatException ignored) {
            // keep the current time
        }
        if (hour < 0 || hour > 24 || minute < 0 || minute > 59) {
            Calendar now = Calendar.getInstance();
            if (hour < 0 || hour > 24) hour = now.get(Calendar.HOUR_OF_DAY);
            if (minute < 0 || minute > 59) minute = now.get(Calendar.MINUTE);
        }
        return new int[]{hour, minute};
    }

    /**
     * The room the font of {@code paint} leaves around its digits: {up to the ascent above them, up to the top above
     * them, down to the descent below them, down to the bottom below them}. A digital clock has this room between its
     * digits and the date; the dial keeps the same room, so the date stays as far from it as from the digits.
     */
    static int[] textRoom(Paint paint) {
        Paint.FontMetricsInt f = paint.getFontMetricsInt();
        Rect r = new Rect();
        paint.getTextBounds("0123456789", 0, 10, r);
        boolean ink = r.height() > 0;
        int above = ink ? -r.top : Math.round(paint.getTextSize() * 0.72f);
        int below = ink ? Math.max(0, r.bottom) : 0;
        return new int[]{
                Math.max(0, -f.ascent - above), Math.max(0, -f.top - above),
                Math.max(0, f.descent - below), Math.max(0, f.bottom - below)};
    }

    /** The whole dial, drawn in place of the clock text with the TextView's own paint. */
    static final class DialSpan extends OwnSpan {
        /** size: the room the span takes (the ring of the numbers); digit: the height of the numbers on it. */
        final int size, dial;
        final float ring, percent;
        float digit;
        final int hour, minute;
        final boolean aod;
        private final Bitmap[] big;
        private final Paint measured = new Paint(), drawing = new Paint();
        private final Rect bounds = new Rect();
        private int[] room;
        private boolean logged;
        /** The hands bitmap of this view; handed on to the next span of the same view. */
        HandLayer layer = new HandLayer();

        DialSpan(CharSequence original, int dial, int hour, int minute, boolean aod, Bitmap[] big, Paint paint,
                float percent) {
            super(original);
            this.dial = dial;
            this.percent = Math.max(MIN_SCALE, Math.min(MAX_SCALE, percent));
            this.hour = hour;
            this.minute = minute;
            this.aod = aod;
            this.big = big;
            this.ring = ringSize(dial, this.percent);
            this.size = Math.max(1, (int) Math.ceil(ring));
            prepare(paint);
        }

        private void prepare(Paint paint) {
            if (room != null && measured.equalsForTextMeasurement(paint)) return;
            measured.set(paint);
            digit = digitHeight(dial, percent, widths(paint, big));
            room = textRoom(paint);
        }

        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            try {
                prepare(paint);
                // The room the clock font leaves above and below its digits stays around the dial too, so the date
                // keeps the distance it has to the digital clock instead of sticking to the dial.
                if (fm != null) {
                    fm.ascent = -size - room[0];
                    fm.top = -size - room[1];
                    fm.descent = room[2];
                    fm.bottom = room[3];
                    fm.leading = 0;
                }
            } catch (Throwable t) {
                logOnce("dial metrics", t);
                if (fm != null) {
                    fm.ascent = -size;
                    fm.top = -size;
                    fm.descent = 0;
                    fm.bottom = 0;
                    fm.leading = 0;
                }
            }
            return size;
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                int top, int y, int bottom, Paint paint) {
            try {
                prepare(paint);
                drawDial(canvas, paint, x + size / 2f, y - size / 2f, digit, ring, hour, minute, hands, aod, big,
                        layer, drawing, bounds);
                if (hands && !aod) kickTicker();
            } catch (Throwable t) {
                logOnce("dial draw", t);
            }
        }

        private void logOnce(String message, Throwable error) {
            if (logged) return;
            logged = true;
            HookEntry.log(message, error);
        }
    }

    /** The minute view of a stacked style: takes no room and draws nothing. */
    static final class BlankSpan extends OwnSpan {
        BlankSpan(CharSequence original) {
            super(original);
        }

        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) {
                fm.top = fm.ascent = 0;
                fm.bottom = fm.descent = 0;
                fm.leading = 0;
            }
            return 0;
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                int top, int y, int bottom, Paint paint) {
            // nothing: the hour view shows the whole dial
        }
    }

    // ------------------------------------------------------------------ drawing

    /**
     * The four numbers and the hands for a dial whose numbers are {@code digit} tall on a ring of {@code ring}
     * diameter. Every size is a scaled copy of the tightly packed maximum-size dial; the hands and the gaps follow
     * the same scale. Geometry is cached until text metrics change, including full-range spans and weight animations.
     */
    static void drawDial(Canvas canvas, Paint source, float cx, float cy, float digit, float ring,
            int hour, int minute, boolean withHands, boolean aod, Bitmap[] big, HandLayer layer) {
        drawDial(canvas, source, cx, cy, digit, ring, hour, minute, withHands, aod, big, layer, new Paint(), new Rect());
    }

    static void drawDial(Canvas canvas, Paint source, float cx, float cy, float d, int hour, int minute,
            boolean withHands, boolean aod, Bitmap[] big, float percent, HandLayer layer) {
        float[] k = widths(source, big);
        drawDial(canvas, source, cx, cy, digitHeight(d, percent, k), ringSize(d, percent), hour, minute,
                withHands, aod, big, layer);
    }

    private static void drawDial(Canvas canvas, Paint source, float cx, float cy, float digit, float ring,
            int hour, int minute, boolean withHands, boolean aod, Bitmap[] big, HandLayer layer, Paint p, Rect r) {
        p.set(source);
        p.setTextAlign(Paint.Align.LEFT);
        float base = source.getTextSize() > 0 ? source.getTextSize() : 100f;
        p.setTextSize(base);
        float ink = inkHeight(p, r);
        p.setTextSize(base * digit / ink);

        float half = ring / 2f;
        label(canvas, p, "12", cx, cy - half + digit / 2f, digit, big, r);
        label(canvas, p, "6", cx, cy + half - digit / 2f, digit, big, r);
        float w3 = labelWidth(p, "3", digit, big, r), w9 = labelWidth(p, "9", digit, big, r);
        label(canvas, p, "3", cx + half - w3 / 2f, cy, digit, big, r);
        label(canvas, p, "9", cx - half + w9 / 2f, cy, digit, big, r);

        if (!withHands) return;
        int second = aod ? -1 : (int) ((System.currentTimeMillis() / 1000) % 60);
        drawHands(canvas, source, cx, cy, ring, hour, minute, second, aod, layer);
    }

    /**
     * Fit the digits tightly into the scaled ring. The same packing rule applies at every size, including wide
     * fonts: changing the slider scales the entire maximum-size layout instead of opening gaps between its digits.
     */
    static float digitHeight(float d, float percent, float[] k) {
        return largestDigit(ringSize(d, percent), k);
    }

    /** 150% keeps the full dial; all smaller settings scale that same compact layout linearly. */
    static float ringSize(float d, float percent) {
        return Math.max(1f, d * Math.max(MIN_SCALE, Math.min(MAX_SCALE, percent)) / MAX_SCALE);
    }

    /** The room an analog clock takes for a dial of diameter d at the size setting. */
    static int faceSize(int d, float percent) {
        return Math.max(1, (int) Math.ceil(ringSize(d, percent)));
    }

    /** Width of each number (12, 3, 6, 9) per unit of digit height in the font of {@code source}, or of the images. */
    static float[] widths(Paint source, Bitmap[] big) {
        Paint p = new Paint(source);
        p.setTextAlign(Paint.Align.LEFT);
        Rect r = new Rect();
        float base = source.getTextSize() > 0 ? source.getTextSize() : 100f;
        p.setTextSize(base);
        return widths(p, big, r, inkHeight(p, r));
    }

    private static float inkHeight(Paint paint, Rect bounds) {
        int height = 0;
        for (String label : LABELS) {
            paint.getTextBounds(label, 0, label.length(), bounds);
            height = Math.max(height, bounds.height());
        }
        return height > 0 ? height : paint.getTextSize() * 0.72f;
    }

    private static float[] widths(Paint p, Bitmap[] big, Rect r, float ink) {
        float[] k = new float[LABELS.length];
        for (int i = 0; i < LABELS.length; i++) {
            if (big != null) {
                k[i] = labelWidth(p, LABELS[i], 1f, big, r);
            } else {
                p.getTextBounds(LABELS[i], 0, LABELS[i].length(), r);
                k[i] = r.width() / ink;
            }
        }
        return k;
    }

    /** The biggest digit height at which neighbouring numbers still keep {@link #TOUCH_GAP} of the dial apart. */
    static float largestDigit(float d, float[] k) {
        float gap = d * TOUCH_GAP;
        float lo = 0f, hi = (d - gap) / 2f;
        // Neighbour spacing alone does not constrain a very wide 12 or the two opposite side labels.
        for (float width : k) if (width > 0f) hi = Math.min(hi, d / width);
        if (k[1] + k[3] > 0f) hi = Math.min(hi, (d - gap) / (k[1] + k[3]));
        for (int i = 0; i < 30; i++) {
            float mid = (lo + hi) / 2f;
            if (spacing(d, mid, k) >= gap) lo = mid; else hi = mid;
        }
        return lo;
    }

    /** The least distance between neighbouring numbers (12-3, 3-6, 6-9, 9-12) at digit height h; below 0: overlap. */
    static float spacing(float d, float h, float[] k) {
        float half = d / 2f;
        float dx = half - (Math.max(k[0], k[2]) / 2f + Math.max(k[1], k[3])) * h;
        float dy = half - 1.5f * h;
        return dx > 0f && dy > 0f ? (float) Math.hypot(dx, dy) : Math.max(dx, dy);
    }

    /**
     * The hands are painted into software bitmaps of their own and put on the clock as pictures: the shape of the
     * parts in the clock colour (alpha only) and, over it, the black outline and the second hand.
     */
    static final class HandLayer {
        Bitmap bitmap, mask;
        Canvas canvas, maskCanvas;
        final Paint tint = new Paint(), overlay = new Paint(Paint.FILTER_BITMAP_FLAG);
        /** What the bitmaps were painted for: a redraw that changes none of it reuses them instead of repainting. */
        float scale, px, py;
        int alpha, hour, minute, second;
        boolean aod, painted;
    }

    static void drawHands(Canvas canvas, Paint source, float cx, float cy, float d, int hour, int minute, int second,
            boolean aod) {
        drawHands(canvas, source, cx, cy, d, hour, minute, second, aod, null);
    }

    /**
     * The hands of the drawing: a black outline (with a soft shadow) around an inside in the clock colour, and a red
     * second hand. On the always-on display the outline is the dimmed clock colour and there is no second hand.
     * The shapes are painted with fresh paints into bitmaps of their own, so neither the text effects of the clock
     * paint nor the system's renderer touch them. The parts in the clock colour are only a mask there: it is put on
     * the clock with the clock paint itself, so whatever colours the numbers (colour, alpha, gradient, filter,
     * blending) colours them the same way, also where a copy of single paint properties painted in software would
     * come out black or empty. The outline has a hole over the inside, so the inside never sits on black. The
     * bitmaps are painted only when the hands, their size, the colour alpha or the display change: a redraw with
     * everything as it was (an animation over the clock, another frame of a transition) just draws them again.
     */
    static void drawHands(Canvas canvas, Paint source, float cx, float cy, float d, int hour, int minute, int second,
            boolean aod, HandLayer layer) {
        float s = d * MINUTE_HAND / HAND_UNITS;
        int n = (int) Math.ceil(2f * ((HAND_UNITS + SHADOW_DY + 4f * SHADOW_BLUR) * s + 2f));
        if (n < 2 || n > 4096) return;
        if (layer == null) layer = new HandLayer();
        Bitmap b = layer.bitmap, m = layer.mask;
        boolean sized = b != null && !b.isRecycled() && b.getWidth() == n && b.getHeight() == n && layer.canvas != null
                && m != null && !m.isRecycled() && m.getWidth() == n && m.getHeight() == n && layer.maskCanvas != null;
        float left = (float) Math.floor(cx - n / 2f), top = (float) Math.floor(cy - n / 2f);
        float px = cx - left, py = cy - top;
        int alpha = source.getAlpha();
        boolean painted = sized && layer.painted && layer.scale == s && layer.px == px && layer.py == py && layer.alpha == alpha
                && layer.hour == hour && layer.minute == minute && layer.second == second && layer.aod == aod;
        if (!painted) {
            layer.painted = false;
            if (!sized) {
                b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
                m = Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8);
                layer.bitmap = b;
                layer.canvas = new Canvas(b);
                layer.mask = m;
                layer.maskCanvas = new Canvas(m);
            } else {
                b.eraseColor(Color.TRANSPARENT);
                m.eraseColor(Color.TRANSPARENT);
            }
            paintHands(layer.canvas, layer.maskCanvas, alpha, px, py, s, hour, minute, second, aod);
            layer.scale = s;
            layer.px = px;
            layer.py = py;
            layer.alpha = alpha;
            layer.hour = hour;
            layer.minute = minute;
            layer.second = second;
            layer.aod = aod;
            layer.painted = true;
        }
        Paint tint = layer.tint;
        tint.set(source);
        tint.setStyle(Paint.Style.FILL);
        tint.clearShadowLayer();
        tint.setMaskFilter(null);
        tint.setPathEffect(null);
        tint.setFilterBitmap(true);
        canvas.drawBitmap(m, left, top, tint);
        if (!aod) canvas.drawBitmap(b, left, top, layer.overlay);
    }

    private static void paintHands(Canvas canvas, Canvas mask, int alpha, float cx, float cy, float s, int hour,
            int minute, int second, boolean aod) {
        Paint inside = new Paint(Paint.ANTI_ALIAS_FLAG);
        inside.setStyle(Paint.Style.FILL);
        inside.setColor(Color.BLACK);
        float hourAngle = ((hour % 12) + minute / 60f) * 30f, minuteAngle = minute * 6f;
        if (aod) {
            // Outline and inside are both the clock colour here: one mask, the outline dimmed in it.
            Paint outline = new Paint(inside);
            outline.setAlpha(Math.round(255 * AOD_OUTLINE));
            hand(mask, mask, cx, cy, s, hourAngle, HOUR_BODY, HOUR_INNER, outline, null, inside, 0f);
            hand(mask, mask, cx, cy, s, minuteAngle, MINUTE_BODY, MINUTE_INNER, outline, null, inside, 0f);
            return;
        }
        Paint outline = new Paint(inside);
        outline.setAlpha(alpha);
        shadow(outline, s, alpha);
        Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
        hole.setStyle(Paint.Style.FILL);
        hole.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        // The mask reaches a pixel under the outline, so no seam shows where the edges of hole and inside meet.
        float grow = Math.min(1f / s, 6f);
        hand(canvas, mask, cx, cy, s, hourAngle, HOUR_BODY, HOUR_INNER, outline, hole, inside, grow);
        hand(canvas, mask, cx, cy, s, minuteAngle, MINUTE_BODY, MINUTE_INNER, outline, hole, inside, grow);
        if (second < 0) return;
        Paint red = new Paint(Paint.ANTI_ALIAS_FLAG);
        red.setStyle(Paint.Style.FILL);
        red.setColor(SECOND_COLOR);
        red.setAlpha(alpha);
        shadow(red, s, alpha);
        Path p = rounded(SECOND_BAR);
        p.op(rounded(SECOND_TAIL), Path.Op.UNION);
        Path ring = new Path();
        ring.addCircle(0f, 0f, RING, Path.Direction.CW);
        p.op(ring, Path.Op.UNION);
        Path circle = new Path();
        circle.addCircle(0f, 0f, RING_HOLE, Path.Direction.CW);
        p.op(circle, Path.Op.DIFFERENCE);
        canvas.drawPath(moved(p, cx, cy, s, second * 6f), red);
    }

    /**
     * One hand: the outline (body and the stem over the pivot, as one shape), a hole in it over the inside (if any)
     * and the inside, {@code grow} drawing units bigger, in the mask of the clock colour.
     */
    private static void hand(Canvas canvas, Canvas mask, float cx, float cy, float s, float degrees, float[] body,
            float[] inner, Paint outline, Paint hole, Paint inside, float grow) {
        Path o = rounded(body);
        o.op(rounded(STEM), Path.Op.UNION);
        canvas.drawPath(moved(o, cx, cy, s, degrees), outline);
        if (hole != null) canvas.drawPath(moved(rounded(inner), cx, cy, s, degrees), hole);
        float[] g = {inner[0] - grow, inner[1] - grow, inner[2] + grow, inner[3] + grow, inner[4] + grow};
        mask.drawPath(moved(rounded(g), cx, cy, s, degrees), inside);
    }

    /** left, top, right, bottom and (optional) corner radius in drawing units. */
    private static Path rounded(float[] r) {
        Path p = new Path();
        float radius = r.length > 4 ? r[4] : 0f;
        p.addRoundRect(r[0], r[1], r[2], r[3], radius, radius, Path.Direction.CW);
        return p;
    }

    /** Drawing units to the canvas: scaled, turned clockwise around the pivot, moved to the dial centre. */
    private static Path moved(Path p, float cx, float cy, float s, float degrees) {
        Matrix m = new Matrix();
        m.setScale(s, s);
        m.postRotate(degrees);
        m.postTranslate(cx, cy);
        p.transform(m);
        return p;
    }

    private static void shadow(Paint p, float s, int alpha) {
        p.setShadowLayer(Math.max(0.5f, SHADOW_BLUR * s), 0f, SHADOW_DY * s,
                Color.argb(Math.round(alpha * 0.25f), 0, 0, 0));
    }

    private static float labelWidth(Paint p, String s, float digit, Bitmap[] big, Rect r) {
        if (big != null) {
            float w = 0;
            for (int i = 0; i < s.length(); i++) w += imageWidth(big[s.charAt(i) - '0'], digit);
            return w;
        }
        p.getTextBounds(s, 0, s.length(), r);
        return r.width();
    }

    private static float imageWidth(Bitmap b, float height) {
        return b == null || b.getHeight() == 0 ? height * 0.56f / 0.72f : height * b.getWidth() / b.getHeight();
    }

    /** One number, centred on (x, y) by its real ink bounds. */
    private static void label(Canvas canvas, Paint p, String s, float x, float y, float digit, Bitmap[] big, Rect r) {
        if (big != null) {
            Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            bp.setColorFilter(new PorterDuffColorFilter(p.getColor() | 0xff000000, PorterDuff.Mode.SRC_IN));
            bp.setAlpha(p.getAlpha());
            float left = x - labelWidth(p, s, digit, big, r) / 2f, top = y - digit / 2f;
            for (int i = 0; i < s.length(); i++) {
                Bitmap b = big[s.charAt(i) - '0'];
                float w = imageWidth(b, digit);
                if (b != null) canvas.drawBitmap(b, null, new RectF(left, top, left + w, top + digit), bp);
                left += w;
            }
            return;
        }
        p.getTextBounds(s, 0, s.length(), r);
        canvas.drawText(s, x - r.exactCenterX(), y - r.exactCenterY(), p);
    }
}

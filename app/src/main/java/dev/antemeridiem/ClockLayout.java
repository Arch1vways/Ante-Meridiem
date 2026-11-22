package dev.antemeridiem;

import android.appwidget.AppWidgetHostView;
import android.content.res.Resources;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.Spanned;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hide the plugin's date or give it the clock's font. Digital clock geometry belongs entirely to vivo.
 *
 * <p>All view state is main-thread-only. A layout/attachment change rebuilds the date bindings; pre-draw only checks
 * those bindings. Each window has one listener and each date retains its own clock source, including when the lock
 * screen and AOD share a window. Weak bindings and removable listeners let discarded plugin views be collected.
 */
final class ClockLayout {
    static final String KEY_DATE_HIDDEN = "date_hidden", KEY_DATE_FONT = "date_font",
            KEY_DIGITAL_SCALE = "digital_scale";
    static final int MIN_DIGITAL_SCALE = 50, MAX_DIGITAL_SCALE = 150, DEFAULT_DIGITAL_SCALE = 100;
    private static final String VIVO = "com.vivo.";
    private static final float WIDGET_GAP_DP = 8f, ANALOG_WIDGET_GAP_DP = 5f,
            PUSH_TOLERANCE_DP = 4f, PUSH_MAX_TOP = 0.8f, PUSH_MAX_BOTTOM = 0.92f;
    static volatile boolean dateHidden, dateFont;
    static volatile int digitalScale = DEFAULT_DIGITAL_SCALE;
    private static boolean lastAnalogMode;

    private static final Set<Object> COMPONENTS = weakSet();
    private static final Set<View> AOD_ROOTS = weakSet(), LISTENING = weakSet(), HIDDEN = weakSet();
    private static final Map<View, Watcher> WATCHERS = new WeakHashMap<>();
    private static final Map<TextView, FontState> FONTS = new WeakHashMap<>();
    private static final Map<TextView, SizeState> CLOCK_SIZES = new WeakHashMap<>();
    private static final Map<View, int[]> PUSHED = new WeakHashMap<>();
    // Field objects refer back to their declaring class; weak keys alone would still retain the class loader.
    private static final Map<Class<?>, WeakReference<Fields>> FIELDS = new WeakHashMap<>();
    private static final Map<Resources, Map<Integer, String>> NAMES = new WeakHashMap<>();
    private static final AtomicBoolean POSTED = new AtomicBoolean();
    private static boolean applying;

    private static final Runnable APPLY = () -> {
        POSTED.set(false);
        applyAll();
    };
    private static final View.OnAttachStateChangeListener ATTACH = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View v) {
            if (onMain()) applyAll();
            else schedule();
        }

        @Override public void onViewDetachedFromWindow(View v) {
            Watcher old = WATCHERS.remove(v);
            if (old != null) old.remove();
            schedule();
        }
    };

    private ClockLayout() { }

    // ------------------------------------------------------------------ settings and triggers

    static int clampScale(int percent) {
        return Math.max(MIN_DIGITAL_SCALE, Math.min(MAX_DIGITAL_SCALE, percent));
    }

    static void update(boolean hideDate, boolean clockFontOnDate, int scalePercent) {
        if (!onMain()) {
            main().post(() -> update(hideDate, clockFontOnDate, scalePercent));
            return;
        }
        int clamped = clampScale(scalePercent);
        if (hideDate == dateHidden && clockFontOnDate == dateFont && clamped == digitalScale) return;
        dateHidden = hideDate;
        dateFont = clockFontOnDate;
        digitalScale = clamped;
        applyAll();
    }

    static void component(Object component) {
        if (component == null) return;
        if (!onMain()) {
            main().post(() -> component(component));
            return;
        }
        COMPONENTS.add(component);
        if (dateHidden || dateFont) schedule();
    }

    static void aodRoot(View root) {
        if (root == null) return;
        if (!onMain()) {
            main().post(() -> aodRoot(root));
            return;
        }
        AOD_ROOTS.add(root);
        if (dateHidden || dateFont) schedule();
    }

    static void schedule() {
        if (POSTED.compareAndSet(false, true)) main().post(APPLY);
    }

    static void applyNow() {
        applyAll();
    }

    static void applyAll() {
        if (!onMain()) {
            schedule();
            return;
        }
        if (applying) return;
        if (POSTED.getAndSet(false)) main().removeCallbacks(APPLY);
        applying = true;
        try {
            boolean scaleActive = !AnalogClock.analog && digitalScale != DEFAULT_DIGITAL_SCALE;
            boolean widgetAdapt = AnalogClock.analog || digitalScale != DEFAULT_DIGITAL_SCALE;
            if (!dateHidden && !dateFont && !widgetAdapt) {
                restoreUnused(Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
                releaseAllPushed();
                clear();
                return;
            }
            Map<View, Frame> frames = new IdentityHashMap<>();
            for (Object c : new ArrayList<>(COMPONENTS)) {
                try {
                    Fields f = fields(c.getClass());
                    List<TextView> clocks = new ArrayList<>(), primary = new ArrayList<>();
                    for (int i = 0; i < f.clocks.size(); i++) {
                        Object v = f.clocks.get(i).get(c);
                        if (!(v instanceof TextView) || clocks.contains(v)) continue;
                        clocks.add((TextView) v);
                        if (f.primary.get(i)) primary.add((TextView) v);
                    }
                    List<View> dates = new ArrayList<>();
                    for (Field field : f.dates) {
                        Object v = field.get(c);
                        if (v instanceof View) dates.add((View) v);
                    }
                    layout(clocks, primary, dates, null, frames);
                } catch (Throwable t) {
                    HookEntry.log("date component", t);
                }
            }
            for (View root : new ArrayList<>(AOD_ROOTS)) {
                try {
                    listen(root);
                    if (!root.isAttachedToWindow()) continue;
                    List<TextView> clocks = AnalogClock.aodTimes(root);
                    layout(clocks, clocks, Collections.emptyList(), root, frames);
                } catch (Throwable t) {
                    HookEntry.log("date AOD", t);
                }
            }
            Set<View> hidden = identitySet();
            Set<TextView> fonts = identitySet();
            Set<TextView> scaledClocks = identitySet();
            for (Frame frame : frames.values()) {
                for (Binding b : frame.dates) {
                    View date = b.date.get();
                    if (dateHidden && date != null) hidden.add(date);
                    TextView clock = b.clock.get();
                    if (dateFont && clock != null && clock.getTypeface() != null) {
                        for (WeakReference<TextView> ref : b.texts) {
                            TextView text = ref.get();
                            if (text != null && text != clock) fonts.add(text);
                        }
                    }
                }
                if (scaleActive) {
                    for (WeakReference<TextView> ref : frame.clocks) {
                        TextView clock = ref.get();
                        if (clock != null) scaledClocks.add(clock);
                    }
                }
            }
            restoreUnused(hidden, fonts, scaledClocks);
            if (!AnalogClock.analog && digitalScale == DEFAULT_DIGITAL_SCALE) releaseAllPushed();
            for (View root : new ArrayList<>(WATCHERS.keySet())) {
                if (!frames.containsKey(root) || !root.isAttachedToWindow()) {
                    Watcher old = WATCHERS.remove(root);
                    if (old != null) old.remove();
                }
            }
            for (Map.Entry<View, Frame> entry : frames.entrySet()) {
                guard(entry.getKey(), entry.getValue());
                enforce(entry.getValue());
            }
        } finally {
            applying = false;
        }
    }

    // ------------------------------------------------------------------ discovery

    private static void layout(List<TextView> clocks, List<TextView> primary, List<View> knownDates, View aodScope,
            Map<View, Frame> frames) {
        for (View v : clocks) listen(v);
        for (View v : knownDates) listen(v);
        TextView first = firstAttached(primary);
        if (first == null) first = firstAttached(clocks);
        View anchor = first != null ? first : aodScope;
        if (anchor == null) {
            for (View d : knownDates) {
                if (d.isAttachedToWindow()) { anchor = d; break; }
            }
        }
        // The attach listener, rather than a permanent timer, handles a not-yet-attached component.
        if (anchor == null || !anchor.isAttachedToWindow()) return;
        View root = anchor.getRootView();
        Set<View> skip = identitySet(), holders = identitySet(), seen = identitySet();
        for (TextView clock : clocks) {
            skip.add(clock);
            for (ViewParent p = clock.getParent(); p instanceof View; p = p.getParent()) holders.add((View) p);
        }
        List<View> dates = new ArrayList<>();
        View scope = first != null ? first : anchor;
        for (View d : knownDates) {
            if (d.getRootView() != root || skip.contains(d) || holders.contains(d) || !seen.add(d)) continue;
            dates.add(d);
            scope = commonAncestor(scope, d);
        }
        if (aodScope != null) scope = aodScope;
        else if (first != null && (dates.isEmpty() || scope == root)) scope = pluginTop(first, root);
        scan(scope, skip, holders, dates, seen, 0);
        Frame frame = frames.get(root);
        if (frame == null) {
            frame = new Frame(root);
            frames.put(root, frame);
        }
        if (aodScope == null) {
            for (TextView clock : clocks) frame.addClock(clock, primary.contains(clock));
            for (View d : dates) frame.addKnownDate(d);
        }
        for (View d : dates) frame.add(d, first);
    }

    private static TextView firstAttached(List<TextView> views) {
        TextView fallback = null;
        for (TextView v : views) {
            if (!v.isAttachedToWindow()) continue;
            if (v.isShown()) return v;
            if (fallback == null) fallback = v;
        }
        return fallback;
    }

    private static View commonAncestor(View a, View b) {
        Set<View> parents = identitySet();
        for (View v = a; v != null; v = parent(v)) parents.add(v);
        for (View v = b; v != null; v = parent(v)) if (parents.contains(v)) return v;
        return a;
    }

    private static View parent(View view) {
        ViewParent p = view.getParent();
        return p instanceof View ? (View) p : null;
    }

    /** Prefer the nearest clock container; stop at the host SystemUI hierarchy. */
    private static View pluginTop(View clock, View root) {
        View best = parent(clock);
        if (best == null) return clock;
        for (View v = best; v != null && v != root; v = parent(v)) {
            try {
                if (v.getId() != View.NO_ID
                        && v.getResources().getResourcePackageName(v.getId()).equals(HookEntry.SYSTEMUI)) break;
            } catch (Resources.NotFoundException ignored) { }
            String id = vivoId(v);
            if (id != null) {
                List<String> w = words(id);
                if (w.contains("time") || w.contains("clock")) return v;
            }
            if (v.getResources() != clock.getResources() && !v.getClass().getName().startsWith(VIVO)) break;
            best = v;
        }
        return best;
    }

    private static void scan(View v, Set<View> clocks, Set<View> holders, List<View> dates, Set<View> seen, int depth) {
        if (v == null || depth > 48 || clocks.contains(v) || v.getVisibility() == View.GONE) return;
        if (!holders.contains(v)) {
            if (isWidget(v)) return;
            if (isDate(v)) {
                if (seen.add(v)) dates.add(v);
                return;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) scan(group.getChildAt(i), clocks, holders, dates, seen, depth + 1);
        }
    }

    private static void collectTexts(View v, List<WeakReference<TextView>> out, int depth) {
        if (depth > 48 || isWidget(v)) return;
        if (v instanceof TextView) out.add(new WeakReference<>((TextView) v));
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) collectTexts(group.getChildAt(i), out, depth + 1);
        }
    }

    private static int distance(View date, View clock) {
        if (clock == null) return Integer.MAX_VALUE;
        View ancestor = commonAncestor(date, clock);
        int result = 0;
        for (View v = date; v != null && v != ancestor; v = parent(v)) result++;
        for (View v = clock; v != null && v != ancestor; v = parent(v)) result++;
        return result;
    }

    private static final class Binding {
        final WeakReference<View> date;
        WeakReference<TextView> clock;
        final List<WeakReference<TextView>> texts = new ArrayList<>();

        Binding(View date, TextView clock) {
            this.date = new WeakReference<>(date);
            this.clock = new WeakReference<>(clock);
            collectTexts(date, texts, 0);
        }
    }

    private static final class Frame {
        final WeakReference<View> root;
        final List<Binding> dates = new ArrayList<>();
        final List<WeakReference<TextView>> clocks = new ArrayList<>();
        final List<WeakReference<TextView>> primary = new ArrayList<>();
        final List<WeakReference<View>> knownDates = new ArrayList<>();
        final List<WeakReference<View>> widgets = new ArrayList<>();
        boolean widgetsDirty = true;

        Frame(View root) {
            this.root = new WeakReference<>(root);
        }

        void addClock(TextView clock, boolean isPrimary) {
            if (!containsRef(clocks, clock)) clocks.add(new WeakReference<>(clock));
            if (isPrimary && !containsRef(primary, clock)) primary.add(new WeakReference<>(clock));
        }

        void addKnownDate(View date) {
            if (!containsRef(knownDates, date)) knownDates.add(new WeakReference<>(date));
        }

        private static <T extends View> boolean containsRef(List<WeakReference<T>> list, T target) {
            for (WeakReference<T> ref : list) if (ref.get() == target) return true;
            return false;
        }

        void add(View date, TextView clock) {
            for (Binding b : dates) {
                if (b.date.get() != date) continue;
                if (distance(date, clock) < distance(date, b.clock.get())) b.clock = new WeakReference<>(clock);
                return;
            }
            dates.add(new Binding(date, clock));
        }
    }

    // ------------------------------------------------------------------ window lifecycle and pre-draw

    private static void listen(View view) {
        if (LISTENING.add(view)) view.addOnAttachStateChangeListener(ATTACH);
    }

    private static final class Watcher implements ViewTreeObserver.OnPreDrawListener,
            ViewTreeObserver.OnGlobalLayoutListener {
        final WeakReference<View> root;
        final WeakReference<ViewTreeObserver> observer;
        Frame frame;
        boolean dirty;

        Watcher(View root, ViewTreeObserver observer, Frame frame) {
            this.root = new WeakReference<>(root);
            this.observer = new WeakReference<>(observer);
            this.frame = frame;
        }

        @Override public void onGlobalLayout() {
            dirty = true;
            if (frame != null) frame.widgetsDirty = true;
        }

        @Override public boolean onPreDraw() {
            try {
                if (dirty) applyAll();
                else enforce(frame);
            } catch (Throwable t) { HookEntry.log("date guard", t); }
            return true;
        }

        void removeFrom(ViewTreeObserver o) {
            if (o == null || !o.isAlive()) return;
            o.removeOnPreDrawListener(this);
            o.removeOnGlobalLayoutListener(this);
        }

        void remove() {
            removeFrom(observer.get());
            View view = root.get();
            if (view != null) removeFrom(view.getViewTreeObserver());
        }
    }

    private static void guard(View root, Frame frame) {
        ViewTreeObserver observer = root.getViewTreeObserver();
        if (!observer.isAlive()) return;
        Watcher old = WATCHERS.get(root);
        if (old != null && old.observer.get() == observer) {
            old.frame = frame;
            old.dirty = false;
            return;
        }
        if (old != null) old.remove();
        Watcher watcher = new Watcher(root, observer, frame);
        WATCHERS.put(root, watcher);
        listen(root);
        observer.addOnGlobalLayoutListener(watcher);
        observer.addOnPreDrawListener(watcher);
    }

    private static void clear() {
        for (Watcher watcher : new ArrayList<>(WATCHERS.values())) watcher.remove();
        WATCHERS.clear();
        for (View view : new ArrayList<>(LISTENING)) view.removeOnAttachStateChangeListener(ATTACH);
        LISTENING.clear();
    }

    private static void enforce(Frame frame) {
        for (Binding b : frame.dates) {
            try {
                View date = b.date.get();
                if (date == null || !date.isAttachedToWindow()) continue;
                if (dateHidden && date.getVisibility() == View.VISIBLE) {
                    HIDDEN.add(date);
                    date.setVisibility(View.INVISIBLE);
                }
                TextView clock = b.clock.get();
                if (!dateFont || clock == null || !clock.isAttachedToWindow()) continue;
                Typeface want = clock.getTypeface();
                String axes = clock.getFontVariationSettings();
                for (WeakReference<TextView> ref : b.texts) {
                    TextView text = ref.get();
                    if (text != null) font(text, clock, want, axes);
                }
            } catch (Throwable t) { HookEntry.log("date binding", t); }
        }
        boolean analogNow = AnalogClock.analog;
        if (analogNow != lastAnalogMode) {
            lastAnalogMode = analogNow;
            releaseAllPushed();
            frame.widgets.clear();
            frame.widgetsDirty = true;
            for (WeakReference<TextView> ref : frame.clocks) {
                TextView clock = ref.get();
                if (clock != null) restoreClockSize(clock);
            }
        }
        if (analogNow) {
            for (WeakReference<TextView> ref : frame.clocks) {
                TextView clock = ref.get();
                if (clock != null && clock.isAttachedToWindow()) {
                    try { restoreClockSize(clock); } catch (Throwable t) { HookEntry.log("restore clock size", t); }
                }
            }
            try { updateAnalogWidgetPush(frame); } catch (Throwable t) { HookEntry.log("analog widget push", t); }
        } else {
            boolean scaleActive = digitalScale != DEFAULT_DIGITAL_SCALE;
            for (WeakReference<TextView> ref : frame.clocks) {
                TextView clock = ref.get();
                if (clock == null || !clock.isAttachedToWindow()) continue;
                try {
                    if (scaleActive) scaleClock(clock);
                    else restoreClockSize(clock);
                } catch (Throwable t) { HookEntry.log("digital clock scale", t); }
            }
            if (scaleActive) {
                try { updateDigitalWidgetPush(frame); } catch (Throwable t) { HookEntry.log("digital widget push", t); }
            } else {
                releaseFramePushed(frame);
            }
        }
    }

    private static final class SizeState {
        float basePx, appliedPx;

        SizeState(float px) {
            basePx = appliedPx = px;
        }
    }

    private static void scaleClock(TextView clock) {
        CharSequence raw = clock.getText();
        if (raw instanceof Spanned && ((Spanned) raw).getSpans(0, raw.length(), AnalogClock.OwnSpan.class).length > 0) return;
        float cur = clock.getTextSize();
        if (cur <= 0f) return;
        SizeState state = CLOCK_SIZES.get(clock);
        if (state == null) {
            state = new SizeState(cur);
            CLOCK_SIZES.put(clock, state);
        } else if (Math.abs(cur - state.appliedPx) > 0.5f) {
            state.basePx = cur;
        }
        float want = Math.max(1f, state.basePx * (digitalScale / 100f));
        want = fitToWidth(clock, raw, cur, want);
        if (Math.abs(cur - want) > 0.5f) {
            clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, want);
        }
        state.appliedPx = clock.getTextSize();
    }

    private static float fitToWidth(TextView clock, CharSequence raw, float curSize, float wantSize) {
        if (raw == null || raw.length() == 0 || curSize <= 0f) return wantSize;
        android.text.TextPaint paint = clock.getPaint();
        if (paint == null) return wantSize;
        char widest = widestDigit(paint);
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch >= '0' && ch <= '9') sb.append(widest);
            else sb.append(ch);
        }
        float measuredAtCur = paint.measureText(sb.toString());
        if (measuredAtCur <= 0f) return wantSize;
        float measuredAtWant = measuredAtCur * (wantSize / curSize);
        float availW = availableWidth(clock);
        if (measuredAtWant > availW) {
            return Math.max(1f, wantSize * (availW / measuredAtWant));
        }
        return wantSize;
    }

    private static char widestDigit(android.text.TextPaint paint) {
        char best = '8';
        float max = -1f;
        for (char d = '0'; d <= '9'; d++) {
            float w = paint.measureText(String.valueOf(d));
            if (w > max) {
                max = w;
                best = d;
            }
        }
        return best;
    }

    private static float availableWidth(TextView clock) {
        int screenW = clock.getResources().getDisplayMetrics().widthPixels;
        float maxW = screenW * 0.94f;
        ViewGroup.LayoutParams lp = clock.getLayoutParams();
        if (lp != null && lp.width > 0 && clock.getWidth() > 0) {
            float w = clock.getWidth() - clock.getPaddingLeft() - clock.getPaddingRight();
            if (w > 0f) maxW = Math.min(maxW, w);
        }
        ViewParent p = clock.getParent();
        while (p instanceof View) {
            View pv = (View) p;
            int pw = pv.getWidth() - pv.getPaddingLeft() - pv.getPaddingRight();
            if (pw > 0) {
                maxW = Math.min(maxW, pw * 0.96f);
                break;
            }
            p = pv.getParent();
        }
        return Math.max(1f, maxW);
    }

    private static void restoreClockSize(TextView clock) {
        SizeState state = CLOCK_SIZES.remove(clock);
        if (state == null) return;
        if (Math.abs(clock.getTextSize() - state.appliedPx) <= 0.5f && state.basePx > 0f) {
            clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, state.basePx);
        }
    }

    private static View keyguardHost(View anchor, View fallback) {
        for (View v = anchor; v != null && v != fallback; v = parent(v)) {
            if (v.getClass().getName().endsWith(".KeyguardOsView")) return v;
        }
        return fallback;
    }

    private static void updateDigitalWidgetPush(Frame frame) {
        updateWidgetPush(frame, false, WIDGET_GAP_DP);
    }

    private static void updateAnalogWidgetPush(Frame frame) {
        updateWidgetPush(frame, true, ANALOG_WIDGET_GAP_DP);
    }

    private static void updateWidgetPush(Frame frame, boolean analogMode, float gapDp) {
        View root = frame.root.get();
        if (root == null || !root.isAttachedToWindow()) return;
        List<WeakReference<TextView>> sources = frame.primary.isEmpty() ? frame.clocks : frame.primary;
        TextView firstPrimary = null;
        for (WeakReference<TextView> ref : sources) {
            TextView c = ref.get();
            if (c != null && c.isAttachedToWindow() && c.getVisibility() == View.VISIBLE && c.getWidth() > 0) {
                firstPrimary = c;
                break;
            }
        }
        if (firstPrimary == null || firstPrimary.isLayoutRequested()) return;
        for (WeakReference<View> ref : frame.widgets) {
            View w = ref.get();
            if (w != null && w.isAttachedToWindow() && w.getVisibility() == View.VISIBLE && w.isLayoutRequested()) {
                return;
            }
        }
        View host = keyguardHost(firstPrimary, root);
        RectF clockBounds = null;
        RectF tmp = new RectF();
        for (WeakReference<TextView> ref : sources) {
            TextView c = ref.get();
            if (c == null || !c.isAttachedToWindow() || c.getVisibility() != View.VISIBLE || c.getWidth() <= 0) continue;
            CharSequence raw = c.getText();
            boolean hasDial = raw instanceof Spanned
                    && ((Spanned) raw).getSpans(0, raw.length(), AnalogClock.DialSpan.class).length > 0;
            boolean hasOwn = raw instanceof Spanned
                    && ((Spanned) raw).getSpans(0, raw.length(), AnalogClock.OwnSpan.class).length > 0;
            if (analogMode) {
                if (!hasDial) continue;
                tmp.set(0f, 0f, c.getWidth(), c.getHeight());
            } else {
                if (hasOwn) continue;
                textInkBox(c, tmp);
            }
            if (!mapToRoot(host, c, tmp, false)) continue;
            if (clockBounds == null) clockBounds = new RectF(tmp);
            else clockBounds.union(tmp);
        }
        if (clockBounds == null || clockBounds.height() <= 0f) return;
        float density = host.getResources().getDisplayMetrics().density;
        if (frame.widgetsDirty || frame.widgets.isEmpty()) {
            if (host.isLayoutRequested()) return;
            frame.widgetsDirty = false;
            Set<View> skip = identitySet(), holders = identitySet();
            for (WeakReference<TextView> ref : frame.clocks) {
                TextView c = ref.get();
                if (c == null) continue;
                skip.add(c);
                for (ViewParent p = c.getParent(); p instanceof View; p = p.getParent()) holders.add((View) p);
            }
            if (dateHidden) {
                for (WeakReference<View> ref : frame.knownDates) {
                    View d = ref.get();
                    if (d == null) continue;
                    skip.add(d);
                    for (ViewParent p = d.getParent(); p instanceof View; p = p.getParent()) holders.add((View) p);
                }
            }
            List<View> found = new ArrayList<>();
            findBelow(host, host, 0, clockBounds, skip, holders, found, density, tmp);
            List<View> stale = new ArrayList<>();
            for (WeakReference<View> ref : frame.widgets) {
                View w = ref.get();
                if (w != null && !found.contains(w)) stale.add(w);
            }
            for (View w : stale) unpush(w, PUSHED.remove(w));
            for (java.util.Iterator<Map.Entry<View, int[]>> it = PUSHED.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<View, int[]> e = it.next();
                View w = e.getKey();
                if (w != null && !found.contains(w) && isDescendant(host, w)) {
                    unpush(w, e.getValue());
                    it.remove();
                }
            }
            frame.widgets.clear();
            for (View w : found) {
                frame.widgets.add(new WeakReference<>(w));
                if (!PUSHED.containsKey(w) && w.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                    PUSHED.put(w, new int[]{((ViewGroup.MarginLayoutParams) w.getLayoutParams()).topMargin, 0});
                }
            }
        }
        if (frame.widgets.isEmpty()) return;
        float contentBottom = clockBounds.bottom;
        if (!dateHidden) {
            for (WeakReference<View> ref : frame.knownDates) {
                View d = ref.get();
                if (d == null || !d.isAttachedToWindow() || d.getVisibility() != View.VISIBLE) continue;
                if (d instanceof TextView) textInkBox((TextView) d, tmp);
                else tmp.set(0f, 0f, d.getWidth(), d.getHeight());
                if (mapToRoot(host, d, tmp, true) && tmp.bottom > clockBounds.top) {
                    contentBottom = Math.max(contentBottom, tmp.bottom);
                }
            }
        }
        float needed = Float.NEGATIVE_INFINITY, gap = gapDp * density;
        for (WeakReference<View> ref : frame.widgets) {
            View w = ref.get();
            if (w == null || !w.isAttachedToWindow() || w.getVisibility() != View.VISIBLE) continue;
            tmp.set(0f, 0f, w.getWidth(), w.getHeight());
            if (mapToRoot(host, w, tmp, true)) {
                needed = Math.max(needed, contentBottom + gap - tmp.top);
            }
        }
        if (needed == Float.NEGATIVE_INFINITY) return;
        int shift = Math.round(needed);
        for (WeakReference<View> ref : frame.widgets) {
            View w = ref.get();
            if (w != null) pushBy(w, PUSHED.get(w), shift);
        }
    }

    private static void findBelow(View root, View v, int depth, RectF clock, Set<View> skip, Set<View> holders,
            List<View> out, float density, RectF tmp) {
        if (v.getVisibility() != View.VISIBLE || depth > 40) return;
        if (!holders.contains(v) && v.getClass().getName().contains("Notification")) return;
        if (skip.contains(v)) return;
        if (v != root && !holders.contains(v)) {
            tmp.set(0f, 0f, v.getWidth(), v.getHeight());
            if (!mapToRoot(root, v, tmp, true)) return;
            if (tmp.width() <= 0f || tmp.height() <= 0f) return;
            if (tmp.right <= clock.left || tmp.left >= clock.right || tmp.bottom <= clock.top) return;
            float h = root.getHeight();
            float minTop = clock.top + Math.min(clock.height() * 0.35f, 72f * density) - PUSH_TOLERANCE_DP * density;
            if (tmp.top >= minTop) {
                if (tmp.top >= PUSH_MAX_TOP * h) return;
                if (tmp.bottom <= PUSH_MAX_BOTTOM * h) {
                    out.add(v);
                    return;
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                findBelow(root, g.getChildAt(i), depth + 1, clock, skip, holders, out, density, tmp);
            }
        }
    }

    private static void textInkBox(TextView view, RectF out) {
        Layout layout = view.getLayout();
        if (layout == null || layout.getLineCount() == 0) {
            out.set(0f, 0f, view.getWidth(), view.getHeight());
            return;
        }
        String s = String.valueOf(view.getText());
        Rect ink = new Rect();
        float top = Float.POSITIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
        int padTop = view.getExtendedPaddingTop();
        int avail = view.getHeight() - padTop - view.getExtendedPaddingBottom();
        int vg = view.getGravity() & Gravity.VERTICAL_GRAVITY_MASK;
        int vOff = vg == Gravity.BOTTOM ? avail - layout.getHeight()
                : vg == Gravity.CENTER_VERTICAL ? (avail - layout.getHeight()) / 2 : 0;
        float origin = padTop + Math.max(0, vOff) - view.getScrollY() - layout.getLineTop(0);
        CharSequence raw = view.getText();
        if (raw instanceof Spanned) {
            AnalogClock.DialSpan[] dials = ((Spanned) raw).getSpans(0, raw.length(), AnalogClock.DialSpan.class);
            if (dials.length > 0) {
                float baseline = origin + layout.getLineBaseline(0);
                out.set(0f, baseline - dials[0].size, view.getWidth(), baseline);
                return;
            }
        }
        for (int line = 0; line < layout.getLineCount(); line++) {
            int start = layout.getLineStart(line), end = Math.min(s.length(), layout.getLineEnd(line));
            while (end > start && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) end--;
            if (end <= start) continue;
            view.getPaint().getTextBounds(s, start, end, ink);
            if (ink.isEmpty()) continue;
            float baseline = origin + layout.getLineBaseline(line);
            top = Math.min(top, baseline + ink.top);
            bottom = Math.max(bottom, baseline + ink.bottom);
        }
        if (bottom > top) out.set(0f, top, view.getWidth(), bottom);
        else out.set(0f, 0f, view.getWidth(), view.getHeight());
    }

    private static boolean mapToRoot(View root, View v, RectF r, boolean stock) {
        View cur = v;
        while (cur != root) {
            Matrix m = cur.getMatrix();
            if (!m.isIdentity()) m.mapRect(r);
            r.offset(cur.getLeft(), cur.getTop());
            if (stock) {
                int[] p = PUSHED.get(cur);
                if (p != null && p[1] != 0) r.offset(0f, -p[1]);
            }
            ViewParent p = cur.getParent();
            if (!(p instanceof View)) return false;
            View pv = (View) p;
            r.offset(-pv.getScrollX(), -pv.getScrollY());
            cur = pv;
        }
        return true;
    }

    private static void pushBy(View v, int[] p, int shift) {
        if (p == null) return;
        ViewGroup.LayoutParams raw = v.getLayoutParams();
        if (!(raw instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) raw;
        int cur = lp.topMargin;
        if (cur != p[0] + p[1]) p[0] = cur;
        int next = p[0] + shift;
        if (cur != next) {
            lp.topMargin = next;
            v.setLayoutParams(lp);
        }
        p[1] = shift;
    }

    private static void unpush(View v, int[] p) {
        if (v == null || p == null || p[1] == 0) return;
        ViewGroup.LayoutParams raw = v.getLayoutParams();
        if (!(raw instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) raw;
        if (lp.topMargin == p[0] + p[1]) {
            lp.topMargin = p[0];
            v.setLayoutParams(lp);
        }
    }

    private static boolean isDescendant(View root, View v) {
        for (View cur = v; cur != null; cur = parent(cur)) {
            if (cur == root) return true;
        }
        return false;
    }

    private static void releaseFramePushed(Frame frame) {
        for (WeakReference<View> ref : frame.widgets) {
            View w = ref.get();
            if (w != null) unpush(w, PUSHED.remove(w));
        }
        frame.widgets.clear();
        frame.widgetsDirty = true;
    }

    private static void releaseAllPushed() {
        for (Map.Entry<View, int[]> e : new ArrayList<>(PUSHED.entrySet())) unpush(e.getKey(), e.getValue());
        PUSHED.clear();
    }

    // ------------------------------------------------------------------ reversible date changes

    private static final class FontState {
        Typeface original, applied, wanted;
        String originalAxes, appliedAxes, wantedAxes;

        FontState(TextView text) {
            original = applied = text.getTypeface();
            originalAxes = appliedAxes = text.getFontVariationSettings();
        }
    }

    private static void font(TextView text, TextView clock, Typeface want, String axes) {
        if (text == clock || want == null) return;
        Typeface current = text.getTypeface();
        String currentAxes = text.getFontVariationSettings();
        FontState state = FONTS.get(text);
        if (state == null) {
            if (current == want && Objects.equals(currentAxes, axes)) return;
            state = new FontState(text);
            FONTS.put(text, state);
        } else if (current != state.applied || !Objects.equals(currentAxes, state.appliedAxes)) {
            // A new plugin style becomes the restoration target; our own previous font never does.
            state.original = current;
            state.originalAxes = currentAxes;
            state.wanted = null;
        }
        if (state.wanted == want && Objects.equals(state.wantedAxes, axes)) return;
        if (current != want) text.setTypeface(want);
        if (!Objects.equals(text.getFontVariationSettings(), axes)) {
            try { text.setFontVariationSettings(axes); } catch (RuntimeException ignored) { }
        }
        // Record the actual result: setting axes can create another Typeface or reject unsupported axes.
        // Retry only when the source or plugin style changes, rather than reparsing a rejected setting every frame.
        state.wanted = want;
        state.wantedAxes = axes;
        state.applied = text.getTypeface();
        state.appliedAxes = text.getFontVariationSettings();
    }

    private static void restoreUnused(Set<View> hidden, Set<TextView> fonts, Set<TextView> scaledClocks) {
        for (View view : new ArrayList<>(HIDDEN)) {
            if (hidden.contains(view)) continue;
            HIDDEN.remove(view);
            try {
                if (view.getVisibility() == View.INVISIBLE) view.setVisibility(View.VISIBLE);
            } catch (Throwable t) { HookEntry.log("restore date", t); }
        }
        for (TextView text : new ArrayList<>(FONTS.keySet())) {
            if (fonts.contains(text)) continue;
            FontState state = FONTS.remove(text);
            if (state == null) continue;
            try {
                if (text.getTypeface() != state.applied
                        || !Objects.equals(text.getFontVariationSettings(), state.appliedAxes)) continue;
                text.setTypeface(state.original);
                if (!Objects.equals(text.getFontVariationSettings(), state.originalAxes)) {
                    try { text.setFontVariationSettings(state.originalAxes); } catch (RuntimeException ignored) { }
                }
            } catch (Throwable t) { HookEntry.log("restore date font", t); }
        }
        for (TextView clock : new ArrayList<>(CLOCK_SIZES.keySet())) {
            if (scaledClocks.contains(clock)) continue;
            try {
                restoreClockSize(clock);
            } catch (Throwable t) { HookEntry.log("restore clock size", t); }
        }
    }

    // ------------------------------------------------------------------ plugin names

    static boolean isWidget(View v) {
        if (v instanceof AppWidgetHostView) return true;
        String id = vivoId(v);
        if (id != null && isWidgetName(words(id))) return true;
        Class<?> c = v.getClass();
        return c.getName().startsWith(VIVO) && isWidgetName(words(c.getSimpleName()));
    }

    static boolean isDate(View v) {
        String id = vivoId(v);
        if (id != null && isDateName(words(id))) return true;
        Class<?> c = v.getClass();
        return c.getName().startsWith(VIVO) && isDateName(words(c.getSimpleName()));
    }

    static boolean isDateName(List<String> w) {
        return !isWidgetName(w) && (w.contains("date") || w.contains("lunar") || w.contains("week") || w.contains("weekday"));
    }

    private static boolean isWidgetName(List<String> w) {
        return w.contains("widget") || w.contains("widgets");
    }

    private static boolean isClockName(List<String> w) {
        return w.contains("hour") || w.contains("hours") || w.contains("minute") || w.contains("minutes")
                || w.contains("time") || w.contains("colon") || w.contains("ampm");
    }

    private static String vivoId(View v) {
        int id = v.getId();
        if (id == View.NO_ID || (id >>> 24) == 0) return null;
        Resources res = v.getResources();
        Map<Integer, String> names = NAMES.get(res);
        if (names == null) {
            names = new HashMap<>();
            NAMES.put(res, names);
        }
        if (names.containsKey(id)) return names.get(id);
        String name = null;
        try {
            if (res.getResourcePackageName(id).startsWith(VIVO)) name = res.getResourceEntryName(id);
        } catch (Resources.NotFoundException ignored) { }
        names.put(id, name);
        return name;
    }

    /** "mHourViewDof" -> [m, hour, view, dof]; "tv_lunar_date" -> [tv, lunar, date]; "TVDate" -> [tv, date]. */
    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char ch = s.charAt(i);
            if (!Character.isLetter(ch)) {
                flush(b, out);
                continue;
            }
            if (Character.isUpperCase(ch) && b.length() > 0) {
                boolean lowerNext = i + 1 < n && Character.isLowerCase(s.charAt(i + 1));
                if (!Character.isUpperCase(s.charAt(i - 1)) || lowerNext) flush(b, out);
            }
            b.append(ch);
        }
        flush(b, out);
        return out;
    }

    private static void flush(StringBuilder b, List<String> out) {
        if (b.length() == 0) return;
        out.add(b.toString().toLowerCase(Locale.ROOT));
        b.setLength(0);
    }

    private static final class Fields {
        final List<Field> clocks = new ArrayList<>(), dates = new ArrayList<>();
        final List<Boolean> primary = new ArrayList<>();
    }

    private static Fields fields(Class<?> type) {
        WeakReference<Fields> ref = FIELDS.get(type);
        Fields cached = ref != null ? ref.get() : null;
        if (cached != null) return cached;
        Fields out = new Fields();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !View.class.isAssignableFrom(f.getType())) continue;
                List<String> w = words(f.getName());
                boolean date = isDateName(w), clock = !date && isClockName(w);
                if (!date && !clock) continue;
                try { f.setAccessible(true); } catch (RuntimeException ignored) { continue; }
                if (date) out.dates.add(f);
                else {
                    out.clocks.add(f);
                    out.primary.add(!w.contains("dof"));
                }
            }
        }
        FIELDS.put(type, new WeakReference<>(out));
        return out;
    }

    private static <T> Set<T> weakSet() {
        return Collections.newSetFromMap(new WeakHashMap<>());
    }

    private static <T> Set<T> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static boolean onMain() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    private static final class Main {
        static final Handler HANDLER = new Handler(Looper.getMainLooper());
    }

    private static Handler main() { return Main.HANDLER; }
}

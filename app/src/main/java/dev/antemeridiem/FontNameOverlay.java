package dev.antemeridiem;

import android.graphics.Color;
import android.graphics.Rect;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/** A non-interactive caption belonging to the font list, not to the lock screen itself. */
final class FontNameOverlay implements View.OnLayoutChangeListener, View.OnAttachStateChangeListener,
        ViewTreeObserver.OnGlobalLayoutListener {
    private static final WeakHashMap<ViewGroup, WeakReference<FontNameOverlay>> LIVE = new WeakHashMap<>();
    private final ViewGroup list;
    private ViewGroup host;
    private ViewTreeObserver observer;
    final TextView label;
    private boolean hasName;

    private FontNameOverlay(ViewGroup list) {
        this.list = list;
        label = new TextView(list.getContext());
        label.setGravity(Gravity.CENTER);
        label.setTextColor(Color.WHITE);
        label.setTextSize(12);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(dp(4), 0, dp(4), 0);
        label.setShadowLayer(dp(1), 0, dp(1), 0xAA000000);
        label.setClickable(false);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        list.addOnLayoutChangeListener(this);
        list.addOnAttachStateChangeListener(this);
        observeLayout();
    }

    /** Prefer the first ancestor with room below the list. Overlay views never intercept touch events. */
    static void show(ViewGroup list, String name) {
        WeakReference<FontNameOverlay> ref = LIVE.get(list);
        FontNameOverlay overlay = ref == null ? null : ref.get();
        if (name == null || name.trim().isEmpty()) {
            if (overlay != null) {
                overlay.hasName = false;
                overlay.label.setVisibility(View.GONE);
            }
            return;
        }
        if (overlay == null) {
            overlay = new FontNameOverlay(list);
            LIVE.put(list, new WeakReference<>(overlay));
        }
        overlay.hasName = true;
        overlay.label.setText(name);
        overlay.position();
    }

    static FontNameOverlay currentForTest(ViewGroup list) {
        WeakReference<FontNameOverlay> ref = LIVE.get(list);
        return ref == null ? null : ref.get();
    }

    private int dp(float value) {
        return Math.round(value * list.getResources().getDisplayMetrics().density);
    }

    private void observeLayout() {
        ViewTreeObserver current = list.getViewTreeObserver();
        if (observer == current) return;
        if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
        observer = current;
        if (observer.isAlive()) observer.addOnGlobalLayoutListener(this);
    }

    private void position() {
        if (!hasName || !list.isShown()) {
            label.setVisibility(View.GONE);
            return;
        }
        int height = dp(22), gap = dp(3);
        Rect rect = new Rect();
        ViewGroup target = null;
        for (ViewParent p = list.getParent(); p instanceof ViewGroup; p = p.getParent()) {
            ViewGroup candidate = (ViewGroup) p;
            list.getDrawingRect(rect);
            candidate.offsetDescendantRectToMyCoords(list, rect);
            if (rect.bottom + gap + height <= candidate.getHeight()) {
                target = candidate;
                break;
            }
        }
        if (target == null) {
            // A tightly clipped menu has no space below the list; keep the caption visible inside it.
            target = list;
            list.getDrawingRect(rect);
        }
        int available = Math.max(0, Math.min(rect.width(), target.getWidth()) - dp(12));
        if (available == 0 || target.getHeight() == 0) {
            label.setVisibility(View.GONE);
            return;
        }
        if (host != target) {
            if (host != null) host.getOverlay().remove(label);
            host = target;
            host.getOverlay().add(label);
        }
        int width = Math.min(available, dp(240));
        height = Math.min(target.getHeight(), height);
        int left = Math.max(0, Math.min(target.getWidth() - width, rect.centerX() - width / 2));
        int top = target == list ? Math.max(0, target.getHeight() - height) : rect.bottom + gap;
        label.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        label.layout(left, top, left + width, top + height);
        label.setAlpha(list.getAlpha());
        label.setVisibility(View.VISIBLE);
    }

    @Override public void onGlobalLayout() { position(); }
    @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                         int oldL, int oldT, int oldR, int oldB) { position(); }
    @Override public void onViewAttachedToWindow(View v) { observeLayout(); position(); }
    @Override public void onViewDetachedFromWindow(View v) {
        if (host != null) host.getOverlay().remove(label);
        if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
        observer = null;
        list.removeOnLayoutChangeListener(this);
        list.removeOnAttachStateChangeListener(this);
        LIVE.remove(list);
    }
}

package dev.antemeridiem;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.NoCopySpan;
import android.text.SpanWatcher;
import android.text.style.ReplacementSpan;
import android.widget.TextView;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Experimental static Big Clock: installed only on known vivo clock components. */
final class BigClock {
    static final String ID = "lf.samsung_big_clock";
    private static final String ROOT = "com.vivo.systemuiplugin.keyguard.time.style.";
    private static final String[][] COMPONENTS = {
            {"s1.S1TimeComponent", "hourView", "hourViewDof"},
            {"s2.S2TimeComponent", "hourView", "hourViewDof"},
            {"s3.S3TimeComponent", "hourView", "hourViewDof", "minuteView", "minuteViewDof"},
    };
    private static final Map<Object, Boolean> SELECTED = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile Bitmap[] bitmaps;
    private BigClock() { }

    static void install(ClassLoader loader) {
        for (String[] spec : COMPONENTS) {
            try {
                Class<?> type = Class.forName(ROOT + spec[0], false, loader);
                HookEntry.interceptMethods(type, "updateFont", chain -> {
                    Object result = chain.proceed();   // a throwing original skips the rest
                    try {
                        Object self = chain.getThisObject();
                        Object font = chain.getArgs().get(0);
                        Object id = font.getClass().getMethod("getFontId").invoke(font);
                        boolean selected = ID.equals(id) && load();
                        if (selected) SELECTED.put(self, true); else SELECTED.remove(self);
                        AnalogClock.attach(self);
                        if (AnalogClock.enabled()) AnalogClock.render(self);   // same font, dial layout
                        else refresh(self, spec, selected);
                    } catch (Throwable t) { HookEntry.log("Big Clock font", t); }
                    return result;
                });
                HookEntry.interceptMethods(type, "updateTime", chain -> {
                    Object result = chain.proceed();
                    Object self = chain.getThisObject();
                    try {
                        AnalogClock.attach(self);
                        if (AnalogClock.enabled()) AnalogClock.render(self);
                        else if (SELECTED.containsKey(self)) refresh(self, spec, true);
                    } catch (Throwable t) { HookEntry.log("Big Clock time", t); }
                    return result;
                });
            } catch (Throwable t) { HookEntry.log("Big Clock component " + spec[0], t); }
        }
    }

    private static void refresh(Object component, String[] fields, boolean selected) {
        for (int i = 1; i < fields.length; i++) {
            try {
                Object view = component.getClass().getField(fields[i]).get(component);
                if (view instanceof TextView) apply((TextView) view, selected);
            } catch (ReflectiveOperationException ignored) { /* another plugin version */ }
        }
    }

    /** Big Clock digit images for the analog dial, or null when this component uses another font. */
    static Bitmap[] digitsFor(Object component) {
        return component != null && SELECTED.containsKey(component) ? bitmaps : null;
    }

    /** Back to the digital layout: Big Clock digits again where that font is selected. */
    static void reapply(Object component) {
        if (component == null) return;
        String name = component.getClass().getName();
        for (String[] spec : COMPONENTS) {
            if (name.equals(ROOT + spec[0])) refresh(component, spec, SELECTED.containsKey(component));
        }
    }

    static void apply(TextView view, boolean selected) {
        CharSequence existing = view.getText();
        if (existing == null || existing.length() == 0) return;
        SpannableString text = new SpannableString(existing.toString());
        boolean changed = false;
        if (existing instanceof Spanned) {
            Spanned source = (Spanned) existing;
            for (Object span : source.getSpans(0, source.length(), Object.class)) {
                if (span instanceof DigitSpan || span instanceof ColonSpan || span instanceof AnalogClock.OwnSpan) {
                    changed = true;
                    continue;
                }
                // Do not copy TextView's internal change watchers to a detached buffer.
                if (span instanceof NoCopySpan || span instanceof SpanWatcher) continue;
                text.setSpan(span, source.getSpanStart(span), source.getSpanEnd(span), source.getSpanFlags(span));
            }
        }
        if (selected && bitmaps != null) {
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                int n = ch - '0';
                if (n >= 0 && n <= 9) {
                    text.setSpan(new DigitSpan(bitmaps[n]), i, i + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    changed = true;
                } else if (ch == ':' || ch == '\uFF1A') {
                    text.setSpan(new ColonSpan(), i, i + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    changed = true;
                }
            }
        }
        if (changed) view.setText(text, TextView.BufferType.SPANNABLE);
    }

    private static synchronized boolean load() {
        if (bitmaps != null) return true;
        if (HookEntry.modulePath == null) return false;
        Bitmap[] loaded = new Bitmap[10];
        try (ZipFile zip = new ZipFile(HookEntry.modulePath)) {
            for (int i = 0; i < 10; i++) {
                ZipEntry entry = zip.getEntry("assets/big_clock/" + i + ".png");
                if (entry == null) return false;
                try (InputStream in = zip.getInputStream(entry)) { loaded[i] = BitmapFactory.decodeStream(in); }
                if (loaded[i] == null) return false;
            }
            bitmaps = loaded;
            return true;
        } catch (Exception e) { HookEntry.log("Big Clock assets", e); return false; }
    }

    /** Thumbnail for the same item in vivo's font picker. */
    static Drawable thumbnail(int color, int size) {
        if (!load()) return null;
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            {
                paint.setColorFilter(new PorterDuffColorFilter(color | 0xff000000, PorterDuff.Mode.SRC_IN));
                paint.setAlpha(android.graphics.Color.alpha(color));
            }
            @Override public void draw(Canvas canvas) {
                Rect b = getBounds();
                if (b.isEmpty()) return;
                float h = Math.min(b.height() * .6f, b.width() * 1.2f / (2f * .56f / .72f));
                float w = h * .56f / .72f;
                float left = b.exactCenterX() - w, top = b.exactCenterY() - h / 2;
                canvas.drawBitmap(bitmaps[1], null, new RectF(left, top, left + w, top + h), paint);
                canvas.drawBitmap(bitmaps[0], null, new RectF(left + w, top, left + 2*w, top + h), paint);
            }
            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
            @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
            @Override public int getIntrinsicWidth() { return size; }
            @Override public int getIntrinsicHeight() { return size; }
        };
    }

    private static final class DigitSpan extends ReplacementSpan {
        private final Bitmap bitmap;
        DigitSpan(Bitmap bitmap) { this.bitmap = bitmap; }
        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            float height = paint.getTextSize() * .72f;
            return Math.round(height * bitmap.getWidth() / bitmap.getHeight());
        }
        @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                int top, int y, int bottom, Paint paint) {
            float height = paint.getTextSize() * .72f;
            float width = height * bitmap.getWidth() / bitmap.getHeight();
            Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            bitmapPaint.setColorFilter(new PorterDuffColorFilter(paint.getColor() | 0xff000000, PorterDuff.Mode.SRC_IN));
            bitmapPaint.setAlpha(paint.getAlpha());
            canvas.drawBitmap(bitmap, null, new RectF(x, y - height, x + width, y), bitmapPaint);
        }
    }

    private static final class ColonSpan extends ReplacementSpan {
        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            float height = paint.getTextSize() * .72f;
            return Math.round(height * .36f);
        }
        @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                int top, int y, int bottom, Paint paint) {
            float height = paint.getTextSize() * .72f;
            float width = height * .36f;
            float cx = x + width / 2f;
            float r = height * .085f;
            Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            dotPaint.setColor(paint.getColor());
            dotPaint.setAlpha(paint.getAlpha());
            dotPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, y - height * .66f, r, dotPaint);
            canvas.drawCircle(cx, y - height * .34f, r, dotPaint);
        }
    }
}

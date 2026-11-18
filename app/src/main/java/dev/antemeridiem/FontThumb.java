package dev.antemeridiem;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Picture of a font in the editor's font list: "10" in the font itself, sized like vivo's own pictures
 * (54 dp square, digits about 60 % of the height) and in the colour of the picture vivo put there first.
 */
final class FontThumb extends Drawable {
    static final String SAMPLE = "10";
    private static final float HEIGHT_SHARE = 0.6f, WIDTH_SHARE = 1.2f;
    private static final Map<String, Typeface> TYPEFACES = new HashMap<>();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Rect text = new Rect();
    private final int size;
    final int color;

    FontThumb(Typeface typeface, int color, int sizePx) {
        this.color = color;
        this.size = sizePx;
        paint.setTypeface(typeface);
        paint.setColor(color);
    }

    /** Null when the font cannot be loaded; the caller then keeps vivo's picture. */
    static Drawable create(ImageView iv, String id, int weight) {
        Typeface tf = typeface(id, weight);
        if (tf == null) return null;
        Context c = iv.getContext();
        int color = sampleColor(iv.getDrawable(), isNight(c) ? Color.WHITE : Color.BLACK);
        int size = Math.round(54 * c.getResources().getDisplayMetrics().density);
        if (BigClock.ID.equals(id)) {
            Drawable thumb = BigClock.thumbnail(color, size);
            if (thumb != null) return thumb;
        }
        return new FontThumb(tf, color, size);
    }

    static synchronized Typeface typeface(String id, int weight) {
        String key = id + "@" + weight;
        Typeface tf = TYPEFACES.get(key);
        if (tf != null) return tf;
        String path = FontResolver.realPath(id);
        if (path == null) return null;
        Typeface.Builder b = new Typeface.Builder(new File(path));
        if (weight >= 0) b.setFontVariationSettings(String.format(Locale.ROOT, "'wght' %d", weight));
        tf = b.build();
        if (tf != null) {
            if (TYPEFACES.size() > 64) TYPEFACES.clear();     // the weight slider creates one entry per weight
            TYPEFACES.put(key, tf);
        }
        return tf;
    }

    private static boolean isNight(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** The most opaque colour of vivo's own picture, so ours follows its day / night / theme colours. */
    static int sampleColor(Drawable d, int fallback) {
        if (d instanceof FontThumb) return ((FontThumb) d).color;
        if (d == null) return fallback;
        try {
            int s = 32;
            Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Rect old = new Rect(d.getBounds());
            d.setBounds(0, 0, s, s);
            d.draw(new Canvas(bmp));
            d.setBounds(old);
            int[] px = new int[s * s];
            bmp.getPixels(px, 0, s, 0, 0, s, s);
            bmp.recycle();
            int best = -1, alpha = 0;
            for (int p : px) {
                int a = Color.alpha(p);
                if (a > alpha) {
                    alpha = a;
                    best = p;
                }
            }
            return alpha >= 128 ? (best | 0xFF000000) : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        paint.setTextSize(100f);
        paint.getTextBounds(SAMPLE, 0, SAMPLE.length(), text);
        if (text.isEmpty()) return;
        float scale = Math.min(b.height() * HEIGHT_SHARE / text.height(), b.width() * WIDTH_SHARE / text.width());
        paint.setTextSize(100f * scale);
        paint.getTextBounds(SAMPLE, 0, SAMPLE.length(), text);
        float x = b.exactCenterX() - text.exactCenterX();
        float y = b.exactCenterY() - text.exactCenterY();
        canvas.drawText(SAMPLE, x, y, paint);
    }

    @Override public int getIntrinsicWidth() {
        return size;
    }

    @Override public int getIntrinsicHeight() {
        return size;
    }

    @Override public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
        invalidateSelf();
    }

    @SuppressWarnings("deprecation")   // still abstract in Drawable
    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}

package dev.antemeridiem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35)
public class DialRenderTest {
    /** Widths of 12, 3, 6, 9 per unit of digit height. */
    private static final float[] K = {1.2f, 0.6f, 0.6f, 0.6f};

    @Test public void numbersKeepTheMaximumSizeSpacingAtEveryScale() {
        float d = 1000f;
        float maxDigit = AnalogClock.digitHeight(d, 150, K);
        for (int percent = 50; percent <= 150; percent += 5) {
            float ring = AnalogClock.ringSize(d, percent);
            float digit = AnalogClock.digitHeight(d, percent, K);
            assertEquals("same proportions at " + percent, maxDigit / d, digit / ring, 0.0001f);
            assertEquals("digits stay close at " + percent, AnalogClock.TOUCH_GAP,
                    AnalogClock.spacing(ring, digit, K) / ring, 0.0001f);
        }
        assertEquals(AnalogClock.digitHeight(d, 50, K) * 2, AnalogClock.digitHeight(d, 100, K), 0.01f);
        assertEquals(AnalogClock.digitHeight(d, 100, K) * 1.5f, maxDigit, 0.01f);
    }

    @Test public void wholeDialScalesContinuouslyUpToTheMaximum() {
        assertEquals(96, AnalogClock.faceSize(288, 50));
        assertEquals(144, AnalogClock.faceSize(288, 75));
        assertEquals(192, AnalogClock.faceSize(288, 100));
        assertEquals(240, AnalogClock.faceSize(288, 125));
        assertEquals(288, AnalogClock.faceSize(288, 150));
        float lastDigit = 0, lastRing = 0;
        for (int percent = 50; percent <= 150; percent++) {
            float digit = AnalogClock.digitHeight(1000, percent, K);
            float ring = AnalogClock.ringSize(1000, percent);
            assertTrue(digit > lastDigit);
            assertTrue(ring > lastRing);
            lastDigit = digit;
            lastRing = ring;
        }
    }

    @Test public void veryWideDigitsFitTheRingAndDoNotCoverOppositeNumbers() {
        for (float[] widths : new float[][]{K, {20f, 0.6f, 0.6f, 0.6f}, {2f, 6f, 1f, 6f}}) {
            for (int diameter : new int[]{12, 50, 1000}) {
                for (int percent = 50; percent <= 150; percent += 5) {
                    float h = AnalogClock.digitHeight(diameter, percent, widths);
                    float ring = AnalogClock.ringSize(diameter, percent);
                    for (float width : widths) assertTrue(width * h <= ring + 0.01f);
                    assertTrue((widths[1] + widths[3]) * h <= ring * (1 - AnalogClock.TOUCH_GAP) + 0.01f);
                    assertTrue(AnalogClock.spacing(ring, h, widths) >= ring * AnalogClock.TOUCH_GAP - 0.01f);
                    assertEquals("wide fonts keep the maximum layout too",
                            AnalogClock.digitHeight(diameter, 150, widths) / diameter, h / ring, 0.0001f);
                }
            }
        }
    }

    @Test public void handsAreTheDrawingNotSquares() throws Exception {
        Bitmap b = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888);
        Paint clock = new Paint(Paint.ANTI_ALIAS_FLAG);
        clock.setColor(Color.WHITE);
        float d = 800f, s = d * AnalogClock.MINUTE_HAND / 578.5f, cx = 500f, cy = 500f;
        AnalogClock.drawHands(new Canvas(b), clock, cx, cy, d, 0, 0, -1, false);
        save(b, "hands.png");
        int tip = px(b, cx, cy - 572 * s);
        assertTrue("outline at the tip: " + hex(tip), Color.alpha(tip) > 200 && Color.red(tip) < 80);
        int corner = px(b, cx + 25 * s, cy - 576 * s);
        assertTrue("rounded tip, not a square corner: " + hex(corner), Color.alpha(corner) < 90);
        int inside = px(b, cx, cy - 450 * s);
        assertTrue("inside in the clock colour: " + hex(inside), Color.alpha(inside) > 200 && Color.red(inside) > 200);
        int edge = px(b, cx + 20 * s, cy - 450 * s);
        assertTrue("black outline: " + hex(edge), Color.alpha(edge) > 200 && Color.red(edge) < 80);
        int beside = px(b, cx + 60 * s, cy - 450 * s);
        assertTrue("nothing beside the hand: " + hex(beside), Color.alpha(beside) < 40);

        Bitmap r = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888);
        AnalogClock.drawHands(new Canvas(r), clock, cx, cy, d, 0, 0, 15, false);
        save(r, "hands-second.png");
        int red = px(r, cx + 300 * s, cy);
        assertTrue("red second hand: " + hex(red), Color.red(red) > 150 && Color.green(red) < 90);
    }

    @Test public void insideOfTheHandsIsColouredLikeTheNumbers() {
        float d = 800f, s = d * AnalogClock.MINUTE_HAND / 578.5f, cx = 500f, cy = 500f;
        // A clock colour that only its colour filter makes white, and a translucent white.
        Paint filtered = new Paint(Paint.ANTI_ALIAS_FLAG);
        filtered.setColor(Color.BLACK);
        filtered.setColorFilter(new android.graphics.PorterDuffColorFilter(Color.WHITE,
                android.graphics.PorterDuff.Mode.SRC_IN));
        Paint translucent = new Paint(Paint.ANTI_ALIAS_FLAG);
        translucent.setColor(0x80FFFFFF);
        for (Paint clock : new Paint[] {filtered, translucent}) {
            for (boolean aod : new boolean[] {false, true}) {
                Bitmap b = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888);
                AnalogClock.drawHands(new Canvas(b), clock, cx, cy, d, 0, 0, -1, aod);
                int inside = px(b, cx, cy - 450 * s);
                assertTrue("white inside, not on black: " + hex(inside) + (aod ? " (AOD)" : ""),
                        Color.alpha(inside) >= Color.alpha(clock.getColor()) - 3 && Color.red(inside) > 240
                                && Color.blue(inside) > 240);
                int edge = px(b, cx + 20 * s, cy - 450 * s);
                assertTrue("outline around it: " + hex(edge), Color.alpha(edge) > 40
                        && (aod ? Color.alpha(edge) < Color.alpha(inside) : Color.red(edge) < 80));
            }
        }
    }

    @Test public void rendersDials() throws Exception {
        for (int percent : new int[] {50, 100, 150}) {
            Bitmap b = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawColor(0xFF2D3A4A);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFF9FD3FF);
            p.setTextSize(120f);
            AnalogClock.drawDial(c, p, 300f, 300f, 560f, 10, 8, true, false, null, percent, null);
            save(b, "dial-" + percent + ".png");
        }
    }

    private static int px(Bitmap b, float x, float y) {
        return b.getPixel((int) Math.floor(x), (int) Math.floor(y));
    }

    private static String hex(int c) {
        return Integer.toHexString(c);
    }

    private static void save(Bitmap b, String name) throws Exception {
        File dir = new File("build/render");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}

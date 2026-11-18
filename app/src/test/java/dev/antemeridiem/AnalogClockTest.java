package dev.antemeridiem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.text.Spanned;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.StyleSpan;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.LinearLayout;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w360dp-h800dp-mdpi")
public class AnalogClockTest {
    @After public void tearDown() {
        AnalogClock.hands = true;
        AnalogClock.analog = false;
        AnalogClock.scale = AnalogClock.DEFAULT_SCALE;
    }

    @Test public void readsTheShownTime() {
        int[] t = AnalogClock.parseTime("10:08", null);
        assertEquals(10, t[0]);
        assertEquals(8, t[1]);
        t = AnalogClock.parseTime("7", "45");
        assertEquals(7, t[0]);
        assertEquals(45, t[1]);
        t = AnalogClock.parseTime("23\n59", null);
        assertEquals(23, t[0]);
        assertEquals(59, t[1]);
        t = AnalogClock.parseTime("٢٣:٥٩", null);
        assertEquals(23, t[0]);
        assertEquals(59, t[1]);
        t = AnalogClock.parseTime("２３：５９", null);
        assertEquals(23, t[0]);
        assertEquals(59, t[1]);
    }

    @Test public void dialReplacesTheDigitsAndCanBeRemoved() {
        TextView hour = view("10");
        TextView minute = view("08");
        AnalogClock.apply(hour, new AnalogClock.Role(true, false, minute, null), true);
        AnalogClock.apply(minute, new AnalogClock.Role(false, false, hour, null), true);

        assertEquals("10", hour.getText().toString());
        assertEquals("08", minute.getText().toString());
        assertTrue(AnalogClock.hasOwn(hour.getText()));
        AnalogClock.DialSpan[] dial = ((Spanned) hour.getText()).getSpans(0, 2, AnalogClock.DialSpan.class);
        assertEquals(1, dial.length);
        assertEquals(10, dial[0].hour);
        assertEquals(8, dial[0].minute);
        int size = dial[0].size;
        // Smaller settings scale the same tightly packed 150% dial, including the room used by the TextView.
        assertEquals(AnalogClock.faceSize(288, 100), size);
        assertEquals(192, size);

        int any = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        hour.measure(any, any);
        assertEquals(size, hour.getMeasuredWidth());
        // the dial keeps the room the font leaves above and below digital digits
        int[] room = AnalogClock.textRoom(hour.getPaint());
        assertTrue("room below the dial", room[2] > 0);
        assertTrue(Math.abs(hour.getMeasuredHeight() - (size + room[0] + room[2])) <= 2);
        minute.measure(any, any);
        assertEquals(0, minute.getMeasuredWidth());

        Bitmap b = draw(hour, size);
        assertTrue("12 at the top", ink(b, 0.35f, 0f, 0.65f, 0.22f) > 40);
        assertTrue("6 at the bottom", ink(b, 0.4f, 0.78f, 0.6f, 1f) > 40);
        assertTrue("3 on the right", ink(b, 0.78f, 0.4f, 1f, 0.6f) > 40);
        assertTrue("9 on the left", ink(b, 0f, 0.4f, 0.22f, 0.6f) > 40);
        assertEquals("corners stay empty", 0, ink(b, 0f, 0f, 0.12f, 0.12f));
        assertTrue("hub", Color.alpha(b.getPixel(size / 2, size / 2)) > 0);

        AnalogClock.hands = false;
        hour.invalidate();
        Bitmap plain = draw(hour, size);
        assertEquals("no hands", 0, ink(plain, 0.45f, 0.45f, 0.55f, 0.55f));

        AnalogClock.strip(hour);
        AnalogClock.strip(minute);
        assertEquals("10", hour.getText().toString());
        assertFalse(AnalogClock.hasOwn(hour.getText()));
        assertFalse(AnalogClock.hasOwn(minute.getText()));
    }

    @Test public void theDateIsAsFarFromTheDialAsFromTheDigits() {
        TextView digital = view("10");
        TextView hour = view("10");
        TextView minute = view("08");
        AnalogClock.apply(hour, new AnalogClock.Role(true, false, minute, null), true);
        assertTrue(AnalogClock.hasOwn(hour.getText()));
        int any = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        digital.measure(any, any);
        hour.measure(any, any);
        Rect ink = new Rect();
        digital.getPaint().getTextBounds("10", 0, 2, ink);
        // from the lowest ink (digits / the 6 of the dial, on the baseline) to the bottom of the view, where the date starts
        int belowDigits = digital.getMeasuredHeight() - digital.getBaseline() - Math.max(0, ink.bottom);
        int belowDial = hour.getMeasuredHeight() - hour.getBaseline();
        assertTrue("digital clock has room below: " + belowDigits, belowDigits > 0);
        assertEquals(belowDigits, belowDial, 2.0);
    }

    @Test public void digitalTextAndPartialFormattingSurviveTheRoundTrip() {
        TextView clock = view("10\n08");
        SpannableString text = new SpannableString("10\n08");
        StyleSpan bold = new StyleSpan(Typeface.BOLD);
        text.setSpan(bold, 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        clock.setText(text);
        AnalogClock.Role role = new AnalogClock.Role(true, false, null, null);
        AnalogClock.apply(clock, role, true);
        AnalogClock.apply(clock, role, true);
        assertEquals("10 08", clock.getText().toString());
        AnalogClock.strip(clock);
        assertEquals("10\n08", clock.getText().toString());
        Spanned restored = (Spanned) clock.getText();
        assertEquals(0, restored.getSpanStart(bold));
        assertEquals(2, restored.getSpanEnd(bold));
    }

    @Test public void editingASpannedTimeDoesNotResurrectTheOldDigits() {
        TextView clock = view("10:08");
        AnalogClock.Role role = new AnalogClock.Role(true, false, null, null);
        AnalogClock.apply(clock, role, true);
        SpannableStringBuilder changed = new SpannableStringBuilder(clock.getText());
        changed.replace(0, 1, "2");
        clock.setText(changed);
        AnalogClock.apply(clock, role, false);
        AnalogClock.DialSpan dial = ((Spanned) clock.getText()).getSpans(0, 5, AnalogClock.DialSpan.class)[0];
        assertEquals(20, dial.hour);
        AnalogClock.strip(clock);
        assertEquals("20:08", clock.getText().toString());
    }

    @Test public void aMinuteOnlyAodUpdateMovesTheDialHands() {
        LinearLayout root = new LinearLayout(RuntimeEnvironment.getApplication());
        root.setOrientation(LinearLayout.VERTICAL);
        TextView hour = view("10"), minute = view("08");
        root.addView(hour);
        root.addView(minute);
        AnalogClock.analog = true;
        AnalogClock.scanAod(root);
        minute.setText("09");
        AnalogClock.trackedTextChanged(minute);
        AnalogClock.DialSpan dial = ((Spanned) hour.getText()).getSpans(0, 2, AnalogClock.DialSpan.class)[0];
        assertEquals(10, dial.hour);
        assertEquals(9, dial.minute);
        assertTrue(dial.aod);
    }

    @Test public void narrowClockContainersAreRespected() {
        TextView clock = view("10:08");
        clock.setLayoutParams(new ViewGroup.LayoutParams(60, ViewGroup.LayoutParams.WRAP_CONTENT));
        clock.setPadding(5, 0, 5, 0);
        clock.layout(0, 0, 60, 100);
        assertEquals(50, AnalogClock.dialSize(clock, false));
        AnalogClock.scale = 150;
        AnalogClock.apply(clock, new AnalogClock.Role(true, false, null, null), true);
        AnalogClock.DialSpan span = ((Spanned) clock.getText()).getSpans(0, 5, AnalogClock.DialSpan.class)[0];
        assertEquals(50, span.size);
    }

    private static TextView view(String text) {
        TextView v = new TextView(RuntimeEnvironment.getApplication());
        v.setTextSize(80);
        v.setTextColor(Color.WHITE);
        v.setIncludeFontPadding(false);
        v.setPadding(0, 0, 0, 0);
        v.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        v.setText(text);
        return v;
    }

    /** The dial square (the span right above the baseline) of the laid out view. */
    private static Bitmap draw(TextView v, int size) {
        int any = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        v.measure(any, any);
        int w = Math.max(size, v.getMeasuredWidth()), h = Math.max(size, v.getMeasuredHeight());
        v.layout(0, 0, w, h);
        Bitmap full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(full));
        int top = Math.max(0, Math.min(h - size, v.getBaseline() - size));
        return Bitmap.createBitmap(full, 0, top, size, size);
    }

    private static int ink(Bitmap b, float l, float t, float r, float bottom) {
        int n = 0;
        int w = b.getWidth(), h = b.getHeight();
        for (int y = (int) (t * h); y < Math.min(h, (int) (bottom * h)); y++) {
            for (int x = (int) (l * w); x < Math.min(w, (int) (r * w)); x++) {
                if (Color.alpha(b.getPixel(x, y)) > 40) n++;
            }
        }
        return n;
    }
}

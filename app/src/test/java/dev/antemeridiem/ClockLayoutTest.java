package dev.antemeridiem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w360dp-h800dp-mdpi")
public class ClockLayoutTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private LinearLayout root;

    @Before public void setup() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        activity.setContentView(root);
    }

    @After public void cleanup() {
        ClockLayout.update(false, false);
        ClockLayout.applyNow();
        AnalogClock.update(false, true, AnalogClock.DEFAULT_SCALE);
        controller.pause().stop().destroy();
    }

    @Test public void splitsNames() {
        assertEquals(Arrays.asList("m", "hour", "view", "dof"), ClockLayout.words("mHourViewDof"));
        assertEquals(Arrays.asList("tv", "lunar", "date"), ClockLayout.words("tv_lunar_date"));
        assertEquals(Arrays.asList("tv", "date"), ClockLayout.words("TVDate"));
        assertTrue(ClockLayout.isDateName(ClockLayout.words("keyguard_date_view")));
        assertTrue(!ClockLayout.isDateName(ClockLayout.words("update_view")));
        assertTrue(!ClockLayout.isDateName(ClockLayout.words("date_widget")));
    }

    @Test public void analogSizeNeverChangesDigitalViewGeometry() {
        Component c = addComponent(Typeface.MONOSPACE);
        c.hourView.setScaleX(0.8f);
        c.hourView.setScaleY(0.9f);
        c.hourView.setTranslationX(12f);
        c.hourView.setTranslationY(24f);
        c.hourView.setPivotX(7f);
        c.hourView.setPivotY(9f);
        float textSize = c.hourView.getTextSize();
        ViewGroup.LayoutParams params = c.hourView.getLayoutParams();
        ClockLayout.update(false, true);
        for (int size : new int[]{50, 100, 150}) {
            AnalogClock.update(false, true, size);
            ClockLayout.applyNow();
            drawFrame();
            assertEquals(0.8f, c.hourView.getScaleX(), 0f);
            assertEquals(0.9f, c.hourView.getScaleY(), 0f);
            assertEquals(12f, c.hourView.getTranslationX(), 0f);
            assertEquals(24f, c.hourView.getTranslationY(), 0f);
            assertEquals(7f, c.hourView.getPivotX(), 0f);
            assertEquals(9f, c.hourView.getPivotY(), 0f);
            assertEquals(textSize, c.hourView.getTextSize(), 0f);
            assertSame(params, c.hourView.getLayoutParams());
        }
    }

    @Test public void changingClockFontDoesNotOverwriteTheOriginalDateFont() {
        Component c = addComponent(Typeface.MONOSPACE);
        Typeface original = c.dateView.getTypeface();
        ClockLayout.update(false, true);
        ClockLayout.applyNow();
        assertSame(c.hourView.getTypeface(), c.dateView.getTypeface());
        c.hourView.setTypeface(Typeface.SERIF);
        drawFrame();
        assertSame(Typeface.SERIF, c.dateView.getTypeface());
        ClockLayout.update(false, false);
        ClockLayout.applyNow();
        assertSame(original, c.dateView.getTypeface());
    }

    @Test public void pluginDateRestylingBecomesTheNewRestorationTarget() {
        Component c = addComponent(Typeface.MONOSPACE);
        ClockLayout.update(false, true);
        ClockLayout.applyNow();
        Typeface newer = Typeface.create(Typeface.SERIF, Typeface.BOLD);
        c.dateView.setTypeface(newer);
        drawFrame();
        assertSame(c.hourView.getTypeface(), c.dateView.getTypeface());
        ClockLayout.update(false, false);
        ClockLayout.applyNow();
        assertSame(newer, c.dateView.getTypeface());
    }

    @Test public void eachComponentInTheSameWindowKeepsItsOwnFontSource() {
        Component lock = addComponent(Typeface.MONOSPACE);
        Component aod = addComponent(Typeface.SERIF);
        ClockLayout.update(false, true);
        ClockLayout.applyNow();
        for (int i = 0; i < 3; i++) {
            drawFrame();
            assertSame(lock.hourView.getTypeface(), lock.dateView.getTypeface());
            assertSame(aod.hourView.getTypeface(), aod.dateView.getTypeface());
        }
    }

    @Test public void removedDatesAreRestoredEvenThoughTheScanCannotFindThem() {
        Component c = addComponent(Typeface.MONOSPACE);
        TextView old = c.dateView;
        Typeface original = old.getTypeface();
        ClockLayout.update(true, true);
        ClockLayout.applyNow();
        assertEquals(View.INVISIBLE, old.getVisibility());
        c.container.removeView(old);
        c.dateView = null;
        ClockLayout.applyNow();
        assertEquals(View.VISIBLE, old.getVisibility());
        assertSame(original, old.getTypeface());
    }

    @Test public void datesCanBeHiddenWithoutAnyClockView() {
        DateOnly c = new DateOnly();
        c.dateView = text("Friday", Typeface.DEFAULT);
        root.addView(c.dateView);
        ClockLayout.component(c);
        ClockLayout.update(true, false);
        ClockLayout.applyNow();
        assertEquals(View.INVISIBLE, c.dateView.getVisibility());
        ClockLayout.update(false, false);
        ClockLayout.applyNow();
        assertEquals(View.VISIBLE, c.dateView.getVisibility());
    }

    @Test public void originallyGoneDatesStayGoneWhenHidingIsDisabled() {
        Component c = addComponent(Typeface.MONOSPACE);
        c.dateView.setVisibility(View.GONE);
        ClockLayout.update(true, false);
        ClockLayout.applyNow();
        ClockLayout.update(false, false);
        ClockLayout.applyNow();
        assertEquals(View.GONE, c.dateView.getVisibility());
    }

    @Test public void firstAttachmentAppliesSettingsWithoutARefreshTimer() {
        Component c = new Component();
        c.hourView = text("10:08", Typeface.MONOSPACE);
        c.dateView = text("Friday", Typeface.DEFAULT);
        ClockLayout.component(c);
        ClockLayout.update(true, true);
        ClockLayout.applyNow();
        root.addView(c.hourView);
        root.addView(c.dateView);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(View.INVISIBLE, c.dateView.getVisibility());
        assertSame(c.hourView.getTypeface(), c.dateView.getTypeface());
    }

    @Test public void layoutRebuildFindsAReplacementDate() {
        Component c = addComponent(Typeface.MONOSPACE);
        ClockLayout.update(true, true);
        ClockLayout.applyNow();
        c.container.removeView(c.dateView);
        c.dateView = text("Saturday", Typeface.DEFAULT);
        c.container.addView(c.dateView);
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        drawFrame();
        assertEquals(View.INVISIBLE, c.dateView.getVisibility());
        assertSame(c.hourView.getTypeface(), c.dateView.getTypeface());
    }

    @Test public void unchangedFramesDoNotResetTheDateTypeface() {
        Component c = addComponent(Typeface.MONOSPACE);
        CountingTextView date = new CountingTextView(activity);
        c.container.removeView(c.dateView);
        c.dateView = date;
        c.container.addView(date);
        ClockLayout.update(false, true);
        ClockLayout.applyNow();
        int writes = date.writes;
        for (int i = 0; i < 100; i++) drawFrame();
        assertEquals(writes, date.writes);
    }

    private Component addComponent(Typeface clockFont) {
        Component c = new Component();
        c.container = new LinearLayout(activity);
        c.container.setOrientation(LinearLayout.VERTICAL);
        c.hourView = text("10:08", clockFont);
        c.dateView = text("Friday", Typeface.SANS_SERIF);
        c.container.addView(c.hourView);
        c.container.addView(c.dateView);
        root.addView(c.container);
        ClockLayout.component(c);
        return c;
    }

    private TextView text(String value, Typeface font) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTypeface(font);
        return text;
    }

    private void drawFrame() { root.getViewTreeObserver().dispatchOnPreDraw(); }

    private static final class Component {
        LinearLayout container;
        TextView hourView, dateView;
    }

    private static final class DateOnly { TextView dateView; }

    private static final class CountingTextView extends TextView {
        int writes;
        CountingTextView(Activity activity) { super(activity); }
        @Override public void setTypeface(Typeface typeface) {
            writes++;
            super.setTypeface(typeface);
        }
    }
}

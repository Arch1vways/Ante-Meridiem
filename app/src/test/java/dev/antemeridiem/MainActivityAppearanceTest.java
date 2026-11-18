package dev.antemeridiem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;
import android.widget.TextView;
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
@Config(sdk = 35, qualifiers = "ru-w360dp-h800dp-notnight-mdpi")
public class MainActivityAppearanceTest {
    private View findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void checkScreen() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        View view = a.getWindow().getDecorView();
        view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 360, 800);
        assertEquals("Ante Meridiem", a.getString(R.string.app_name));
        assertNotNull(findText(view, "Модуль не активен"));
        assertTrue(findText(view, "Перезапустить «Темы»") == null);
        assertTrue(findText(view, "Перезапустить System UI") == null);
        assertNotNull(findText(view, "Добавить шрифт"));
        assertNotNull(findText(view, "SF Pro"));
        assertTrue(findText(view, "Как выбрать шрифт") == null);
        assertTrue(findText(view, "Шрифты и авторы") == null);
        View name = findText(view, "SF Pro");
        ViewGroup row = (ViewGroup) name.getParent();
        PixelSwitch sw = (PixelSwitch) row.getChildAt(row.getChildCount() - 1);
        assertTrue(sw.isChecked());
        row.performClick();
        assertFalse(new FontStore(a).isShown("lf.sf_pro"));
        new FontStore(a).setShown("lf.sf_pro", true);
        a.finish();
    }

    @Test public void statusUsesReceivedResponse() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        View view = a.getWindow().getDecorView();
        assertNotNull(findText(view, "Модуль не активен"));
        new FontStore(a).markSeen(HookEntry.SYSTEMUI);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofSeconds(3));
        assertNotNull(findText(view, "Модуль активен"));
        a.finish();
    }

    @Test public void lightScreen() { checkScreen(); }
    @Test @Config(sdk = 35, qualifiers = "ru-w360dp-h800dp-night-mdpi")
    public void darkScreen() { checkScreen(); }

    @Test public void sizeControlBelongsOnlyToAnalogAndKeepsItsValue() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup().visible();
        MainActivity activity = controller.get();
        try {
            View screen = activity.getWindow().getDecorView();
            View size = findText(screen, activity.getString(R.string.analog_size));
            assertNotNull(size);
            assertFalse(size.isShown());
            findText(screen, activity.getString(R.string.layout_analog)).performClick();
            assertTrue(size.isShown());
            ViewGroup slider = (ViewGroup) size.getParent().getParent();
            SeekBar bar = (SeekBar) slider.getChildAt(1);
            Bundle value = new Bundle();
            value.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, bar.getMax());
            assertTrue(bar.performAccessibilityAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), value));
            assertEquals(150, new FontStore(activity).analogScale());
            findText(screen, activity.getString(R.string.layout_digital)).performClick();
            assertFalse(size.isShown());
            findText(screen, activity.getString(R.string.layout_analog)).performClick();
            assertTrue(size.isShown());
            assertEquals(bar.getMax(), bar.getProgress());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test public void catalogChangesFromAnotherStoreReachTheOpenActivity() throws Exception {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup().visible();
        MainActivity activity = controller.get();
        try {
            FontStore other = new FontStore(activity.getApplicationContext());
            FontSpec imported = other.importFont(activity.getContentResolver(),
                    Uri.fromFile(BuiltinFontsTest.asset(BuiltinFonts.byId("lf.geist"))));
            other.rename(imported.id, "Imported after resume");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(findText(activity.getWindow().getDecorView(), "Imported after resume"));
        } finally {
            controller.pause().stop().destroy();
        }
    }
}

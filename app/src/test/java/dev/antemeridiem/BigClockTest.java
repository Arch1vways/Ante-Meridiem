package dev.antemeridiem;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;
import android.widget.TextView;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35)
public class BigClockTest {
    @Test public void clockSpansFollowTextAndAreRemovedWithoutTouchingOtherSpans() throws Exception {
        Bitmap[] images = new Bitmap[10];
        for (int i=0; i<10; i++) {
            try (java.io.InputStream in = RuntimeEnvironment.getApplication().getAssets().open("big_clock/" + i + ".png")) {
                images[i] = BitmapFactory.decodeStream(in);
            }
            assertNotNull(images[i]);
        }
        Field f = BigClock.class.getDeclaredField("bitmaps"); f.setAccessible(true); f.set(null, images);
        try {
            TextView v = new TextView(RuntimeEnvironment.getApplication());
            v.setLayoutParams(new android.view.ViewGroup.LayoutParams(900, 300));
            v.setTextSize(100); v.setTextColor(Color.WHITE);
            SpannableString text = new SpannableString("11:39");
            text.setSpan(new ForegroundColorSpan(Color.WHITE), 0, 5, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            v.setText(text); BigClock.apply(v, true);
            Spannable s = (Spannable) v.getText();
            assertEquals("11:39", s.toString());
            assertEquals(4, s.getSpans(0, 5, ReplacementSpan.class).length);
            assertEquals(1, s.getSpans(0, 5, ForegroundColorSpan.class).length);
            v.measure(android.view.View.MeasureSpec.makeMeasureSpec(900, 1073741824), android.view.View.MeasureSpec.makeMeasureSpec(300, 1073741824));
            v.layout(0,0,900,300);
            Bitmap canvas = Bitmap.createBitmap(900, 300, Bitmap.Config.ARGB_8888);
            v.draw(new Canvas(canvas));
            int visible=0, partial=0;
            for (int y=0;y<300;y++) for (int x=0;x<900;x++) {
                int alpha=Color.alpha(canvas.getPixel(x,y));
                if (alpha>0) visible++;
                if (alpha>30 && alpha<225) partial++;
            }
            assertTrue(visible>500); assertTrue(partial>100);
            BigClock.apply(v, false);
            s=(Spannable)v.getText();
            assertEquals("11:39",s.toString());
            assertEquals(0,s.getSpans(0,5,ReplacementSpan.class).length);
            assertEquals(1,s.getSpans(0,5,ForegroundColorSpan.class).length);
        } finally { f.set(null,null); }
    }
}

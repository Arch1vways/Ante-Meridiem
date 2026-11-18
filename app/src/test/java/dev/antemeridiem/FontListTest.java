package dev.antemeridiem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class FontListTest {
    @Test public void providerUsesInstalledApplicationId() {
        assertEquals("arch1.dev.antemeridiem", BuildConfig.APPLICATION_ID);
        assertEquals(BuildConfig.APPLICATION_ID + ".fonts", FontsProvider.AUTHORITY);
        assertEquals(FontsProvider.AUTHORITY, FontsProvider.customUri("lf.c.ab").getAuthority());
    }

    @Test public void roundTrip() {
        List<FontSpec> in = new ArrayList<>();
        in.add(BuiltinFonts.byId("lf.geist"));
        in.add(new FontSpec("lf.c.00112233aabbccdd", "Mine", BuiltinFonts.CUSTOM, null, 200, 800, 300));
        in.add(new FontSpec("lf.c.ffff", "Static", BuiltinFonts.CUSTOM, null, 0, 0, 0));
        List<FontSpec> out = FontList.fromBundle(FontList.toBundle(in));
        assertEquals(3, out.size());
        assertSame(BuiltinFonts.byId("lf.geist"), out.get(0));
        assertEquals("Mine", out.get(1).name);
        assertEquals(200, out.get(1).weightMin);
        assertEquals(800, out.get(1).weightMax);
        assertEquals(300, out.get(1).weightDefault);
        assertEquals(false, out.get(2).supportsWeight());
    }

    @Test public void rejectsGarbage() {
        assertNull(FontList.fromBundle(null));
        assertNull(FontList.fromBundle(new Bundle()));
        Bundle b = FontList.toBundle(new ArrayList<>());
        b.putStringArray(FontList.IDS, new String[]{"../evil"});
        assertNull(FontList.fromBundle(b));   // lengths no longer match
        Bundle c = FontList.toBundle(List.of(new FontSpec("lf.c.ab", "A", BuiltinFonts.CUSTOM, null, 0, 0, 0)));
        c.putStringArray(FontList.IDS, new String[]{"lf.unknown_builtin"});
        assertEquals(0, FontList.fromBundle(c).size());
    }
}

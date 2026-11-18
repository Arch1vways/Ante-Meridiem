package dev.antemeridiem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FontSpecTest {
    @Test public void pseudoPathRoundTrip() {
        FontSpec s = BuiltinFonts.byId("lf.sf_pro");
        assertEquals("/lockfonts/lf.sf_pro", s.pseudoPath());
        assertEquals("lf.sf_pro", FontSpec.idFromPath(s.pseudoPath()));
        assertTrue(FontSpec.isPseudoPath(s.pseudoPath()));
        assertEquals("sf_pro", s.key());
    }

    @Test public void normalPathsAreNotOurs() {
        assertFalse(FontSpec.isPseudoPath("/system/fonts/vivoSansClockVF.ttf"));
        assertFalse(FontSpec.isPseudoPath(null));
        assertFalse(FontSpec.isPseudoPath(42));
        assertNull(FontSpec.idFromPath("/lockfonts/"));
        assertNull(FontSpec.idFromPath("/lockfonts/lf.../../x"));
        assertNull(FontSpec.idFromPath("/lockfonts/lf.a/b"));
        assertNull(FontSpec.idFromPath("/lockfonts/other"));
        assertFalse(FontSpec.isOurId("0"));
        assertFalse(FontSpec.isOurId("origin"));
        assertTrue(FontSpec.isOurId("lf.c.0123456789abcdef"));
    }

    @Test public void weights() {
        FontSpec v = new FontSpec("lf.x", "X", BuiltinFonts.OTHER, "fonts/x.ttf", 100, 900, 2000);
        assertTrue(v.supportsWeight());
        assertEquals(900, v.weightDefault);
        assertEquals("'wght' 500", v.variationSettings(500));
        FontSpec s = new FontSpec("lf.y", "Y", BuiltinFonts.OTHER, "fonts/y.ttf", 0, 0, 0);
        assertFalse(s.supportsWeight());
        assertEquals(FontSpec.STATIC_WEIGHT, s.weightMin);
        assertEquals(FontSpec.STATIC_WEIGHT, s.weightMax);
        assertEquals("", s.variationSettings(500));
    }
}

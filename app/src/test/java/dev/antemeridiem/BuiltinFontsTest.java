package dev.antemeridiem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class BuiltinFontsTest {
    static File asset(FontSpec s) {
        File f = new File("src/main/assets/" + s.asset);
        if (!f.isFile()) f = new File("app/src/main/assets/" + s.asset);
        return f;
    }

    @Test public void catalogIsConsistent() {
        Set<String> ids = new HashSet<>();
        for (FontSpec s : BuiltinFonts.ALL) {
            assertTrue(s.id, FontSpec.isValidId(s.id));
            assertTrue("duplicate " + s.id, ids.add(s.id));
            assertTrue(s.id, BuiltinFonts.categoryOrder(s.category) < BuiltinFonts.CATEGORIES.length - 1);
            assertTrue(s.id, !s.name.isEmpty());
            assertTrue("missing " + s.asset, asset(s).isFile());
            assertTrue(s.id, asset(s).length() < 2 * 1024 * 1024);
        }
        assertEquals(59, BuiltinFonts.ALL.size());
        assertEquals(24, BuiltinFonts.ALL.stream().filter(s -> BuiltinFonts.SAMSUNG.equals(s.category)).count());
    }

    @Test public void removedDuplicatesResolveToSurvivors() {
        assertEquals("lf.samsung_modex", FontResolver.legacyBuiltinId("lf.samsung_modex_mono"));
        assertEquals("lf.samsung_clock_compact", FontResolver.legacyBuiltinId("lf.samsung_clock_h"));
        assertEquals("lf.samsung_one_stretch", FontResolver.legacyBuiltinId("lf.samsung_one_stretch"));
        assertEquals(null, BuiltinFonts.byId("lf.samsung_one_stretch"));
        assertEquals(null, BuiltinFonts.byId("lf.samsung_one_stretch_h"));
    }

    @Test public void weightRangesMatchTheFonts() throws Exception {
        for (FontSpec s : BuiltinFonts.ALL) {
            FontInfo info = FontInfo.read(asset(s));
            assertNotNull(s.id, info.family);
            // a font may have an axis that does nothing for digits (SF Rail Rounded): then it is offered as static
            if (s.supportsWeight()) assertTrue(s.id + " weight axis", info.hasWeightAxis);
            if (s.supportsWeight()) {
                assertTrue(s.id, s.weightMin >= info.weightMin - 0.5f);
                assertTrue(s.id, s.weightMax <= info.weightMax + 0.5f);
                assertTrue(s.id, s.weightDefault >= s.weightMin && s.weightDefault <= s.weightMax);
            }
        }
    }

    @Test public void readsNames() throws Exception {
        assertEquals("Geist", FontInfo.read(asset(BuiltinFonts.byId("lf.geist"))).family);
        FontInfo sf = FontInfo.read(asset(BuiltinFonts.byId("lf.sf_pro")));
        assertEquals("OpenType CFF2", sf.format);
        assertTrue(sf.family, sf.family.startsWith("SF"));
        assertEquals(1f, sf.weightMin, 0.01f);
        assertEquals(1000f, sf.weightMax, 0.01f);
    }
}

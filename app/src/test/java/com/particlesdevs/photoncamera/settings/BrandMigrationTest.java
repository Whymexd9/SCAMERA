package com.particlesdevs.photoncamera.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** P55: stored settings follow the rename tool exactly (brand/vectors.tsv = its old -> new table of the renamed tree). */
public class BrandMigrationTest {
    @Test
    public void everyRenamedWordOfTheToolMapsTheSame() throws Exception {
        InputStream in = getClass().getClassLoader().getResourceAsStream("brand/vectors.tsv");
        int n = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isEmpty()) continue;
                String[] p = line.split("\t");
                assertEquals(p[0], p[1], BrandMigration.word(p[0]));
                n++;
            }
        }
        assertTrue("vectors: " + n, n > 1000);
    }

    @Test
    public void vendorNamesInValuesStay() {
        String tags = "[{\"name\":\"vivo.control.forceSensorMode\",\"value\":\"7\"}]";
        assertEquals(tags, BrandMigration.text(tags));
        assertEquals("libvivo_nice_cre.so", BrandMigration.text("libvivo_nice_cre.so"));
        assertEquals("com.vivo.stats.aec.so", BrandMigration.text("com.vivo.stats.aec.so"));
    }

    @Test
    public void keysAndValuesOfAProfileAreRenamed() {
        Map<String, Object> old = new HashMap<>();
        old.put("pref_lmc_hybrid_kernel", "2");
        old.put("pref_vivo_nice_hybrid_kernel", "1");
        old.put("pref_remosaic_backend", "vivo_neural");
        Set<String> tiles = new HashSet<>();
        tiles.add("pref_lmc_hybrid_denoise");
        tiles.add("pref_flash_mode");
        old.put("pref_shade_tiles", tiles);
        old.put("pref_iso", 400);
        Map<String, Object> m = BrandMigration.map(old);
        assertEquals("2", m.get("pref_scam_hybrid_kernel"));
        assertEquals("1", m.get("pref_scamhdr_hybrid_kernel"));
        assertEquals("scam_neural", m.get("pref_remosaic_backend"));
        assertTrue(((Set<?>) m.get("pref_shade_tiles")).contains("pref_scam_hybrid_denoise"));
        assertTrue(((Set<?>) m.get("pref_shade_tiles")).contains("pref_flash_mode"));
        assertEquals(400, m.get("pref_iso"));
        assertFalse(m.containsKey("pref_lmc_hybrid_kernel"));
    }

    @Test
    public void aNewKeyAlreadyWrittenWinsOverItsOldTwin() {
        Map<String, Object> old = new HashMap<>();
        old.put("pref_lmc_hybrid_kernel", "old");
        old.put("pref_scam_hybrid_kernel", "new");
        assertEquals("new", BrandMigration.map(old).get("pref_scam_hybrid_kernel"));
    }
}

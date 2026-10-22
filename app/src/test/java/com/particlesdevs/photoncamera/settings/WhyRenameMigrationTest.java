package com.particlesdevs.photoncamera.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P319 (owner, 2026-10-22: «Везде где есть "Ark" меняй на "Why"»): the stored settings of the tone keep their values under the
 * new names - the preferences, a module profile or backup, the shade's tiles, the developer file's lines - from a key of
 * before this rename and from one of before P55 alike; a word that only contains the letters (dark, mark) stays; a new key
 * already written wins over its old twin.
 */
public class WhyRenameMigrationTest {
    /** The rename of the stored keys' tone word (what BrandMigration.word adds after the P55 words). */
    static String whyStage(String s) {
        return s.replaceAll("(?<![A-Za-z0-9])ark(?![A-Za-z0-9])", "why");
    }

    @Test public void theOldKeysGetTheNewNames() {
        assertEquals("pref_scam_hybrid_why_tone", BrandMigration.word("pref_scam_hybrid_ark_tone"));
        assertEquals("pref_scam_hybrid_why_sharp_gain", BrandMigration.word("pref_scam_hybrid_ark_sharp_gain"));
        assertEquals("pref_scam_hybrid_why_tone", BrandMigration.word("pref_lmc_hybrid_ark_tone")); // before P55 too
        assertEquals("hybrid_why_hr_mix", BrandMigration.word("hybrid_ark_hr_mix"));
        assertEquals("vf_why", BrandMigration.word("vf_ark"));
        assertEquals("scam_hybrid_nr_why_luma_screen", BrandMigration.word("scam_hybrid_nr_ark_luma_screen"));
    }

    @Test public void theDeveloperFileAndTheLabTakeTheToneWordOnly() {
        // today's names keep their vendor words (the P55 rules would rename them): only the tone's word moves
        assertEquals("vivo_sdk_fullres", BrandMigration.why("vivo_sdk_fullres"));
        assertEquals("module_vivo_qcom_isz_back1", BrandMigration.why("module_vivo_qcom_isz_back1"));
        assertEquals("vf_why", BrandMigration.why("vf_ark"));
        assertEquals("hybrid_why_fusion_mono", BrandMigration.why("hybrid_ark_fusion_mono"));
        Map<String, Object> lab = new HashMap<>();
        lab.put("pref_scam_hybrid_ark_tone", "1");
        lab.put("module_vivo_qcom_isz_back1", 2);
        Map<String, Object> m = BrandMigration.whyMap(lab);
        assertEquals("1", m.get("pref_scam_hybrid_why_tone"));
        assertEquals(2, m.get("module_vivo_qcom_isz_back1"));
    }

    @Test public void wordsThatOnlyContainTheLettersStay() {
        for (String w : new String[]{"pref_dark_mode", "dark", "marker", "pref_watermark", "parked", "DARK_P50", "MARK"})
            assertEquals(w, BrandMigration.word(w));
        assertEquals("shaders/ark/combine", BrandMigration.text("shaders/ark/combine")); // a path stays
    }

    @Test public void aProfileTheTilesAndTheNewKeyFirst() {
        Map<String, Object> old = new HashMap<>();
        old.put("pref_scam_hybrid_ark_metering", "1");
        old.put("pref_scam_hybrid_ark_ccm_sat", "1.2");
        old.put("pref_scam_hybrid_why_ccm_sat", "0.9"); // written by the new version already
        Set<String> tiles = new LinkedHashSet<>();
        tiles.add("pref_scam_hybrid_ark_metering");
        tiles.add("pref_dark_tile");
        old.put("pref_shade_tiles", tiles);
        old.put("pref_shade_order", "pref_scam_hybrid_ark_metering,pref_flash_mode");
        Map<String, Object> m = BrandMigration.map(old);
        assertEquals("1", m.get("pref_scam_hybrid_why_metering"));
        assertEquals("0.9", m.get("pref_scam_hybrid_why_ccm_sat"));
        assertFalse(m.containsKey("pref_scam_hybrid_ark_metering"));
        assertTrue(((Set<?>) m.get("pref_shade_tiles")).contains("pref_scam_hybrid_why_metering"));
        assertTrue(((Set<?>) m.get("pref_shade_tiles")).contains("pref_dark_tile"));
        assertEquals("pref_scam_hybrid_why_metering,pref_flash_mode", m.get("pref_shade_order"));
        // a second pass changes nothing
        assertEquals(m, BrandMigration.map(m));
    }

    /** Every key of today's settings tree with the tone's word is reached from its old spelling. */
    @Test public void everyRenamedKeyOfTheTreeIsReachedFromItsOldName() throws Exception {
        File xml = new File("src/main/res/xml/preferences.xml");
        if (!xml.exists()) xml = new File("app/src/main/res/xml/preferences.xml");
        String s = new String(Files.readAllBytes(xml.toPath()), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("android:key=\"([^\"]+)\"").matcher(s);
        int n = 0;
        while (m.find()) {
            String key = m.group(1);
            if (!key.matches(".*(?<![A-Za-z0-9])why(?![A-Za-z0-9]).*")) continue;
            String old = key.replaceAll("(?<![A-Za-z0-9])why(?![A-Za-z0-9])", "ark");
            assertEquals(old, key, BrandMigration.word(old));
            n++;
        }
        assertTrue("keys with the tone's word: " + n, n > 80);
    }
}

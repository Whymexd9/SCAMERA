package com.particlesdevs.photoncamera.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.particlesdevs.photoncamera.settings.LensProfileMatcher.Lens;

import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class LensProfileMatcherTest {
    /** The summary text follows the UI language (plain JVM: the default locale); restored after each test. */
    private final Locale defaultLocale = Locale.getDefault();

    @After public void restoreLocale() {
        Locale.setDefault(defaultLocale);
    }

    /** vivo X200 Ultra slots (2026-10-01): 2.4x tele (85 mm), 1x main (35 mm), 10x ISZ Tetra and 6.7x ISZ Quad on the tele, 0.4x UW, front. */
    private static final List<Lens> VIVO = Arrays.asList(
            new Lens("back0", false, 85f, 2.4f, false, "2.4×"),
            new Lens("back1", false, 35f, 1f, false, "1×"),
            new Lens("back2", false, 354f, 10f, true, "10х"),
            new Lens("back3", false, 14f, 0.4f, false, "0.4×"),
            new Lens("back4", false, 237f, 6.7f, true, "6.7×"),
            new Lens("front0", true, 21f, 1f, false, "Фронт"));
    /** OPPO Find X8 Ultra: 0.6x UW (15 mm), 1x main (23 mm), 3x (70 mm), 6x periscope (135 mm), front. */
    private static final List<Lens> OPPO = Arrays.asList(
            new Lens("back0", false, 15f, 0.6f, false, "0.6×"),
            new Lens("back1", false, 23f, 1f, false, "1×"),
            new Lens("back2", false, 70f, 3f, false, "3×"),
            new Lens("back3", false, 135f, 6f, false, "6×"),
            new Lens("front0", true, 21f, 1f, false, "Фронт"));

    @Test public void vivoToOppoMapsEveryLensByWhatItIs() {
        Map<String, String> m = LensProfileMatcher.match(VIVO, OPPO);
        assertEquals("back3", m.get("back0"));   // UW <- UW
        assertEquals("back1", m.get("back1"));   // main <- main (35 vs 23 mm, same role)
        assertEquals("back0", m.get("back2"));   // 3x <- 2.4x tele
        assertEquals("back0", m.get("back3"));   // 6x <- the 2.4x tele: a real tele beats the 6.7x ISZ crop slot (role)
        assertEquals("front0", m.get("front0"));
    }

    @Test public void oppoToVivoFeedsOneSourceTeleToSeveralTargets() {
        Map<String, String> m = LensProfileMatcher.match(OPPO, VIVO);
        assertEquals("back2", m.get("back0"));   // 2.4x <- 3x
        assertEquals("back1", m.get("back1"));
        assertEquals("back3", m.get("back2"));   // 10x ISZ <- 6x periscope
        assertEquals("back0", m.get("back3"));
        assertEquals("back3", m.get("back4"));   // 6.7x ISZ <- 6x as well (many-to-one)
        assertEquals("front0", m.get("front0"));
    }

    @Test public void fewerSourceLensesAndNoFrontLeaveTheBaseline() {
        Locale.setDefault(new Locale("ru"));
        fewerSourceLensesAndNoFrontLeaveTheBaseline("Фронт ← общие");
    }

    /** The same on an English system («Фронт» is the module's own label, user data). */
    @Test public void fewerSourceLensesAndNoFrontLeaveTheBaselineInEnglish() {
        Locale.setDefault(Locale.ENGLISH);
        fewerSourceLensesAndNoFrontLeaveTheBaseline("Фронт ← shared");
    }

    private void fewerSourceLensesAndNoFrontLeaveTheBaseline(String unmatchedFront) {
        List<Lens> src = Arrays.asList(new Lens("back0", false, 24f, 1f, false, "1×"));
        Map<String, String> m = LensProfileMatcher.match(src, OPPO);
        for (String slot : new String[]{"back0", "back1", "back2", "back3"}) assertEquals(slot, "back0", m.get(slot));
        assertNull(m.get("front0"));
        String summary = LensProfileMatcher.describe(m, src, OPPO);
        assertTrue(summary, summary.contains(unmatchedFront));
        assertTrue(summary, summary.startsWith("0.6× ← 1×"));
    }

    @Test public void cropOnlyOnTheSourceStillMapsTheTele() {
        // the source's only tele exists as an ISZ crop slot (the plain tele slot is hidden)
        List<Lens> src = Arrays.asList(new Lens("back0", false, 24f, 1f, false, "1×"), new Lens("back1", false, 120f, 5f, true, "5×"));
        Map<String, String> m = LensProfileMatcher.match(src, OPPO);
        assertEquals("back1", m.get("back3"));
        assertEquals("back0", m.get("back1"));
    }

    @Test public void rolesFollowTheMainFocalLength() {
        float main = LensProfileMatcher.mainFocal(VIVO);
        assertEquals(35f, main, 0f);
        assertEquals(LensProfileMatcher.Role.UW, LensProfileMatcher.role(VIVO.get(3), main));
        assertEquals(LensProfileMatcher.Role.TELE, LensProfileMatcher.role(VIVO.get(0), main));
        assertEquals(LensProfileMatcher.Role.CROP, LensProfileMatcher.role(VIVO.get(2), main));
        assertEquals(LensProfileMatcher.Role.FRONT, LensProfileMatcher.role(VIVO.get(5), main));
    }
}

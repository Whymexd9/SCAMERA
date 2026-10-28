package com.particlesdevs.photoncamera.ui.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** P345: when the frozen viewfinder over the resumed camera gives way (FrozenViewfinder, CameraFragment.showFrozenViewfinder). */
public class FrozenViewfinderTest {
    private static String src(String path) throws Exception {
        return new String(Files.readAllBytes(new File("src/main/java/com/particlesdevs/photoncamera/" + path).toPath()),
                StandardCharsets.UTF_8);
    }

    /**
     * Bug viewfinder-ui-freeze-raw-only: a RAW-only session (vivo ISZ 2x / 4x, FULLRES with «Поток ISP» off) never latches an ISP
     * frame - the frozen frame stayed the whole 4 s over the live RAW viewfinder; its first RAW draw ends it now.
     */
    @Test
    public void aRawOnlySessionEndsTheFreezeOnItsFirstRawDraw() {
        assertTrue(FrozenViewfinder.fresh(5, 5, 10, 11));
        assertFalse(FrozenViewfinder.fresh(5, 5, 10, 10));
        assertTrue("an ISP frame as before", FrozenViewfinder.fresh(5, 6, 10, 10));
        assertTrue(FrozenViewfinder.MAX_SHOWN_MS == 4000);
    }

    /** Missed bug: uptimeMillis stops in deep sleep - an hour-old frame looked fresh after the screen was off. */
    @Test
    public void theAgeIsRealTime() throws Exception {
        assertFalse(FrozenViewfinder.tooOld(1_000, 61_000));
        assertTrue(FrozenViewfinder.tooOld(1_000, 61_001));
        final String s = src("ui/camera/CameraFragment.java");
        final int freeze = s.indexOf("private void freezeViewfinder()"), show = s.indexOf("private void showFrozenViewfinder()");
        final int end = s.indexOf("public void onPause()", show);
        assertTrue(freeze > 0 && show > freeze && end > show);
        final String both = s.substring(freeze, end);
        assertTrue(both.contains("frozenVfAtMs = android.os.SystemClock.elapsedRealtime();"));
        assertTrue(both.contains("FrozenViewfinder.tooOld(frozenVfAtMs, android.os.SystemClock.elapsedRealtime())"));
        assertFalse("no uptime for the frame's age", both.contains("uptimeMillis() - frozenVfAtMs"));
        assertTrue("the RAW draws are watched", both.contains("MainRenderer.rawDraws()"));
    }
}

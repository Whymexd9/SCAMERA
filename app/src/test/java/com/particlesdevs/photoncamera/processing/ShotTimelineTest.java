package com.particlesdevs.photoncamera.processing;

import android.app.Application;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

/**
 * P236 (speed-ux B4 + F): the shot timeline's new marks - the native merge's stages from its total line, the summary line with
 * hold / to_worker / merge / gap / post / save and the notes - and marks outside a shot opening no timeline.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ShotTimelineTest {
    private static final String OWNER_TOTAL = "HYBRID MOSAIC NATIVE: total 11935 ms (before the merge 942: pick 0 response 463 binning 478, "
            + "merge 9067, after it 1925)";

    @After public void cleanup() throws Exception {
        set("capturing", null);
        set("processing", null);
    }

    private static void set(String field, Object value) throws Exception {
        Field f = ShotTimeline.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(null, value);
    }

    private static ShotTimeline processing() throws Exception {
        Field f = ShotTimeline.class.getDeclaredField("processing");
        f.setAccessible(true);
        return (ShotTimeline) f.get(null);
    }

    @Test public void theNativeTotalLineGivesTheWorkersStages() {
        assertArrayEquals(new long[]{11935, 942, 0, 463, 478, 9067, 1925}, ShotTimeline.nativeStages(OWNER_TOTAL));
        assertNull(ShotTimeline.nativeStages("HYBRID MOSAIC NATIVE: total 11935 ms"));
        assertNull(ShotTimeline.nativeStages("HYBRID STAGES ms: align=99"));
    }

    @Test public void workerLinesMarkDetectionResponseBinningMergeAndChroma() throws Exception {
        ShotTimeline.attach(null);
        ShotTimeline.mark("job");
        ShotTimeline.workerLine("HYBRID MOSAIC: header block 2, detection skipped (the app's block)");
        ShotTimeline.workerLine("HYBRID MOSAIC NATIVE: chroma median (dual 5-point, taps 1 px of the 8192x6144 result, cpu (size)) 1895 ms");
        ShotTimeline.workerLine(OWNER_TOTAL);
        ShotTimeline.workerLine("SCAM CAPTURE OK");
        final String line = processing().describe();
        for (String m : new String[]{"job=", "w_detect=", "w_response=", "w_binning=", "w_merge=", "w_chroma=", "w_ok="})
            assertTrue(m + " in " + line, line.contains(" " + m));
        // the marks from the total line lie before it: response ends 463 ms after the start, binning at 942, the merge at 10009
        final String summary = processing().summary();
        assertTrue(summary, summary.contains("w_native_ms=pick0/response463/binning478/merge9067/after1925"));
        // time order: the stages back from the total line come before the chroma median's end
        assertTrue(line, line.indexOf("w_binning=") < line.indexOf("w_merge=") && line.indexOf("w_merge=") < line.indexOf("w_chroma="));
    }

    @Test public void theSummaryNamesHoldMergeGapAndPost() throws Exception {
        ShotTimeline.begin("shutter");
        ShotTimeline.capture("series_done");
        ShotTimeline.captureNote("route", "test");
        ShotTimeline.attach(ShotTimeline.detach());
        ShotTimeline.mark("job");
        ShotTimeline.mark("w_ok");
        ShotTimeline.mark("ref_copy");
        ShotTimeline.mark("post_start");
        ShotTimeline.mark("post_done");
        ShotTimeline.mark("saved");
        final String s = processing().summary();
        assertTrue(s, s.startsWith("SHOT SUMMARY ms: hold="));
        for (String k : new String[]{" to_worker=", " merge=", " gap=", " post=", " save=", " total=", " thermal_press=", " vf_fps_press=", " route=test"})
            assertTrue(k + " in " + s, s.contains(k));
        assertFalse("every stage is there", s.contains("=-1"));
    }

    @Test public void aMissingStageIsMinusOneAndPassesOutsideAShotOpenNothing() throws Exception {
        ShotTimeline.markIfShot("post_compile");
        ShotTimeline.note("post_compile_ms", "36/790");
        assertNull("a GL pass outside a shot opens no timeline", processing());
        ShotTimeline.attach(null);
        ShotTimeline.mark("post_start");
        ShotTimeline.markIfShot("post_compile");
        ShotTimeline.note("post_compile_ms", "36/790");
        final String s = processing().summary();
        assertTrue(s, s.contains(" hold=-1") && s.contains(" merge=-1") && s.contains("post_compile_ms=36/790"));
        assertTrue(processing().describe().contains(" post_compile="));
    }
}

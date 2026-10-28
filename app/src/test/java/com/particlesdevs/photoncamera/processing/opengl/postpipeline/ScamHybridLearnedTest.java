package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

/**
 * P377 (owner, 2026-10-28: «всё что работает на адрено на гпу, на мали тоже должно работать на гпу»): the GPU variants P364 kept to
 * Adreno run everywhere; a driver that dies on one (the X200 Pro's Mali-G925 on the own-quad cells) leaves it out for good on that
 * build of the phone and the app - learned from the prewarm before any shot, not paid by a shot at every start.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ScamHybridLearnedTest {
    private static final String CELLS_CRASH = "HYBRID GPU: context Mali-G925-Immortalis MC12 ssbo=2048MB; compiling program by program\n"
            + "HYBRID GPU: compile mean\nHYBRID GPU: compile cells\n"
            + "WORKER CRASH: signal 11 SIGSEGV code=1 addr=0x8 tid=7 thread=mali-cpu-comman stage=GPU compile cells: glCompileShader"
            + " last=GPU compile cells: glCompileShader\nWORKER EXIT: signal 11\n";
    private Context context;

    private static void loaded(boolean value) throws Exception {
        Field f = ScamHybridBurst.class.getDeclaredField("learnedLoaded");
        f.setAccessible(true);
        f.setBoolean(null, value);
    }

    @Before
    public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        ScamHybridBurst.avoidedSteps.clear();
        loaded(false);
    }

    @After
    public void cleanup() throws Exception {
        ScamHybridBurst.avoidedSteps.clear();
        loaded(false);
    }

    @Test
    public void aPrewarmCrashOnASameResultVariantIsKeptForThisBuild() throws Exception {
        assertTrue(ScamHybridBurst.learnFromPrewarm(context, CELLS_CRASH));
        assertTrue(ScamHybridBurst.avoidedTuning().contains("cellsPlain 1\n"));
        // the next start of the app (a new process): read back, before the first tuning text
        ScamHybridBurst.avoidedSteps.clear();
        loaded(false);
        ScamHybridBurst.loadLearned(context);
        assertTrue(ScamHybridBurst.avoidedSteps.contains(ScamHybridBurst.STEP_CELLS));
        // another build (a system update with a new driver, an update of the app): tried again
        ScamHybridBurst.avoidedSteps.clear();
        loaded(false);
        context.getSharedPreferences("scam_gpu_learned", Context.MODE_PRIVATE).edit().putString("build", "older|1").commit();
        ScamHybridBurst.loadLearned(context);
        assertFalse(ScamHybridBurst.avoidedSteps.contains(ScamHybridBurst.STEP_CELLS));
        assertEquals("", ScamHybridBurst.avoidedTuning());
    }

    @Test
    public void aPrewarmCrashElsewhereIsTheShotsToLearn() {
        // a required program: the GPU merge is not turned off by a prewarm (the shot's own worker decides)
        assertFalse(ScamHybridBurst.learnFromPrewarm(context, CELLS_CRASH.replace("compile cells", "compile merge")));
        // a step that changes the photo (the blend itself): not kept, not taken from the prewarm
        assertFalse(ScamHybridBurst.learnFromPrewarm(context, CELLS_CRASH.replace("compile cells", "compile blend")));
        assertTrue(ScamHybridBurst.avoidedSteps.isEmpty());
        assertFalse(ScamHybridBurst.gpuMergeUnusable());
        assertFalse("no crash, nothing learned", ScamHybridBurst.learnFromPrewarm(context, "HYBRID PREWARM: block 1 ok\n"));
        assertNull(ScamHybridBurst.crashStage("HYBRID PREWARM: block 1 ok\n"));
    }
}

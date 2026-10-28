package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Crash site of a dead worker (vivo X200 Pro, Mali-G925, owner's log 2026-10-07: every merge died after
 * "HYBRID GPU: compile flags"): the app leaves the optional step out of the next attempts, or the GPU merge altogether.
 */
public class ScamHybridBurstCrashTest {
    private static final String X200_PRO_REPORT =
            "Failed to load symbol  ntiLegacyMemMgrCreateSCAM WARMUP: CRE ready in 33 ms, joined after 33 ms\n"
            + "Could not open module param file '/sys/module/mali_kbase/parameters/large_page_conf'\n"
            + "SCAM STOCK MOTION frame=1 corners=107 accepted=53 stock replaceReference=0\n"
            + "HYBRID GPU: context Mali-G925-Immortalis MC12 ssbo=2048MB tex=16384 wgY=65535 blocks=35 bindings=70; compiling program by program\n"
            + "HYBRID GPU: compile mean\n"
            + "HYBRID GPU: compile flags\n"
            + "SCAM STOCK MOTION frame=4 corners=107 accepted=56 stock replaceReference=0\n"
            + "WORKER EXIT: signal 11 SIGSEGV, no completion line\n";

    @Test public void lastAnnouncedProgramIsTheCrashSiteWithoutACrashReport() {
        assertEquals("GPU compile flags", ScamHybridBurst.crashStage(X200_PRO_REPORT));
        assertEquals(ScamHybridBurst.STEP_OUTLIERS, ScamHybridBurst.stepOf(ScamHybridBurst.crashStage(X200_PRO_REPORT)));
    }

    @Test public void crashReportStageWins() {
        String report = X200_PRO_REPORT.replace("WORKER EXIT:",
                "WORKER CRASH: signal 11 SIGSEGV code=1 addr=0x0 tid=123 thread=scam-neural-wor stage=CRE track last=GPU compile flags: glCompileShader\n"
                + "WORKER EXIT:");
        assertEquals("CRE track", ScamHybridBurst.crashStage(report));
        assertEquals(ScamHybridBurst.STEP_CRE, ScamHybridBurst.stepOf("CRE track"));
        // a driver thread without a stage: the program being compiled
        String driver = X200_PRO_REPORT.replace("WORKER EXIT:",
                "WORKER CRASH: signal 11 SIGSEGV code=1 addr=0x0 tid=124 thread=mali-cmar stage=- last=GPU compile merge: glLinkProgram\n"
                + "WORKER EXIT:");
        assertEquals("GPU compile merge: glLinkProgram", ScamHybridBurst.crashStage(driver));
        assertEquals(ScamHybridBurst.STEP_GPU, ScamHybridBurst.stepOf("GPU compile merge: glLinkProgram"));
        // no stage at all: nothing to learn (not the GPU compile heuristic either)
        String unknown = X200_PRO_REPORT.replace("WORKER EXIT:",
                "WORKER CRASH: signal 11 SIGSEGV code=1 addr=0x0 tid=125 thread=pool stage=- last=hybrid merge\nWORKER EXIT:");
        assertNull(ScamHybridBurst.crashStage(unknown));
    }

    @Test public void noLearningWhenTheContextWasReadyOrTheWorkerDidNotCrash() {
        String ready = X200_PRO_REPORT.replace("SCAM STOCK MOTION frame=4",
                "HYBRID GPU: Mali-G925-Immortalis MC12 frames=21 limits ssbo=2048MB init ms=900 (compile mean=10)\nSCAM STOCK MOTION frame=4");
        assertNull(ScamHybridBurst.crashStage(ready));
        assertNull(ScamHybridBurst.crashStage(X200_PRO_REPORT.replace("WORKER EXIT: signal 11 SIGSEGV", "WORKER EXIT: code 1")));
        assertNull(ScamHybridBurst.crashStage(null));
    }

    @Test public void stepsOfStages() {
        assertEquals(ScamHybridBurst.STEP_OUTLIERS, ScamHybridBurst.stepOf("GPU compile mean: glCompileShader"));
        assertEquals(ScamHybridBurst.STEP_RIM, ScamHybridBurst.stepOf("GPU compile rim"));
        assertEquals(ScamHybridBurst.STEP_BENTO, ScamHybridBurst.stepOf("GPU compile bento: glLinkProgram"));
        assertEquals(ScamHybridBurst.STEP_BLEND, ScamHybridBurst.stepOf("GPU compile blend: glCompileShader")); // P310
        assertEquals(ScamHybridBurst.STEP_US_FILL, ScamHybridBurst.stepOf("GPU compile mosaicUs")); // P313
        assertEquals(ScamHybridBurst.STEP_US_FILL, ScamHybridBurst.stepOf("GPU compile mosaicUsT: glLinkProgram"));
        assertEquals(ScamHybridBurst.STEP_GPU, ScamHybridBurst.stepOf("GPU compile mosaicFast"));
        assertEquals(ScamHybridBurst.STEP_CHROMA, ScamHybridBurst.stepOf("GPU compile chroma"));
        assertEquals(ScamHybridBurst.STEP_GPU, ScamHybridBurst.stepOf("GPU compile reject"));
        assertEquals(ScamHybridBurst.STEP_GPU, ScamHybridBurst.stepOf("GPU context (EGL)"));
        assertNull(ScamHybridBurst.stepOf("GPU merge"));
        assertNull(ScamHybridBurst.stepOf("hybrid merge"));
    }

    @Test public void avoidedStepsBecomeTuningLines() {
        ScamHybridBurst.avoidedSteps.clear();
        try {
            assertEquals("", ScamHybridBurst.avoidedTuning());
            ScamHybridBurst.avoidedSteps.add(ScamHybridBurst.STEP_OUTLIERS);
            assertEquals("hotSigma 0\nhotBaseSigma 0\n", ScamHybridBurst.avoidedTuning());
            ScamHybridBurst.avoidedSteps.clear();
            ScamHybridBurst.avoidedSteps.add(ScamHybridBurst.STEP_BLEND);
            assertEquals("bentoBlend 0\n", ScamHybridBurst.avoidedTuning()); // P310
            ScamHybridBurst.avoidedSteps.clear();
            ScamHybridBurst.avoidedSteps.add(ScamHybridBurst.STEP_US_FILL);
            assertEquals("mosaicUsFill 0\n", ScamHybridBurst.avoidedTuning()); // P313
        } finally {
            ScamHybridBurst.avoidedSteps.clear();
        }
    }

    /**
     * processing-gpu-init-misclass: Adreno buffers the early GPU context's lines until the merge takes the context, so no early
     * death writes "HYBRID GPU:". A CRE crash or a low-memory kill turned the GPU merge off for the session (every later 12 MP shot
     * the base frame alone) and never reached learnFromCrash; a death at the GPU context still does.
     */
    @Test public void adrenoEarlyDeathIsNotGpuInit() {
        final String adreno = "SCAM WARMUP: CRE ready in 41 ms, joined after 41 ms\n"
                + "HYBRID GPU ALIGN f=1 dx=0.42 dy=-0.13 ms=12\n"
                + "WORKER CRASH: signal 11 SIGSEGV code=1 addr=0x0 tid=1 thread=scam-neural-wor stage=CRE track last=CRE track\n"
                + "WORKER EXIT: signal 11 SIGSEGV, no completion line\n";
        assertFalse(ScamHybridBurst.gpuInitDeath(12_000_000L, adreno));
        assertEquals(ScamHybridBurst.STEP_CRE, ScamHybridBurst.stepOf(ScamHybridBurst.crashStage(adreno)));
        // the merge's own thread, and a GPU alignment thread once the worker names its stage: the conservative retry
        assertFalse(ScamHybridBurst.gpuInitDeath(12_000_000L, adreno.replace("stage=CRE track last=CRE track",
                "stage=hybrid merge last=hybrid merge")));
        assertFalse(ScamHybridBurst.gpuInitDeath(12_000_000L, adreno.replace("tid=1 thread=scam-neural-wor stage=CRE track last=CRE track",
                "tid=9 thread=scam-neural-wor stage=GPU align (F6) last=hybrid merge")));
        // a low-memory kill takes the ordinary retry
        assertFalse(ScamHybridBurst.gpuInitDeath(12_000_000L,
                "HYBRID GPU ALIGN f=1 dx=0.42 dy=-0.13 ms=12\nWORKER EXIT: signal 9 SIGKILL (low memory or killed), no completion line\n"));
        // a crash in the GPU context, or a signal with nothing naming the site: the GPU is unusable as before
        assertTrue(ScamHybridBurst.gpuInitDeath(12_000_000L, adreno.replace("stage=CRE track last=CRE track",
                "stage=GPU context (EGL) last=eglCreateContext")));
        assertTrue(ScamHybridBurst.gpuInitDeath(12_000_000L, "HYBRID GPU ALIGN f=1 dx=0.42 dy=-0.13 ms=12\n"
                + "WORKER EXIT: signal 11 SIGSEGV, no completion line\n"));
        // a stream above 16 MP never; the context's own line (Mali) or no signal: not a GPU-init death
        assertFalse(ScamHybridBurst.gpuInitDeath(50_000_000L, "WORKER EXIT: signal 11 SIGSEGV, no completion line\n"));
        assertFalse(ScamHybridBurst.gpuInitDeath(12_000_000L, X200_PRO_REPORT));
        assertFalse(ScamHybridBurst.gpuInitDeath(12_000_000L, "WORKER EXIT: code 1, no completion line\n"));
        assertFalse(ScamHybridBurst.gpuInitDeath(12_000_000L, null));
    }

    /**
     * Owner's log 2026-10-28 (vivo X200 Pro, Mali-G925): every worker died in the driver's compiler on kHybCells («stage=GPU compile
     * cells: glCompileShader») and the session went to the base frame alone (a pink blown lamp). The next attempt takes the plain
     * build (cellsPlain 1) instead of turning the GPU merge off; only a crash on that one leaves no GPU merge.
     */
    /**
     * P377: the blend's stage programs and tile lists run on every GPU now (Adreno only before); a crash on one leaves only it out -
     * the blend as one program / on every pixel, the same result - never the blend or the GPU merge.
     */
    @Test public void aCrashOnABlendVariantTakesItsSameResultBuild() {
        for (String stage : new String[]{"blend0", "blend1", "blend2", "blend3", "blend4"})
            assertEquals(stage, ScamHybridBurst.STEP_BLEND_STAGES, ScamHybridBurst.stepOf("GPU compile " + stage + ": glCompileShader"));
        assertEquals("the blend program itself", ScamHybridBurst.STEP_BLEND, ScamHybridBurst.stepOf("GPU compile blend: glCompileShader"));
        assertEquals(ScamHybridBurst.STEP_BLEND_LISTS, ScamHybridBurst.stepOf("GPU merge (blend lists)"));
        assertNull(ScamHybridBurst.stepOf("GPU merge"));
        final String report = X200_PRO_REPORT.replace("WORKER EXIT:",
                "WORKER CRASH: signal 11 SIGSEGV code=1 addr=0x8 tid=1 thread=libscamera_work stage=GPU merge (blend lists) last=GPU merge"
                + " (blend lists)\nWORKER EXIT:");
        assertEquals("GPU merge (blend lists)", ScamHybridBurst.crashStage(report));
        assertFalse(ScamHybridBurst.gpuInitDeath(12_000_000L, report));
        // review: the driver runs the indirect dispatches on its own threads (Mali's command threads die with stage=-)
        final String driverThread = X200_PRO_REPORT.replace("WORKER EXIT:",
                "WORKER CRASH: signal 11 SIGSEGV code=1 addr=0x8 tid=9 thread=mali-cmar stage=- last=GPU merge (blend lists)\nWORKER EXIT:");
        assertEquals(ScamHybridBurst.STEP_BLEND_LISTS, ScamHybridBurst.stepOf(ScamHybridBurst.crashStage(driverThread)));
        // a driver thread after another stage still names none
        assertNull(ScamHybridBurst.crashStage(driverThread.replace("last=GPU merge (blend lists)", "last=GPU merge")));
        ScamHybridBurst.avoidedSteps.clear();
        try {
            ScamHybridBurst.avoidedSteps.add(ScamHybridBurst.STEP_BLEND_STAGES);
            assertEquals("blendStages 0\n", ScamHybridBurst.avoidedTuning());
            ScamHybridBurst.avoidedSteps.add(ScamHybridBurst.STEP_BLEND_LISTS);
            assertEquals("blendStages 0\nblendLists 0\n", ScamHybridBurst.avoidedTuning());
            // review: the lab's re-merges take the same-result lines (and only those: a step that changes the photo stays out)
            ScamHybridBurst.avoidedSteps.add(ScamHybridBurst.STEP_RIM);
            assertEquals("blendStages 0\nblendLists 0\n", ScamHybridBurst.sameResultTuning());
        } finally {
            ScamHybridBurst.avoidedSteps.clear();
        }
        assertTrue(ScamHybridBurst.SAME_RESULT_STEPS.contains(ScamHybridBurst.STEP_CELLS));
        assertFalse("a step that changes the photo is not kept for good", ScamHybridBurst.SAME_RESULT_STEPS.contains(ScamHybridBurst.STEP_BLEND));
    }

    @Test public void aCrashOnTheCellsPassTakesThePlainBuild() {
        final String report = X200_PRO_REPORT.replace("WORKER EXIT:",
                "WORKER CRASH: signal 11 SIGSEGV code=1 addr=0x8 tid=28996 thread=libscamera_work stage=GPU compile cells: glCompileShader"
                + " last=GPU compile cells: glCompileShader\nWORKER EXIT:");
        assertEquals("GPU compile cells: glCompileShader", ScamHybridBurst.crashStage(report));
        assertEquals(ScamHybridBurst.STEP_CELLS, ScamHybridBurst.stepOf("GPU compile cells: glCompileShader"));
        assertFalse("not a GPU-init death: the conservative retry runs with the plain build",
                ScamHybridBurst.gpuInitDeath(12_000_000L, report));
        assertEquals(ScamHybridBurst.STEP_GPU, ScamHybridBurst.stepOf("GPU compile cellsPlain: glCompileShader"));
        ScamHybridBurst.avoidedSteps.add(ScamHybridBurst.STEP_CELLS);
        try {
            assertTrue(ScamHybridBurst.avoidedTuning().contains("cellsPlain 1\n"));
        } finally {
            ScamHybridBurst.avoidedSteps.remove(ScamHybridBurst.STEP_CELLS);
        }
    }
}

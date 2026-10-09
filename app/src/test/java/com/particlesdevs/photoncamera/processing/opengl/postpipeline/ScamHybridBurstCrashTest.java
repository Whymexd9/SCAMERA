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
        } finally {
            ScamHybridBurst.avoidedSteps.clear();
        }
    }
}

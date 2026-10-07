package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Crash site of a dead worker (vivo X200 Pro, Mali-G925, owner's log 2026-10-07: every merge died after
 * "HYBRID GPU: compile flags"): the app leaves the optional step out of the next attempts, or the GPU merge altogether.
 */
public class LmcHybridBurstCrashTest {
    private static final String X200_PRO_REPORT =
            "Failed to load symbol  ntiLegacyMemMgrCreateNICE WARMUP: CRE ready in 33 ms, joined after 33 ms\n"
            + "Could not open module param file '/sys/module/mali_kbase/parameters/large_page_conf'\n"
            + "NICE STOCK MOTION frame=1 corners=107 accepted=53 stock replaceReference=0\n"
            + "HYBRID GPU: context Mali-G925-Immortalis MC12 ssbo=2048MB tex=16384 wgY=65535 blocks=35 bindings=70; compiling program by program\n"
            + "HYBRID GPU: compile mean\n"
            + "HYBRID GPU: compile flags\n"
            + "NICE STOCK MOTION frame=4 corners=107 accepted=56 stock replaceReference=0\n"
            + "WORKER EXIT: signal 11 SIGSEGV, no completion line\n";

    @Test public void lastAnnouncedProgramIsTheCrashSiteWithoutACrashReport() {
        assertEquals("GPU compile flags", LmcHybridBurst.crashStage(X200_PRO_REPORT));
        assertEquals(LmcHybridBurst.STEP_OUTLIERS, LmcHybridBurst.stepOf(LmcHybridBurst.crashStage(X200_PRO_REPORT)));
    }

    @Test public void crashReportStageWins() {
        String report = X200_PRO_REPORT.replace("WORKER EXIT:",
                "WORKER CRASH: signal 11 SIGSEGV code=1 addr=0x0 tid=123 thread=vivo-neural-wor stage=CRE track last=GPU compile flags: glCompileShader\n"
                + "WORKER EXIT:");
        assertEquals("CRE track", LmcHybridBurst.crashStage(report));
        assertEquals(LmcHybridBurst.STEP_CRE, LmcHybridBurst.stepOf("CRE track"));
        // a driver thread without a stage: the program being compiled
        String driver = X200_PRO_REPORT.replace("WORKER EXIT:",
                "WORKER CRASH: signal 11 SIGSEGV code=1 addr=0x0 tid=124 thread=mali-cmar stage=- last=GPU compile merge: glLinkProgram\n"
                + "WORKER EXIT:");
        assertEquals("GPU compile merge: glLinkProgram", LmcHybridBurst.crashStage(driver));
        assertEquals(LmcHybridBurst.STEP_GPU, LmcHybridBurst.stepOf("GPU compile merge: glLinkProgram"));
        // no stage at all: nothing to learn (not the GPU compile heuristic either)
        String unknown = X200_PRO_REPORT.replace("WORKER EXIT:",
                "WORKER CRASH: signal 11 SIGSEGV code=1 addr=0x0 tid=125 thread=pool stage=- last=hybrid merge\nWORKER EXIT:");
        assertNull(LmcHybridBurst.crashStage(unknown));
    }

    @Test public void noLearningWhenTheContextWasReadyOrTheWorkerDidNotCrash() {
        String ready = X200_PRO_REPORT.replace("NICE STOCK MOTION frame=4",
                "HYBRID GPU: Mali-G925-Immortalis MC12 frames=21 limits ssbo=2048MB init ms=900 (compile mean=10)\nNICE STOCK MOTION frame=4");
        assertNull(LmcHybridBurst.crashStage(ready));
        assertNull(LmcHybridBurst.crashStage(X200_PRO_REPORT.replace("WORKER EXIT: signal 11 SIGSEGV", "WORKER EXIT: code 1")));
        assertNull(LmcHybridBurst.crashStage(null));
    }

    @Test public void stepsOfStages() {
        assertEquals(LmcHybridBurst.STEP_OUTLIERS, LmcHybridBurst.stepOf("GPU compile mean: glCompileShader"));
        assertEquals(LmcHybridBurst.STEP_RIM, LmcHybridBurst.stepOf("GPU compile rim"));
        assertEquals(LmcHybridBurst.STEP_BENTO, LmcHybridBurst.stepOf("GPU compile bento: glLinkProgram"));
        assertEquals(LmcHybridBurst.STEP_CHROMA, LmcHybridBurst.stepOf("GPU compile chroma"));
        assertEquals(LmcHybridBurst.STEP_GPU, LmcHybridBurst.stepOf("GPU compile reject"));
        assertEquals(LmcHybridBurst.STEP_GPU, LmcHybridBurst.stepOf("GPU context (EGL)"));
        assertNull(LmcHybridBurst.stepOf("GPU merge"));
        assertNull(LmcHybridBurst.stepOf("hybrid merge"));
    }

    @Test public void avoidedStepsBecomeTuningLines() {
        LmcHybridBurst.avoidedSteps.clear();
        try {
            assertEquals("", LmcHybridBurst.avoidedTuning());
            LmcHybridBurst.avoidedSteps.add(LmcHybridBurst.STEP_OUTLIERS);
            assertEquals("hotSigma 0\nhotBaseSigma 0\n", LmcHybridBurst.avoidedTuning());
        } finally {
            LmcHybridBurst.avoidedSteps.clear();
        }
    }
}

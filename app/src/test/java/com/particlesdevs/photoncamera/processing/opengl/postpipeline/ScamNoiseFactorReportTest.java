package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** P77: the noise model factor the worker merged with reaches the finish denoise through its report line. */
public class ScamNoiseFactorReportTest {
    private static final String LINE = "HYBRID NOISE CHECK:";
    private static final String KEY = "-> model x";

    @Test
    public void scaledModelIsRead() {
        String report = "HYBRID MERGE: x\nHYBRID NOISE CHECK: base vs frame 1, 2493 flat of 2852 blocks, measured / model 0.058248"
                + " -> model x0.062500 for every frame\nHYBRID STAGES ms: total=1\n";
        assertEquals(0.0625f, ScamNeuralClient.reportNumber(report, LINE, KEY, 1f), 1e-6f);
    }

    /**
     * P165: the merged DNG's headroom reaches the DNG writer (BaselineExposure log2 of it) through the worker's line, the
     * number exactly as the worker prints it (hybridDngReport); without the line the DNG keeps the N frames' white.
     */
    @Test
    public void mergedDngHeadroomIsRead() {
        String report = "HYBRID BENTO: applied (success) factor=8.000009 frames=3\nHYBRID DNG: merged Bayer 4080x3064, 16-bit,"
                + " headroom=10.1445 (N white at 1/10.145), no denoise\nHYBRID STAGES ms: total=1\n";
        assertEquals(10.1445f, ScamNeuralClient.reportNumber(report, "HYBRID DNG:", "headroom=", 1f), 1e-4f);
        assertEquals(1f, ScamNeuralClient.reportNumber("HYBRID STAGES ms: total=1\n", "HYBRID DNG:", "headroom=", 1f), 0f);
    }

    /** P417: the deep shadows' read factor (HYBRID NOISE CHECK DARK) reaches the dark colour tier; the single factor is not taken for it. */
    @Test
    public void darkReadFactorIsRead() {
        String report = "HYBRID NOISE CHECK: base vs frame 1, 823 flat of 3072 blocks, measured / model 0.537098 -> model x0.537098 for every frame\n"
                + "HYBRID NOISE CHECK DARK: 947 flat blocks at or below 0.002 (median level 0.00114, noise 1.092 x the model) -> the reject's"
                + " read term x6.677 below 0.0020, none from 0.0040\nHYBRID STAGES ms: total=1\n";
        assertEquals(6.677f, ScamNeuralClient.reportNumber(report, "HYBRID NOISE CHECK DARK:", "read term x", 1f), 1e-4f);
        assertEquals(0.537098f, ScamNeuralClient.reportNumber(report, LINE, KEY, 1f), 1e-6f);
        assertEquals(1f, ScamNeuralClient.reportNumber("HYBRID NOISE CHECK DARK: not measured (12 flat blocks at or below 0.002 of the 50"
                + " needed)\n", "HYBRID NOISE CHECK DARK:", "read term x", 1f), 0f);
    }

    @Test
    public void keptModelIsOne() {
        String report = "HYBRID NOISE CHECK: base vs frame 1, 1223 flat of 3072 blocks, measured / model 0.948544 -> model kept\n";
        assertEquals(1f, ScamNeuralClient.reportNumber(report, LINE, KEY, 1f), 0f);
        assertEquals(1f, ScamNeuralClient.reportNumber("no check\n", LINE, KEY, 1f), 0f);
    }
}

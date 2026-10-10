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

    @Test
    public void keptModelIsOne() {
        String report = "HYBRID NOISE CHECK: base vs frame 1, 1223 flat of 3072 blocks, measured / model 0.948544 -> model kept\n";
        assertEquals(1f, ScamNeuralClient.reportNumber(report, LINE, KEY, 1f), 0f);
        assertEquals(1f, ScamNeuralClient.reportNumber("no check\n", LINE, KEY, 1f), 0f);
    }
}

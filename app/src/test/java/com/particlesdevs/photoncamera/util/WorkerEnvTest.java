package com.particlesdevs.photoncamera.util;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

/** P242 (speed-ux B6): the merge worker's environment with and without the normal GPU priority of the A/B. */
public class WorkerEnvTest {
    @Test public void theNormalPriorityIsOneMoreVariable() {
        final String[] low = WorkerEnv.build("/data/app/lib", "/cache/job", false);
        assertEquals("as before P242", Arrays.asList(
                "LD_LIBRARY_PATH=/system/lib64:/system_ext/lib64:/data/app/lib:/cache/job:/vendor/lib64",
                "ADSP_LIBRARY_PATH=/data/app/lib;/cache/job;/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/vendor/dsp;/system/lib/rfsa/adsp",
                "PATH=/system/bin",
                "TMPDIR=/cache/job"), Arrays.asList(low));
        final String[] normal = WorkerEnv.build("/data/app/lib", "/cache/job", true);
        assertEquals(low.length + 1, normal.length);
        assertEquals("SCAM_GPU_NORMAL_PRIORITY=1", normal[normal.length - 1]);
        assertArrayEquals(low, Arrays.copyOf(normal, low.length));
    }
}

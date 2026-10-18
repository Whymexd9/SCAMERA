package com.particlesdevs.photoncamera.capture;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** P212h (A7 = M14 phase 0): the vivo AE audit's cadence, BV and log line (log only). */
public class VivoAeAuditTest {
    @Test
    public void bvIsTheMedianOverTheIso100Exposure() {
        // 0.12 at 10 ms ISO 100: 12 -> 3.585
        assertEquals(Math.log(12) / Math.log(2), VivoAeAudit.bv(0.12f, 10_000_000L, 100), 1e-6);
        // the same scene at 2x the exposure gives 2x the median: the same BV
        assertEquals(VivoAeAudit.bv(0.06f, 10_000_000L, 100), VivoAeAudit.bv(0.12f, 10_000_000L, 200), 1e-6);
        // the X300U tele's 50 ms ISO 3200 at a night median 0.004
        assertEquals(Math.log(0.004 / (0.05 * 32)) / Math.log(2), VivoAeAudit.bv(0.004f, 50_000_000L, 3200), 1e-6);
        assertTrue(Double.isNaN(VivoAeAudit.bv(0f, 10_000_000L, 100)));
        assertTrue(Double.isNaN(VivoAeAudit.bv(0.1f, 0L, 100)));
        assertTrue(Double.isNaN(VivoAeAudit.bv(0.1f, 10_000_000L, 0)));
    }

    @Test
    public void everyEighthFrameIsMeteredAndOneLineASecondIsLogged() {
        VivoAeAudit a = new VivoAeAudit();
        int due = 0;
        for (int i = 0; i < 64; i++) if (a.due()) due++;
        assertEquals(8, due);
        VivoAeAudit b = new VivoAeAudit();
        assertTrue("the first frame", b.due());
        for (int i = 1; i < 8; i++) assertFalse(b.due());
        assertTrue(b.due());

        VivoAeAudit audit = new VivoAeAudit();
        final String first = audit.add("5", "native", 4, 0.012f, 0.4f, 0.6f, 0.001f, true, 33_333_333L, 800, 2, 0f, 10_000L);
        assertNotNull("the first sample is logged at once", first);
        assertTrue(first, first.startsWith("vivo AE audit camera 5 native block 4: p50 0.01200 p98 0.400 P 0.600 C 0.0010 lsc BV "));
        assertTrue(first, first.contains("| HAL 33.333 ms ISO 800 aeState 2 ev +0.00 | 1 samples"));
        assertNull(audit.add("5", "native", 4, 0.024f, 0.4f, 0.6f, 0f, true, 33_333_333L, 800, 2, 0f, 10_300L));
        assertNull(audit.add("5", "native", 4, 0.006f, 0.4f, 0.6f, 0f, true, 33_333_333L, 800, 2, 0f, 10_600L));
        final String next = audit.add("5", "native", 4, 0.012f, 0.4f, 0.6f, 0f, false, 33_333_333L, 800, null, 0.5f, 11_000L);
        assertNotNull(next);
        // 4 samples since the last line... the first went with it: 3 here, median of 0.024, 0.006, 0.012 -> the 0.012 one
        final double bv = VivoAeAudit.bv(0.012f, 33_333_333L, 800);
        assertTrue(next, next.contains("aeState - ev +0.50 | 3 samples, BV median " + String.format(java.util.Locale.ROOT, "%.2f", bv)));
        assertFalse(next, next.contains(" lsc "));
    }

    @Test
    public void theMedianOfTheSecond() {
        assertEquals(2.0, VivoAeAudit.median(new double[]{3, 1, 2}, 3), 0);
        assertEquals(1.5, VivoAeAudit.median(new double[]{2, 1, 9}, 2), 0);
        assertTrue(Double.isNaN(VivoAeAudit.median(new double[4], 0)));
    }
}

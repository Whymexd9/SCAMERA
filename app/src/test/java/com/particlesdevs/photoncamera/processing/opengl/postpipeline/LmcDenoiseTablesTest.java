package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;

import static org.junit.Assert.*;

/** Host arithmetic of the LMC hybrid noise reduction against research/hybrid5/denoise_port.md §2.4 (ArkCam 2.85). */
public class LmcDenoiseTablesTest {
    private static LmcDenoiseTables.Config arkcam() {
        LmcDenoiseTables.Config c = new LmcDenoiseTables.Config();
        c.revertMax = 9f; c.coarseStock = 0f; c.chromaFloor = 0f;   // exact emulation, no safeguards
        return c;
    }

    @Test public void interpolationMatchesTheArkCamTables() {
        LmcDenoiseTables.Pick[] p = new LmcDenoiseTables.Pick[1];
        float[][] l = LmcDenoiseTables.luma(arkcam(), 7.80806f, p);   // "Finish input frame SNR" of the vivo ArkCam dump
        assertEquals(0, p[0].lo); assertEquals(1, p[0].hi);
        assertEquals(1.56f, l[0][0], 0.01f); assertEquals(4.37f, l[0][1], 0.015f); assertEquals(0.82f, l[0][2], 0.01f);
        assertEquals(1.88f, l[1][0], 0.01f); assertEquals(4.34f, l[1][1], 0.015f); assertEquals(0.74f, l[1][2], 0.01f);
        assertEquals(0.11f, l[2][0], 0.01f); assertEquals(0.43f, l[2][1], 0.01f); assertEquals(0.67f, l[2][2], 0.01f);
        float[][] c = LmcDenoiseTables.chroma(arkcam(), 7.80806f, null);
        float[] expected = {2.75f, 2.75f, 2.75f, 1.0f, 1.72f};
        for (int b = 0; b < 5; b++) assertEquals(expected[b], c[b][0], 0.02f);
        // clamped outside the tiers, exact at a key
        assertEquals(9f, LmcDenoiseTables.luma(arkcam(), 1f, null)[0][1], 0f);
        assertEquals(0.95f, LmcDenoiseTables.luma(arkcam(), 500f, null)[0][1], 1e-6f);
        assertEquals(2f, LmcDenoiseTables.luma(arkcam(), 10f, null)[0][0], 1e-6f);
        assertEquals(0.5f, LmcDenoiseTables.chroma(arkcam(), 25f, null)[0][0], 1e-6f);
    }

    @Test public void safeguardsAndMultipliers() {
        LmcDenoiseTables.Config c = new LmcDenoiseTables.Config();       // defaults: ArkCam exactly, no safeguards
        assertEquals(9f, c.revertMax, 0f); assertEquals(0f, c.coarseStock, 0f); assertEquals(0f, c.chromaFloor, 0f);
        c.revertMax = 2f; c.coarseStock = 0.5f; c.chromaFloor = 2.75f;   // the optional safeguards
        float[][] l = LmcDenoiseTables.luma(c, 3f, null);
        assertEquals(2f, l[0][1], 0f);
        assertEquals(2f, l[1][1], 0f);
        assertEquals(0.6f, l[2][0], 1e-6f);                               // b2: half of SabreLow stock 1.2
        assertEquals(0.08f, l[2][1], 1e-6f);
        float[][] ch = LmcDenoiseTables.chroma(c, 25f, null);
        assertEquals(2.75f, ch[0][0], 1e-6f); assertEquals(2.75f, ch[2][0], 1e-6f); assertEquals(0f, ch[3][0], 1e-6f);
        c.chromaMult = 0f;
        assertEquals(0f, LmcDenoiseTables.chroma(c, 25f, null)[0][0], 0f);  // the multiplier also scales the floor
        c = new LmcDenoiseTables.Config();
        c.revertMax = 2f; c.coarseStock = 0.5f; c.chromaFloor = 2.75f;
        c.sabreMult = 0f;                                                 // only the GID14 tiers (SNR 10, 80) keep strength
        assertEquals(0f, LmcDenoiseTables.luma(c, 5f, null)[1][0], 0f);
        assertEquals(1f, LmcDenoiseTables.luma(c, 10f, null)[1][0], 1e-6f);
        // table settings: five values, clamped, chroma outliers integer
        float[] o = com.particlesdevs.photoncamera.settings.SettingsNumericRules.listValue("pref_lmc_hybrid_dn_chroma_t1_outlier", "0, 5.4, 5, 4, 40", null);
        assertArrayEquals(new float[]{0f, 5f, 5f, 4f, 16f}, o, 0f);
        float[] fb = {1f, 3f, 0f, 0f, 0f};
        assertSame(fb, com.particlesdevs.photoncamera.settings.SettingsNumericRules.listValue("pref_lmc_hybrid_dn_luma_t1_strength", "1,3,0", fb));
        assertEquals(9f, com.particlesdevs.photoncamera.settings.SettingsNumericRules.listValue("pref_lmc_hybrid_dn_luma_t1_revert", "12,9,0,0,0", fb)[0], 0f);
        assertEquals(500.0, com.particlesdevs.photoncamera.settings.SettingsNumericRules.value("pref_lmc_hybrid_dn_luma_t3_snr", "900", 20), 0.0);
        assertEquals(9.0, com.particlesdevs.photoncamera.settings.SettingsNumericRules.value("pref_lmc_hybrid_dn_revert_max", "30", 2), 0.0);
        // unsorted keys still bracket correctly
        LmcDenoiseTables.Pick p = LmcDenoiseTables.pick(new float[]{20f, 5f, 10f}, 7.5f);
        assertEquals(1, p.lo); assertEquals(2, p.hi); assertEquals(0.5f, p.t, 1e-6f);
    }

    @Test public void halfDecodingAndStatisticsReduction() {
        assertEquals(1f, LmcDenoiseTables.half(0x3c00), 0f);
        assertEquals(-2f, LmcDenoiseTables.half(0xc000), 0f);
        assertEquals(65504f, LmcDenoiseTables.half(0x7bff), 0f);
        assertEquals(5.9604645e-8f, LmcDenoiseTables.half(0x0001), 1e-12f);
        // 64 flat blocks with g = 0.25 and 16 textured ones with g = 4: the flattest quarter gives 0.25
        int n = 80;
        float[] b = new float[8 * n];
        for (int i = 0; i < n; i++) {
            boolean flat = i < 64;
            float g = flat ? 0.25f : 4f;
            float[] v = {0.05f, flat ? 1f + i * 0.01f : 50f, g, g * 1.1f, 2 * g, 2.2f * g, 4 * g, 4.4f * g};
            System.arraycopy(v, 0, b, 8 * i, 8);
        }
        float[] r = LmcDenoiseTables.reduce(b, n, 0.8f);
        assertNotNull(r);
        assertEquals(0.25f, r[0], 1e-6f); assertEquals(0.275f, r[1], 1e-6f);
        assertEquals((float) Math.sqrt(0.5), r[6], 1e-5f);                // uvs U = sqrt(gY / gU)
        assertEquals(0.5f, r[8], 1e-5f);                                  // uvs V
        assertNull(LmcDenoiseTables.reduce(b, 10, 0.8f));
        // the SNR estimate: mu / sqrt(g01 / 2 * (rho_s s mu + rho_r r))
        assertEquals(0.01f / (float) Math.sqrt(0.125 * (1e-3 * 0.01 + 1e-6)), LmcDenoiseTables.snr(0.01f, 0.25f, 1e-3f, 1e-6f, 1f, 1f), 1e-3f);
    }
}

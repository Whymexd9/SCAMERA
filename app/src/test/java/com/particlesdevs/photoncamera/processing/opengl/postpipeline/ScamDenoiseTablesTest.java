package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;

import static org.junit.Assert.*;

/** Host arithmetic of the SCAM Hybrid noise reduction against research/hybrid5/denoise_port.md §2.4 (ArkCam 2.85). */
public class ScamDenoiseTablesTest {
    private static ScamDenoiseTables.Config arkcam() {
        ScamDenoiseTables.Config c = new ScamDenoiseTables.Config();
        c.revertMax = 9f; c.coarseStock = 0f; c.chromaFloor = 0f;   // exact emulation, no safeguards
        c.lumaStrength[0] = new float[]{1f, 3f, 0f, 0f, 0f};        // ArkCam's SabreLow (P135: GCam stock by default)
        c.lumaRevert[0] = new float[]{9f, 9f, 0.08f, 0.12f, 0f};
        return c;
    }

    @Test public void interpolationMatchesTheArkCamTables() {
        ScamDenoiseTables.Pick[] p = new ScamDenoiseTables.Pick[1];
        float[][] l = ScamDenoiseTables.luma(arkcam(), 7.80806f, p);   // "Finish input frame SNR" of the vivo ArkCam dump
        assertEquals(0, p[0].lo); assertEquals(1, p[0].hi);
        assertEquals(1.56f, l[0][0], 0.01f); assertEquals(4.37f, l[0][1], 0.015f); assertEquals(0.82f, l[0][2], 0.01f);
        assertEquals(1.88f, l[1][0], 0.01f); assertEquals(4.34f, l[1][1], 0.015f); assertEquals(0.74f, l[1][2], 0.01f);
        assertEquals(0.11f, l[2][0], 0.01f); assertEquals(0.43f, l[2][1], 0.01f); assertEquals(0.67f, l[2][2], 0.01f);
        float[][] c = ScamDenoiseTables.chroma(arkcam(), 7.80806f, null);
        float[] expected = {2.75f, 2.75f, 2.75f, 1.0f, 1.72f};
        for (int b = 0; b < 5; b++) assertEquals(expected[b], c[b][0], 0.02f);
        // clamped outside the tiers, exact at a key
        assertEquals(9f, ScamDenoiseTables.luma(arkcam(), 1f, null)[0][1], 0f);
        assertEquals(0.95f, ScamDenoiseTables.luma(arkcam(), 500f, null)[0][1], 1e-6f);
        assertEquals(2f, ScamDenoiseTables.luma(arkcam(), 10f, null)[0][0], 1e-6f);
        assertEquals(0.5f, ScamDenoiseTables.chroma(arkcam(), 25f, null)[0][0], 1e-6f);
    }

    /**
     * P135: a night shot (SCA_20261015_024212: SNR 3.04) keeps its grain but not an abnormal amount: every level denoised with
     * GCam's stock SabreLow strengths, half the fine grain put back, nothing stronger than it was - instead of ArkCam's
     * {1, 3, 0, 0, 0} with revert 9 (the fine grain amplified, the coarse levels untouched).
     */
    @Test public void nightTierNeverAmplifiesTheNoise() {
        ScamDenoiseTables.Config c = new ScamDenoiseTables.Config();
        assertEquals(1f, c.revertMax, 0f);
        for (float snr : new float[]{0.5f, 3.04f, 5f, 7.8f, 10f, 20f, 80f, 300f}) {
            float[][] l = ScamDenoiseTables.luma(c, snr, null);
            for (int b = 0; b < 5; b++) assertTrue("revert " + l[b][1] + " at SNR " + snr, l[b][1] <= 1f);
        }
        float[][] night = ScamDenoiseTables.luma(c, 3.04f, null);
        float[] stock = {1.2f, 1.1f, 1.2f, 1.1f, 1.0f};
        for (int b = 0; b < 5; b++) assertEquals(stock[b], night[b][0], 1e-6f);
        assertEquals(0.6f, night[0][1], 1e-6f);
        assertEquals(0.5f, night[1][1], 1e-6f);
        assertTrue("the grain kept: a share of it put back", night[0][1] > 0.3f);
        // between the night and the GID14 tier (SNR 7.8, ArkCam's dump): revert well under 1 on the fine levels
        float[][] mid = ScamDenoiseTables.luma(c, 7.80806f, null);
        assertTrue(mid[0][1] <= 0.75f && mid[1][1] <= 0.7f);
        assertTrue("coarse levels denoised", mid[2][0] > 0.5f && mid[3][0] > 0.3f);
    }

    @Test public void safeguardsAndMultipliers() {
        ScamDenoiseTables.Config c = new ScamDenoiseTables.Config();       // defaults: ArkCam above SNR 5, revert limit 1
        assertEquals(1f, c.revertMax, 0f); assertEquals(0f, c.coarseStock, 0f); assertEquals(0f, c.chromaFloor, 0f);
        c = arkcam();
        c.revertMax = 2f; c.coarseStock = 0.5f; c.chromaFloor = 2.75f;   // the optional safeguards on ArkCam's tables
        float[][] l = ScamDenoiseTables.luma(c, 3f, null);
        assertEquals(2f, l[0][1], 0f);
        assertEquals(2f, l[1][1], 0f);
        assertEquals(0.6f, l[2][0], 1e-6f);                               // b2: half of SabreLow stock 1.2
        assertEquals(0.08f, l[2][1], 1e-6f);
        float[][] ch = ScamDenoiseTables.chroma(c, 25f, null);
        assertEquals(2.75f, ch[0][0], 1e-6f); assertEquals(2.75f, ch[2][0], 1e-6f); assertEquals(0f, ch[3][0], 1e-6f);
        c.chromaMult = 0f;
        assertEquals(0f, ScamDenoiseTables.chroma(c, 25f, null)[0][0], 0f);  // the multiplier also scales the floor
        c = new ScamDenoiseTables.Config();
        c.revertMax = 2f; c.coarseStock = 0.5f; c.chromaFloor = 2.75f;
        c.sabreMult = 0f;                                                 // only the GID14 tiers (SNR 10, 80) keep strength
        assertEquals(0f, ScamDenoiseTables.luma(c, 5f, null)[1][0], 0f);
        assertEquals(1f, ScamDenoiseTables.luma(c, 10f, null)[1][0], 1e-6f);
        // table settings: five values, clamped, chroma outliers integer
        float[] o = com.particlesdevs.photoncamera.settings.SettingsNumericRules.listValue("pref_scam_hybrid_dn_chroma_t1_outlier", "0, 5.4, 5, 4, 40", null);
        assertArrayEquals(new float[]{0f, 5f, 5f, 4f, 16f}, o, 0f);
        float[] fb = {1f, 3f, 0f, 0f, 0f};
        assertSame(fb, com.particlesdevs.photoncamera.settings.SettingsNumericRules.listValue("pref_scam_hybrid_dn_luma_t1_strength", "1,3,0", fb));
        assertEquals(9f, com.particlesdevs.photoncamera.settings.SettingsNumericRules.listValue("pref_scam_hybrid_dn_luma_t1_revert", "12,9,0,0,0", fb)[0], 0f);
        assertEquals(500.0, com.particlesdevs.photoncamera.settings.SettingsNumericRules.value("pref_scam_hybrid_dn_luma_t3_snr", "900", 20), 0.0);
        assertEquals(9.0, com.particlesdevs.photoncamera.settings.SettingsNumericRules.value("pref_scam_hybrid_dn_revert_max", "30", 2), 0.0);
        // unsorted keys still bracket correctly
        ScamDenoiseTables.Pick p = ScamDenoiseTables.pick(new float[]{20f, 5f, 10f}, 7.5f);
        assertEquals(1, p.lo); assertEquals(2, p.hi); assertEquals(0.5f, p.t, 1e-6f);
    }

    @Test public void halfDecodingAndStatisticsReduction() {
        assertEquals(1f, ScamDenoiseTables.half(0x3c00), 0f);
        assertEquals(-2f, ScamDenoiseTables.half(0xc000), 0f);
        assertEquals(65504f, ScamDenoiseTables.half(0x7bff), 0f);
        assertEquals(5.9604645e-8f, ScamDenoiseTables.half(0x0001), 1e-12f);
        // 64 flat blocks with g = 0.25 and 16 textured ones with g = 4: the flattest quarter gives 0.25
        int n = 80;
        float[] b = new float[8 * n];
        for (int i = 0; i < n; i++) {
            boolean flat = i < 64;
            float g = flat ? 0.25f : 4f;
            float[] v = {0.05f, flat ? 1f + i * 0.01f : 50f, g, g * 1.1f, 2 * g, 2.2f * g, 4 * g, 4.4f * g};
            System.arraycopy(v, 0, b, 8 * i, 8);
        }
        float[] r = ScamDenoiseTables.reduce(b, n, 0.8f);
        assertNotNull(r);
        assertEquals(0.25f, r[0], 1e-6f); assertEquals(0.275f, r[1], 1e-6f);
        assertEquals((float) Math.sqrt(0.5), r[6], 1e-5f);                // uvs U = sqrt(gY / gU)
        assertEquals(0.5f, r[8], 1e-5f);                                  // uvs V
        assertNull(ScamDenoiseTables.reduce(b, 10, 0.8f));
        // the SNR estimate: mu / sqrt(g01 / 2 * (rho_s s mu + rho_r r))
        assertEquals(0.01f / (float) Math.sqrt(0.125 * (1e-3 * 0.01 + 1e-6)), ScamDenoiseTables.snr(0.01f, 0.25f, 1e-3f, 1e-6f, 1f, 1f), 1e-3f);
    }
}

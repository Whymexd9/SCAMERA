package com.particlesdevs.photoncamera.processing.color;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P98: com.oplus.custom.ccm as the OPPO HALs deliver it (SCAM TEST reports, 2026-10-11). */
public class OplusCcmTest {
    private static byte[] hex(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    @Test
    public void parsesTheFindX8UltraMatrix() {
        float[] m = OplusCcm.parse(hex("000000007501d23f9d371abfcdb41cbd468e9fbdf201aa3f302480be2669b93cd0c41bbfc5fcca3f000000000000000000000000"));
        assertArrayEquals(new float[]{1.6407f, -0.6024f, -0.0383f, -0.0779f, 1.3282f, -0.2503f, 0.0227f, -0.6085f, 1.5858f}, m, 1e-3f);
    }

    @Test
    public void parsesTheFindX7UltraMatrixAndSeesItsIdentityTransform() {
        float[] m = OplusCcm.parse(hex("00000000e7b4cd3fd84314bfe6bee4bc5aac27be27a5993f88f315bd70a2743d18671abf798ec53f000000000000000000000000"));
        assertFalse(OplusCcm.identity(m));
        assertTrue(OplusCcm.identity(new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1}));
    }

    @Test
    public void rejectsWhatIsNoMatrix() {
        assertNull(OplusCcm.parse(null));
        assertNull(OplusCcm.parse(new byte[20]));
        assertNull(OplusCcm.parse(new byte[52])); // all zero: rows do not sum to 1
    }
}

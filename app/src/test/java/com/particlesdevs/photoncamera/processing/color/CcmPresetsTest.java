package com.particlesdevs.photoncamera.processing.color;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * P408: the reference app's colour matrix registry (CameraCcmRegistry, read from its dex: 94 profiles, ids 4 .. 97), its Dynamic CCT
 * (applyDynamicCct) and the hand-set matrix's text.
 */
public class CcmPresetsTest {
    @Test public void theRegistryAsTheReference() {
        assertEquals(94, CcmPresets.ALL.length);
        final Set<Integer> ids = new HashSet<>();
        for (CcmPresets.Preset p : CcmPresets.ALL) {
            assertEquals(p.name, 9, p.m.length);
            assertEquals("unique id " + p.id, true, ids.add(p.id));
        }
        assertNull("0 is the system matrix", CcmPresets.matrix(0));
        assertNull(CcmPresets.matrix(1000));
        // spot values (the dex's float arrays)
        assertArrayEquals(new float[]{1.67996f, -0.925398f, 0.14669f, -0.113992f, 1.25587f, -0.0655392f, -0.16176f, -0.558025f, 1.64053f},
                CcmPresets.matrix(29), 1e-5f);
        assertArrayEquals(new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1}, CcmPresets.matrix(8), 0f);
        final float[] a = CcmPresets.matrix(24);
        a[0] = 9f;
        assertEquals("a copy", 1.59753f, CcmPresets.matrix(24)[0], 1e-5f);
    }

    @Test public void dynamicCctAsTheReference() {
        final float[] m = {1.6f, -0.5f, -0.1f, -0.2f, 1.4f, -0.2f, 0.05f, -0.55f, 1.5f};
        assertSame("ISO 400 and below: none", m, CcmPresets.dynamicCct(m, 400, 0.6f));
        assertSame("strength 0: none", m, CcmPresets.dynamicCct(m, 3200, 0f));
        // ISO 1600: t = log16(4) x 0.6 = 0.3; each row towards its sum on the diagonal
        final float t = 0.3f;
        final float[] d = CcmPresets.dynamicCct(m, 1600, 0.6f);
        for (int r = 0; r < 3; r++) {
            final float sum = m[r * 3] + m[r * 3 + 1] + m[r * 3 + 2];
            for (int c = 0; c < 3; c++) assertEquals(m[r * 3 + c] * (1 - t) + (r == c ? sum * t : 0f), d[r * 3 + c], 1e-5f);
            assertEquals("a grey stays grey", sum, d[r * 3] + d[r * 3 + 1] + d[r * 3 + 2], 1e-5f);
        }
        // ISO held at 6400: full strength there and above
        assertArrayEquals(new float[]{1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f}, CcmPresets.dynamicCct(m, 12800, 1f), 1e-5f);
    }

    @Test public void theMatrixText() {
        assertArrayEquals(new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1}, CcmPresets.parse("1, 0, 0, 0, 1, 0, 0, 0, 1"), 0f);
        assertNotNull(CcmPresets.parse("1.5 -0.4 -0.1; -0.2 1.3 -0.1; 0 -0.5 1.5"));
        assertNull(CcmPresets.parse("1, 0, 0"));
        assertNull(CcmPresets.parse("a, b, c, d, e, f, g, h, i"));
        assertNull(CcmPresets.parse(null));
    }
}

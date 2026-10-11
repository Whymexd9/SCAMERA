package com.particlesdevs.photoncamera.processing.color;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * P98 The OPPO ISP's colour matrix of a frame, vendor tag com.oplus.custom.ccm (52 bytes on the Find X7 / X8 / X9 Ultra,
 * SCAM TEST reports 2026-10-11): a 4-byte header (0), nine little-endian floats (the 3x3 matrix by rows, each row summing to
 * 1.000) and three zero floats. On the X8U / X9U it is close to the standard colorCorrection.transform; on the X7U that
 * transform is the identity while this matrix is a real one.
 */
public final class OplusCcm {
    public static final String TAG_NAME = "com.oplus.custom.ccm";

    private OplusCcm() {}

    /** The nine matrix values, or null when the bytes are not such a matrix (size, non-finite, rows not white-preserving). */
    @Nullable
    public static float[] parse(@Nullable byte[] raw) {
        if (raw == null || raw.length < 40) return null;
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        float[] m = new float[9];
        for (int i = 0; i < 9; i++) {
            m[i] = b.getFloat(4 + 4 * i);
            if (Float.isNaN(m[i]) || Float.isInfinite(m[i]) || Math.abs(m[i]) > 8f) return null;
        }
        for (int row = 0; row < 3; row++) {
            float sum = m[row * 3] + m[row * 3 + 1] + m[row * 3 + 2];
            if (sum < 0.85f || sum > 1.15f || m[row * 3 + row] < 0.8f) return null;
        }
        return m;
    }

    /** Whether a transform is the identity (the X7U's reported one), so it says nothing about the scene. */
    public static boolean identity(float[] m) {
        if (m == null || m.length < 9) return false;
        for (int i = 0; i < 9; i++) if (Math.abs(m[i] - (i % 4 == 0 ? 1f : 0f)) > 1e-3f) return false;
        return true;
    }
}

package com.particlesdevs.photoncamera.capture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Bounded, same-colour RAW16 sharpness estimate; never copies a full ZSL frame. */
public final class RawFrameQuality {
    private RawFrameQuality() {}

    public static double score(ByteBuffer source, int width, int height,
                               int rowStride, int pixelStride, int block) {
        if (source == null || width < 32 || height < 32 || pixelStride != 2
                || rowStride < width * 2 || (block != 1 && block != 2 && block != 4))
            return Double.NaN;
        ByteBuffer raw = source.duplicate().slice().order(ByteOrder.LITTLE_ENDIAN);
        if ((long)(height - 1) * rowStride + width * 2L > raw.limit()) return Double.NaN;
        int period = 2 * block;
        double[] gradients = new double[64 * 48];
        int count = 0;
        double signal = 0;
        // Same CFA phase even on quad/tetra sensors; include the whole inner 80%.
        for (int gy = 0; gy < 48; gy++) for (int gx = 0; gx < 64; gx++) {
            int x = (width / 10 + gx * (width * 4 / 5) / 64) / period * period;
            int y = (height / 10 + gy * (height * 4 / 5) / 48) / period * period;
            if (x < period || y < period || x + period >= width || y + period >= height) continue;
            int pos = y * rowStride + x * 2;
            int left = raw.getShort(pos - period * 2) & 65535;
            int right = raw.getShort(pos + period * 2) & 65535;
            int up = raw.getShort(pos - period * rowStride) & 65535;
            int down = raw.getShort(pos + period * rowStride) & 65535;
            gradients[count++] = Math.hypot(right - left, down - up);
            signal += (left + right + up + down) * 0.25;
        }
        if (count < 64 || signal <= 0) return Double.NaN;
        Arrays.sort(gradients, 0, count);
        // Subtract the texture/noise floor: uniform high-ISO noise must not win
        // merely because its gradients are larger. This is not a semantic model.
        double floor = gradients[count / 2] * 2.5;
        double edges = 0;
        for (int i = count * 4 / 5; i < count; i++) edges += Math.max(0, gradients[i] - floor);
        return edges / (count - count * 4 / 5) / (signal / count);
    }
}

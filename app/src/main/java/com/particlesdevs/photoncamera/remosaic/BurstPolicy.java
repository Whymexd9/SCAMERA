package com.particlesdevs.photoncamera.remosaic;

/** Android-independent validation for the closed native implementation. */
public final class BurstPolicy {
    public static final long INPUT_BUDGET = 768L * 1024 * 1024;
    private BurstPolicy() {}
    public static int block(String value) {
        if ("2".equals(value)) return 2;
        if ("4".equals(value)) return 4;
        return 1;
    }
    public static int frameCount(int requested, int width, int height) {
        long pixels = (long) width * height;
        if (width < 164 || height < 164 || width % 2 != 0 || height % 2 != 0
                || pixels > 64_000_000L) throw new IllegalArgumentException("MFSR: неподдерживаемый размер RAW");
        // Input RAW16 + quarter-resolution float guide; reserve output and scratch.
        long perFrame = pixels * 9 / 4;
        int limit = (int)((INPUT_BUDGET - pixels * 4) / perFrame);
        if (limit < 3) throw new IllegalArgumentException("MFSR: недостаточно памяти для серии");
        return Math.max(3, Math.min(Math.min(40, requested), limit));
    }
    public static String cfa(String selected, int sensor) {
        for (String s : new String[]{"RGGB", "GRBG", "GBRG", "BGGR"})
            if (s.equals(selected)) return s;
        if (sensor < 0 || sensor > 3) throw new IllegalArgumentException("MFSR: выберите порядок CFA");
        return new String[]{"RGGB", "GRBG", "GBRG", "BGGR"}[sensor];
    }
}

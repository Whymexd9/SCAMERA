package com.particlesdevs.photoncamera.settings;

import java.math.BigDecimal;

/** Numeric preferences may come from strings, old typed sliders, or JSON backups. */
public final class PreferenceNumber {
    private PreferenceNumber() {}

    public static double read(Object value, double fallback) {
        if (value == null) return fallback;
        if (value instanceof Boolean) return (Boolean) value ? 1 : 0;
        try {
            double number = value instanceof Number ? ((Number) value).doubleValue()
                    : Double.parseDouble(value.toString().trim().replace(',', '.'));
            return Double.isFinite(number) ? number : fallback;
        } catch (NumberFormatException e) { return fallback; }
    }

    public static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value != null && "true".equalsIgnoreCase(value.toString())) return true;
        if (value != null && "false".equalsIgnoreCase(value.toString())) return false;
        return read(value, fallback ? 1 : 0) != 0;
    }

    public static float bounded(Object value, float fallback, float min, float max) {
        return (float) Math.max(min, Math.min(max, read(value, fallback)));
    }

    public static boolean floating(Class<?> type) {
        return type == float.class || type == Float.class || type == double.class || type == Double.class;
    }

    public static int progress(float value, float min, float stepsPerUnit, int max) {
        long progress = Math.round(((double) value - min) * stepsPerUnit);
        return (int) Math.max(0, Math.min(max, progress));
    }

    public static String format(float value, boolean floating) {
        return floating ? new BigDecimal(Float.toString(value)).stripTrailingZeros().toPlainString()
                : Integer.toString(Math.round(value));
    }

    /**
     * Decimals a decimal slider stores on its grid: as many as its step (1 / stepPerUnit) and its minimum need, at least 2
     * (stepPerUnit 10000 -> 4, so 0.0005 is not stored as "0.00"; minimum 0.001 with steps of 0.01 -> 3).
     */
    public static int gridDecimals(float stepPerUnit, float min) {
        int step = stepPerUnit > 1 ? (int) Math.ceil(Math.log10(stepPerUnit) - 1e-9) : 0;
        return Math.max(2, Math.max(step, decimalsOf(Float.toString(min))));
    }

    /** Significant decimals of a number written as text ("1.414" -> 3, "0.50" -> 1, "8" -> 0; 0 when not a number). */
    public static int decimalsOf(String text) {
        try {
            return Math.max(0, new BigDecimal(text.trim().replace(',', '.')).stripTrailingZeros().scale());
        } catch (RuntimeException e) { return 0; }
    }

    /** A slider value rounded to {@code decimals} places, trailing zeros trimmed down to two ("0.0005", "1.414", "0.50"). */
    public static String gridText(double value, int decimals) {
        if (!Double.isFinite(value)) value = 0;
        BigDecimal v = BigDecimal.valueOf(value).setScale(Math.max(2, decimals), java.math.RoundingMode.HALF_UP).stripTrailingZeros();
        if (v.scale() < 2) v = v.setScale(2, java.math.RoundingMode.UNNECESSARY);
        return v.toPlainString();
    }
}

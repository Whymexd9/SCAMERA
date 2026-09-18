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
}

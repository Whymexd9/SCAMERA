package com.particlesdevs.photoncamera.processing.render;

import android.util.Pair;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A DNG noise-model calibration: four Bayer planes in R, Gr, Gb, B order, each with
 *
 * <pre>
 *   S = A * iso + B                        (signal dependent / shot noise)
 *   O = C * iso^2 + D * digitalGain^2      (additive / read noise)
 * </pre>
 *
 * <p>This is the same parameterisation {@link NoiseModeler} already computes; a profile
 * only supplies the coefficients, so selecting one feeds every consumer of
 * {@code noiseModeler.computeModel} at once — the denoise nodes and the alignment
 * significance gate alike.
 *
 * <p>Calibration files produced by the Google noise-model tool are plain C source. They are
 * parsed as <em>data</em>: the four coefficient arrays are matched textually, nothing is
 * compiled or executed.
 */
public final class NoiseModelProfile {

    /** Value of the profile preference meaning "keep using SENSOR_NOISE_PROFILE from Camera2". */
    public static final String AUTO_ID = "auto";

    public final String id;
    public final String name;
    /** Per plane (R, Gr, Gb, B). */
    public final double[] a = new double[4];
    public final double[] b = new double[4];
    public final double[] c = new double[4];
    public final double[] d = new double[4];

    public NoiseModelProfile(String id, String name,
                             double[] a, double[] b, double[] c, double[] d) {
        this.id = id;
        this.name = name;
        System.arraycopy(a, 0, this.a, 0, 4);
        System.arraycopy(b, 0, this.b, 0, 4);
        System.arraycopy(c, 0, this.c, 0, 4);
        System.arraycopy(d, 0, this.d, 0, 4);
    }

    /**
     * Evaluate the profile at a sensitivity, returning one (S, O) pair per plane.
     *
     * @param analogueIso sensitivity at which digital gain starts; the calibration tool uses the
     *                    sensor's maximum analogue ISO for this.
     */
    public Pair<Double, Double>[] evaluate(int iso, int analogueIso) {
        double digitalGain = Math.max((double) iso / Math.max(analogueIso, 1), 1.0);
        @SuppressWarnings("unchecked")
        Pair<Double, Double>[] out = new Pair[4];
        for (int p = 0; p < 4; p++) {
            double s = a[p] * iso + b[p];
            double o = c[p] * (double) iso * iso + d[p] * digitalGain * digitalGain;
            out[p] = new Pair<>(Math.max(s, 0.0), Math.max(o, 0.0));
        }
        return out;
    }

    // ---------------------------------------------------------------- built-ins

    /**
     * IMX363, camera 0 of the LG V50, calibrated by jx chang. Shipped as a reference profile:
     * the numbers are close to the hardcoded fallback in {@link NoiseModeler} but resolve Gr and
     * Gb separately, which the fallback cannot.
     */
    public static final NoiseModelProfile LGV50_IMX363_0 = new NoiseModelProfile(
            "lgv50_imx363_0", "IMX363_0 (LG V50)",
            new double[]{2.691353781189422e-06, 2.6817014313123966e-06,
                    2.682315890739256e-06, 2.695296790679005e-06},
            new double[]{5.607265966668617e-06, 1.0263138748900872e-05,
                    1.0650013817119217e-05, 7.6267061661234736e-06},
            new double[]{2.9917133034145296e-11, 6.086258466440452e-11,
                    6.308190715155536e-11, 3.0739030068625654e-11},
            new double[]{3.275624181535426e-07, 6.624913143068297e-07,
                    6.662466882279819e-07, 3.1510627507887423e-07});

    private static final List<NoiseModelProfile> BUILT_IN = new ArrayList<>();

    static {
        BUILT_IN.add(LGV50_IMX363_0);
    }

    /** Id of the profile loaded from a user-supplied calibration file. */
    public static final String IMPORTED_ID = "imported";
    private static volatile NoiseModelProfile imported;

    public static void setImported(NoiseModelProfile profile) {
        imported = profile;
    }

    public static NoiseModelProfile getImported() {
        return imported;
    }

    public static List<NoiseModelProfile> builtIn() {
        return new ArrayList<>(BUILT_IN);
    }

    public static NoiseModelProfile byId(String id) {
        if (id == null || AUTO_ID.equals(id)) {
            return null;
        }
        if (IMPORTED_ID.equals(id)) {
            return imported;
        }
        for (NoiseModelProfile p : BUILT_IN) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- import

    // Android's ICU regex engine rejects a bare ']' or '}' outside a class, unlike the JDK's,
    // so both closing brackets must be escaped or the class fails to initialise at all.
    private static final Pattern ARRAY = Pattern.compile(
            "noise_model_([ABCD])\\s*\\[\\s*\\]\\s*=\\s*\\{([^}]*)\\}");

    /**
     * Parse a calibration file. Returns null when the four coefficient arrays are not all present,
     * so a wrong file is rejected instead of half-applied.
     */
    public static NoiseModelProfile parse(String source, String id, String name) {
        double[][] found = new double[4][];
        Matcher m = ARRAY.matcher(source);
        while (m.find()) {
            int slot = "ABCD".indexOf(m.group(1).charAt(0));
            String[] parts = m.group(2).split(",");
            if (parts.length != 4) {
                return null;
            }
            double[] values = new double[4];
            for (int i = 0; i < 4; i++) {
                try {
                    values[i] = Double.parseDouble(parts[i].trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            found[slot] = values;
        }
        for (double[] slot : found) {
            if (slot == null) {
                return null;
            }
        }
        return new NoiseModelProfile(id, name, found[0], found[1], found[2], found[3]);
    }

    // ---------------------------------------------------------------- export

    /** Render the profile back into the calibration-file format it was imported from. */
    public String toCalibrationSource() {
        StringBuilder sb = new StringBuilder();
        sb.append("/* SCAMERA noise model export: ").append(name).append(" */\n\n");
        sb.append("double compute_noise_model_entry_S(int plane, int sens) {\n");
        appendArray(sb, "noise_model_A", a);
        appendArray(sb, "noise_model_B", b);
        sb.append("    double s = noise_model_A[plane] * sens + noise_model_B[plane];\n");
        sb.append("    return s < 0.0 ? 0.0 : s;\n}\n\n");
        sb.append("double compute_noise_model_entry_O(int plane, int sens) {\n");
        appendArray(sb, "noise_model_C", c);
        appendArray(sb, "noise_model_D", d);
        sb.append("    double digital_gain = (sens / 800.0) < 1.0 ? 1.0 : (sens / 800.0);\n");
        sb.append("    double o = noise_model_C[plane] * sens * sens"
                + " + noise_model_D[plane] * digital_gain * digital_gain;\n");
        sb.append("    return o < 0.0 ? 0.0 : o;\n}\n");
        return sb.toString();
    }

    private static void appendArray(StringBuilder sb, String name, double[] values) {
        sb.append("    static double ").append(name).append("[] = { ");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.US, "%s", values[i]));
        }
        sb.append(" };\n");
    }

    /** Write the profile to Download/SCAMERA. Returns the file, or null when the write failed. */
    public File exportTo(File directory) {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            return null;
        }
        File target = new File(directory, "SCAMERA-noise-" + id + ".c");
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(toCalibrationSource().getBytes(StandardCharsets.UTF_8));
            return target;
        } catch (Exception e) {
            return null;
        }
    }
}

package com.particlesdevs.photoncamera.processing.parameters;

import java.nio.file.Files;
import java.nio.file.Paths;

/** Compares production arithmetic with original ARM64 execution outputs. */
public final class TetModelCheck {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        int count = 0;
        double worst = 0;
        for (String line : Files.readAllLines(Paths.get(args[0]))) {
            if (line.startsWith("#") || line.isEmpty()) continue;
            String[] fields = line.split(",");
            double[] v = new double[fields.length];
            for (int i = 0; i < fields.length; i++) v[i] = Double.parseDouble(fields[i]);
            double[] actual = TetModel.factorize(v[0], new double[]{1, 10, 40},
                    new double[]{1, 2, 8}, .1, v[2], v[3], v[1] == 0 ? 0 : 1000 / v[1]);
            double error = Math.max(Math.abs(actual[0] - v[4]), Math.abs(actual[1] - v[5]));
            require(error < 2e-5, "Native fixture " + count + " error " + error);
            worst = Math.max(worst, error);
            count++;
        }
        require(count == 192, "Missing native reference cases");
        // Upward snapping must reduce gain, preserving the achieved product.
        double[] pair = TetModel.factorize(45, new double[]{15}, new double[]{3}, .1, 100, 16, 10);
        require(pair[0] == 20 && pair[1] == 2.25, "Nearest snap and gain reduction");
        pair = TetModel.factorize(45, new double[]{15}, new double[]{3}, .1, 17, 16, 10);
        require(pair[0] == 10 && pair[1] == 4.5, "Cap fallback");
        pair = TetModel.factorize(45, new double[]{15}, new double[]{3}, .1, 17, 3, 10);
        require(pair[0] == 15 && pair[1] == 3, "Reject snap above gain limit");
        pair = TetModel.factorize(45, new double[]{15}, new double[]{3}, 12, 17, 16, 10);
        require(pair[0] == 15 && pair[1] == 3, "Reject snap below sensor floor");
        for (double target : new double[]{-1, 0, Double.NaN, Double.POSITIVE_INFINITY, .00001, 1, 45, 1e10}) {
            for (long period : new long[]{0, 8333333, 10000000}) {
                TetModel.Split split = TetModel.solve(target, 100, 3200, 100000, 17000000, period);
                require(split.exposureNs >= 100000 && split.exposureNs <= 17000000,
                        "Sensor shutter range");
                require(split.iso >= 100 && split.iso <= 3200, "Sensor ISO range");
            }
        }
        TetModel.Split low = TetModel.solve(.001, 100, 3200, 100000, 17000000);
        require(low.exposureNs == 100000 && low.iso == 100, "Minimum target");
        boolean rejected = false;
        try { TetModel.solve(1, 0, 3200, 100000, 17000000); }
        catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "Invalid limits");
        System.out.println("TET: " + count + " native cases passed, maximum error " + worst
                + "; snapping, saturation and sensor bounds passed");
    }
}

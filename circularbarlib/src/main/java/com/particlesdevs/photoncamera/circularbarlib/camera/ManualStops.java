package com.particlesdevs.photoncamera.circularbarlib.camera;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/** Standard photographic third stops, bounded by the current camera's actual range. */
public final class ManualStops {
    private ManualStops() {}
    public static List<Long> iso(long min, long max) {
        TreeSet<Long> values = endpoints(min, max);
        for (int octave = -4; octave < 16; octave++) {
            for (double base : new double[]{100, 125, 160}) {
                long value = Math.round(Math.scalb(base, octave));
                if (value >= min && value <= max) values.add(value);
            }
        }
        return new ArrayList<>(values);
    }
    public static List<Long> shutter(long min, long max) {
        TreeSet<Long> values = endpoints(min, max);
        double[] denominators = {32000,25000,20000,16000,12800,10000,8000,6400,5000,4000,3200,2500,2000,1600,1250,1000,800,640,500,400,320,250,200,160,125,100,80,60,50,40,30,25,20,15,13,10,8,6,5,4,3.2,2.5,2};
        for (double d : denominators) add(values, Math.round(1e9 / d), min, max);
        for (double s : new double[]{0.6,0.8,1,1.3,1.6,2,2.5,3.2,4,5,6,8,10,13,16,20,25,32,40,50,64,80,100,128,160,200,256,320,400,512,640,800,1024})
            add(values, Math.round(s * 1e9), min, max);
        return new ArrayList<>(values);
    }
    private static TreeSet<Long> endpoints(long min, long max) {
        if (min <= 0 || max < min) throw new IllegalArgumentException("Invalid manual range");
        TreeSet<Long> values = new TreeSet<>(); values.add(min); values.add(max); return values;
    }
    private static void add(TreeSet<Long> values, long value, long min, long max) {
        if (value >= min && value <= max) values.add(value);
    }
    public static boolean majorIso(long value) {
        double octave = Math.log(value / 100.0) / Math.log(2);
        return Math.abs(octave - Math.rint(octave)) < 0.001;
    }
    public static boolean majorShutter(long value) {
        for (double d : new double[]{32000,16000,8000,4000,2000,1000,500,250,125,60,30,15,8,4,2})
            if (Math.abs(value - Math.round(1e9 / d)) <= 1) return true;
        double octave = Math.log(value / 1e9) / Math.log(2);
        return value >= 1000000000L && Math.abs(octave - Math.rint(octave)) < 0.001;
    }
    public static String shutterLabel(long ns) {
        double seconds = ns / 1e9;
        if (seconds < 0.5) return "1/" + Math.round(1 / seconds);
        return String.format(Locale.ROOT, "%.3f", seconds).replaceAll("0+$", "").replaceAll("\\.$", "") + " s";
    }
}

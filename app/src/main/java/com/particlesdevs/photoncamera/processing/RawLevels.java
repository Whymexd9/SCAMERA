package com.particlesdevs.photoncamera.processing;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;

import java.util.Arrays;
import java.util.Locale;

/**
 * P127: the black and white levels of a RAW stream, by its bit depth.
 * <p>
 * A RAW_SENSOR stream is 8, 10, 12, 14 or 16 bits, its levels pedestal (white + 1) / 16 and white 2^bits - 1: 8 bit 16 / 255,
 * 10 bit 64 / 1023, 12 bit 256 / 4095, 14 bit 1024 / 16383, 16 bit 4096 / 65535. The camera declares one depth (the static white
 * and black), and the stream is not always it:
 * <ul>
 * <li>vivo X300 Ultra DCG (P126): 14-bit samples, white 1023 and black 64 declared, the frames' dynamic black 1030.75 - the same
 * pedestal in units 16 times smaller;</li>
 * <li>vivo X500 Pro Max DCG (P104): 14-bit samples under white 1023 and no dynamic black to tell;</li>
 * <li>Realme GT8 Pro: dynamic black 16 for the static 64 over 10-bit samples (the dynamic black in 8-bit units);</li>
 * <li>OPPO Find X8 Ultra: the black drifting 64 -> ~60, or the pedestal lost (P114): the data floor, {@code Parameters}.</li>
 * </ul>
 * The depth is read from three sources, each in its own units: the declared white; the dynamic black against the static one (a
 * ratio of 4^n is the same pedestal 2n bits wider or narrower, a ratio in between is a drift or junk) and the dynamic white; the
 * samples RawPayloadCheck looks at anyway (values the declared depth cannot hold prove a wider stream, the floor of such a frame
 * tells 12 from 14 bits). Metadata never makes a stream narrower than declared, and a wider reading is dropped when the samples
 * contradict it (their floor under half of its pedestal). The depth proven by the samples only grows within a camera session
 * (two frames agreeing): it does not flap with the scene; a new session (another camera, sensor mode or DCG) starts over.
 * <p>
 * Black and white are then taken at that depth: the dynamic black when it is in the stream's units, else the static black scaled
 * by 2^bits (unless the samples show the pedestal stayed where it was); the dynamic white in the stream's units, else the declared
 * white scaled ((white + 1) * 2^bits - 1). Everything here is arithmetic on metadata and on the samples the payload check has
 * already read: no frame is read again for it.
 * <p>
 * The scaled white of a wider stream is a guess, and the vivo X300 Ultra's DCG 14-bit stream does not reach it: its blown
 * window clipped at 8694 = 1022 + 8 x (1023 - 64), half of 16383 (owner's log 2026-10-15: every channel of the merge at
 * 0.4995). Read as unclipped, the equal raw channels of the blown area turned pink after the white balance. A clip plateau the
 * samples show (RawPayloadCheck: many samples on one top value, few just under it) is the white of a wider stream when it lies
 * under the scaled one ({@link #noteSaturation}, a camera session).
 */
public final class RawLevels {
    public static final int MIN_BITS = 8, MAX_BITS = 16;
    /** The samples alone prove at most 14 bits: values above are junk (packed bytes read as 16-bit), not a 16-bit stream. */
    public static final int MAX_SAMPLED_BITS = 14;
    static final int NO_SCALE = Integer.MIN_VALUE;
    /** Octaves a metadata black may sit off an exact 4^n ratio (X300 Ultra: 1021.75 / 64 = 2^3.997), and a sampled floor. */
    static final double META_TOLERANCE = 0.25, FLOOR_TOLERANCE = 0.35;

    private RawLevels() {}

    /** Canonical pedestal of a depth: 16, 64, 256, 1024, 4096. */
    public static int pedestal(int bits) {
        return 1 << (bits - 4);
    }

    /** Canonical white of a depth: 255, 1023, 4095, 16383, 65535. */
    public static int white(int bits) {
        return (1 << bits) - 1;
    }

    /** The even depth 8..16 whose range holds {@code white} (1023 -> 10, 4000 -> 12, 16383 -> 14), 0 for none (<= 0, > 65535). */
    public static int bitsOfWhite(double white) {
        if (!(white > 0) || white > 65535) return 0;
        for (int b = MIN_BITS; b <= MAX_BITS; b += 2) if (white <= white(b)) return b;
        return 0;
    }

    /**
     * Bits by which {@code value} is {@code reference} in other units: 0 within 25 % (the same units, a drift), +-2 / 4 / 6 / 8 for
     * a ratio of 4^n within {@code tolerance} octaves, {@link #NO_SCALE} otherwise (150 for 64: neither the same pedestal nor
     * another depth's).
     */
    static int scaleSteps(double reference, double value, double tolerance) {
        if (!(reference > 0) || !(value > 0)) return NO_SCALE;
        final double ratio = value / reference;
        if (ratio >= 0.75 && ratio <= 1.25) return 0;
        final double bits = Math.log(ratio) / Math.log(2);
        final int k = 2 * (int) Math.round(bits / 2);
        if (k == 0 || Math.abs(k) > 8 || Math.abs(bits - k) > tolerance) return NO_SCALE;
        return k;
    }

    /** The levels of a frame: black per CFA site (BlackLevelPattern order), white, the stream's depth and where they came from. */
    public static final class Levels {
        public final float[] black;
        public final int white, bits, declaredBits;
        /** The black is the frame's dynamic one (in the stream's units or scaled to them). */
        public final boolean dynamicBlack;
        /** Diagnostics: why this depth, which black and white. */
        public final String source;

        Levels(float[] black, int white, int bits, int declaredBits, boolean dynamicBlack, String source) {
            this.black = black;
            this.white = white;
            this.bits = bits;
            this.declaredBits = declaredBits;
            this.dynamicBlack = dynamicBlack;
            this.source = source;
        }

        public float meanBlack() {
            return (black[0] + black[1] + black[2] + black[3]) / 4f;
        }

        @Override public String toString() {
            return String.format(Locale.ROOT, "%d bit, black %s, white %d (%s)", bits, Arrays.toString(black), white, source);
        }
    }

    /**
     * The levels of one frame.
     *
     * @param staticBlack     the camera's black pattern (4 values; null: the declared depth's pedestal)
     * @param staticWhite     the camera's white (0 or less: 1023)
     * @param dynamicBlack    the frame's dynamic black (4 values) or null
     * @param dynamicWhite    the frame's dynamic white, 0 when none
     * @param sampledBits     the depth the session's samples proved (0: none, see {@link #noteSamples})
     * @param sampledFloor    the latest sampled floor (0.5 % of a frame's samples), negative when not measured
     * @param wide            a stream may be wider than declared (dev switch raw_wide)
     * @param useDynamicBlack the dynamic black also when it only drifts in the declared units (Parameters' setting)
     * @param useDynamicWhite the dynamic white when it is in the stream's units
     */
    public static Levels resolve(float[] staticBlack, int staticWhite, float[] dynamicBlack, int dynamicWhite, int sampledBits,
                                 float sampledFloor, boolean wide, boolean useDynamicBlack, boolean useDynamicWhite) {
        return resolve(staticBlack, staticWhite, dynamicBlack, dynamicWhite, sampledBits, sampledFloor, 0, wide, useDynamicBlack,
                useDynamicWhite);
    }

    /** As above, with {@code saturation}: the clip plateau the session's samples showed (0: none), the white of a wider stream. */
    public static Levels resolve(float[] staticBlack, int staticWhite, float[] dynamicBlack, int dynamicWhite, int sampledBits,
                                 float sampledFloor, int saturation, boolean wide, boolean useDynamicBlack, boolean useDynamicWhite) {
        return resolve(staticBlack, staticWhite, dynamicBlack, dynamicWhite, sampledBits, sampledFloor, saturation, 0, wide,
                useDynamicBlack, useDynamicWhite);
    }

    /**
     * As above, with {@code knownBits}: the depth of the sensor mode the session asked for and the results confirm (P230 Xiaomi 15
     * Ultra DCG: 14 bit under the declared white 1023; 0: none). Not a guess of the samples: it holds with raw_wide 0 too.
     */
    public static Levels resolve(float[] staticBlack, int staticWhite, float[] dynamicBlack, int dynamicWhite, int sampledBits,
                                 float sampledFloor, int saturation, int knownBits, boolean wide, boolean useDynamicBlack,
                                 boolean useDynamicWhite) {
        int declared = bitsOfWhite(staticWhite);
        if (declared == 0) {
            declared = 10;
            staticWhite = 1023;
        }
        final float[] stat = staticBlack != null && staticBlack.length >= 4 ? Arrays.copyOf(staticBlack, 4) : filled(pedestal(declared));
        final float sb = min(stat);
        final float[] dyn = dynamicBlack != null && dynamicBlack.length >= 4 && min(dynamicBlack) >= 0 ? dynamicBlack : null;
        // the units of the dynamic black: against the static black, or the declared pedestal when the static black is 0
        final float ref = sb > 0 ? sb : pedestal(declared);
        int kBlack = NO_SCALE;
        if (dyn != null) kBlack = sb <= 0 && min(dyn) <= 1.25f * ref ? 0 : scaleSteps(ref, min(dyn), META_TOLERANCE);
        final int dynBlackBits = kBlack == NO_SCALE ? 0 : declared + kBlack;
        final int dynWhiteBits = dynamicWhite > 0 ? bitsOfWhite(dynamicWhite) : 0;

        int bits = declared;
        String why = "declared";
        if (knownBits > bits && knownBits <= MAX_BITS) {
            bits = knownBits;
            why = "sensor mode";
        }
        if (wide) {
            if (sampledBits > bits && sampledBits <= MAX_BITS) {
                bits = sampledBits;
                why = "samples";
            }
            final int meta = Math.max(dynBlackBits <= MAX_BITS ? dynBlackBits : 0, dynWhiteBits);
            if (meta > bits) {
                // a wider reading of the metadata the samples contradict: a floor under half of that depth's pedestal
                final float metaPedestal = ref * (1 << (meta - declared));
                if (sampledFloor >= 0 && sampledFloor < 0.5f * metaPedestal) {
                    why += ", " + meta + " bit of the metadata refused (sampled floor " + Math.round(sampledFloor) + ")";
                } else {
                    bits = meta;
                    why = meta == dynBlackBits ? "dynamic black" : "dynamic white";
                }
            }
        }

        float[] black;
        boolean usedDynamic = false;
        if (dyn != null && dynBlackBits == bits && (kBlack != 0 || useDynamicBlack)) {
            black = Arrays.copyOf(dyn, 4); // the frame's own pedestal in the stream's units
            usedDynamic = true;
            why += kBlack != 0 ? ", black dynamic" : ", black dynamic (drift)";
        } else if (dyn != null && dynBlackBits >= MIN_BITS && kBlack != 0 && useDynamicBlack) {
            black = scaled(dyn, bits - dynBlackBits); // Realme GT8 Pro: 16 in 8-bit units -> 64
            usedDynamic = true;
            why += ", black dynamic x" + (1 << Math.max(0, bits - dynBlackBits));
        } else if (bits > declared && sampledFloor >= 0 && sampledFloor < 0.5f * sb * (1 << (bits - declared))) {
            black = stat; // the samples sit under the scaled pedestal: it stayed at the declared black (P104 «black kept»)
            why += ", black static (the samples' floor " + Math.round(sampledFloor) + " under the scaled one)";
        } else {
            black = scaled(stat, bits - declared);
            why += bits > declared ? ", black static x" + (1 << (bits - declared)) : ", black static";
        }

        int white;
        if (useDynamicWhite && dynamicWhite > 0 && dynWhiteBits == bits) {
            white = dynamicWhite;
            why += ", white dynamic";
        } else if (bits == declared) {
            white = staticWhite;
            why += ", white static";
        } else {
            white = (int) Math.min(65535L, ((staticWhite + 1L) << (bits - declared)) - 1);
            why += ", white static x" + (1 << (bits - declared));
            // the scaled white is a guess: the clip the samples measured, when the stream clips under it (X300 Ultra DCG: 8694)
            if (saturation > 0 && saturation < white && saturation > 2f * max(black)) {
                white = saturation;
                why += ", white = measured clip " + saturation;
            }
        }
        if (min(black) >= white) {
            black = filled(0);
            why += ", black above white: 0";
        }
        return new Levels(black, white, bits, declared, usedDynamic, why);
    }

    /** The depth the samples of one frame prove, 0 for none: their range, and 2 bits more when their floor sits on that pedestal. */
    public static int sampledBits(int secondMax, int declaredWhite, float staticBlack, float floor) {
        final int declared = bitsOfWhite(declaredWhite), byMax = bitsOfWhite(secondMax);
        if (declared == 0 || byMax <= declared || byMax > MAX_SAMPLED_BITS) return 0;
        // a frame lit to 12 bits only (vivo DCG at night: samples to ~3000) whose floor is the declared black x 16: 14 bits
        if (byMax + 2 <= MAX_SAMPLED_BITS && staticBlack > 0 && floor > 0
                && declared + scaleSteps(staticBlack, floor, FLOOR_TOLERANCE) == byMax + 2) return byMax + 2;
        return byMax;
    }

    // ---- the session's stream: what the frames of the current camera session told so far ----

    private static final Object LOCK = new Object();
    private static boolean sWide = true;
    private static int sStaticWhite;
    private static float sStaticBlack = -1;
    private static float[] sDynamicBlack;
    private static int sDynamicWhite;
    private static int sSampledBits;
    private static float sSampledFloor = -1;
    /** The clip plateau the samples of this session showed (0: none seen), see {@link #noteSaturation}. */
    private static int sSaturation;
    /** Frames of this session whose samples proved at least 10 / 12 / 14 bits (index (bits - 8) / 2). */
    private static final int[] sVotes = new int[5];
    /** P230: the depth of the session's sensor mode (0: none), see {@link #noteKnownBits}. */
    private static int sKnownBits;

    /** A new camera session: the stream is learnt again. {@code wide}: dev switch raw_wide (a stream may be wider than declared). */
    public static void newSession(boolean wide) {
        synchronized (LOCK) {
            sWide = wide;
            forget();
        }
    }

    public static boolean wideEnabled() {
        synchronized (LOCK) {
            return sWide;
        }
    }

    private static void forget() {
        sStaticWhite = 0;
        sStaticBlack = -1;
        sDynamicBlack = null;
        sDynamicWhite = 0;
        sSampledBits = 0;
        sSampledFloor = -1;
        sSaturation = 0;
        sKnownBits = 0;
        Arrays.fill(sVotes, 0);
    }

    /**
     * Another camera (another declared white) than the session's: what was learnt belongs to that one. A different static black
     * alone (a logical camera and its physical one) only updates it.
     */
    private static void follow(float staticBlack, int staticWhite) {
        if (staticWhite <= 0) return;
        if (sStaticWhite != 0 && sStaticWhite != staticWhite) forget();
        sStaticWhite = staticWhite;
        if (staticBlack >= 0) sStaticBlack = staticBlack;
    }

    /** The camera whose frames are checked (its declared levels; null / 0: unknown). */
    public static void noteCamera(float[] staticBlack, int staticWhite) {
        synchronized (LOCK) {
            follow(staticBlack == null || staticBlack.length < 4 ? -1 : min(staticBlack), staticWhite);
        }
    }

    /** A frame's metadata: the camera's declared levels and the frame's dynamic ones (null / 0: none, the last ones stay). */
    public static void noteMetadata(float[] staticBlack, int staticWhite, float[] dynamicBlack, int dynamicWhite) {
        synchronized (LOCK) {
            final int before = sessionCamera(staticBlack, staticWhite) ? resolveLocked(staticBlack, staticWhite).bits : 0;
            follow(staticBlack == null || staticBlack.length < 4 ? -1 : min(staticBlack), staticWhite);
            if (dynamicBlack != null && dynamicBlack.length >= 4) sDynamicBlack = Arrays.copyOf(dynamicBlack, 4);
            if (dynamicWhite > 0) sDynamicWhite = dynamicWhite;
            if (before == 0) return;
            final Levels now = resolveLocked(staticBlack, staticWhite);
            if (now.bits != before) com.particlesdevs.photoncamera.util.Log.i("SCAM_CAPTURE", "RAW levels: " + before + " bit -> " + now);
        }
    }

    /**
     * RawPayloadCheck: one plain frame's samples - {@code bits} they prove ({@link #sampledBits}, 0: within the declared white) and
     * their floor (negative: not measured). Two frames of the session proving a depth make it the stream's.
     */
    static void noteSamples(int declaredWhite, int bits, float floor) {
        synchronized (LOCK) {
            if (!sWide) return;
            if (floor >= 0) sSampledFloor = floor;
            for (int b = 10; b <= Math.min(bits, MAX_SAMPLED_BITS); b += 2) sVotes[(b - MIN_BITS) / 2]++;
            int proven = sSampledBits;
            for (int b = MAX_SAMPLED_BITS; b > Math.max(proven, MIN_BITS); b -= 2) if (sVotes[(b - MIN_BITS) / 2] >= 2) { proven = b; break; }
            if (proven > sSampledBits && proven > bitsOfWhite(declaredWhite)) {
                com.particlesdevs.photoncamera.util.Log.i("SCAM_CAPTURE", "RAW samples prove " + proven + " bit under the declared white "
                        + declaredWhite + (floor >= 0 ? " (floor " + Math.round(floor) + ")" : ""));
                sSampledBits = proven;
            }
        }
    }

    /**
     * RawPayloadCheck: a frame of a stream wider than declared clipped on {@code value} (a plateau of its samples). The highest
     * plateau of the session is kept: a clip does not move, a lower one would be a flat content level.
     */
    static void noteSaturation(int value) {
        synchronized (LOCK) {
            if (!sWide || value <= sSaturation) return;
            com.particlesdevs.photoncamera.util.Log.i("SCAM_CAPTURE", "RAW clip plateau at " + value
                    + (sSaturation > 0 ? " (was " + sSaturation + ")" : "") + ": the white of the wider stream");
            sSaturation = value;
        }
    }

    /**
     * P230: the session runs a sensor mode of {@code bits} (Xiaomi 15 Ultra DCG: 14) on the camera of {@code staticBlack} /
     * {@code staticWhite}; 0 takes it back (the results report another mode). Its frames resolve at that depth at once, and
     * RawPayloadCheck takes its white ({@link #knownWhite}): a 14-bit sample is no impossible value. Logged on change.
     */
    public static void noteKnownBits(float[] staticBlack, int staticWhite, int bits, String why) {
        synchronized (LOCK) {
            follow(staticBlack == null || staticBlack.length < 4 ? -1 : min(staticBlack), staticWhite);
            final int b = bits >= MIN_BITS && bits <= MAX_BITS && bits % 2 == 0 ? bits : 0;
            if (b == sKnownBits) return;
            com.particlesdevs.photoncamera.util.Log.i("SCAM_CAPTURE", "RAW levels: sensor mode depth " + (b == 0 ? "none" : b + " bit")
                    + " (" + why + ", was " + (sKnownBits == 0 ? "none" : sKnownBits + " bit") + ")");
            sKnownBits = b;
        }
    }

    public static int knownBits() {
        synchronized (LOCK) {
            return sKnownBits;
        }
    }

    /** The white of the session's known depth, 0 without one. */
    public static int knownWhite() {
        synchronized (LOCK) {
            return sKnownBits > 0 ? white(sKnownBits) : 0;
        }
    }

    public static int sampledSaturation() {
        synchronized (LOCK) {
            return sSaturation;
        }
    }

    /** The metadata of the session says the stream is wider than declared: RawPayloadCheck measures the floor to check it. */
    static boolean metadataWider() {
        synchronized (LOCK) {
            if (!sWide || sStaticWhite <= 0) return false;
            final int declared = bitsOfWhite(sStaticWhite);
            final float ref = sStaticBlack > 0 ? sStaticBlack : pedestal(declared);
            final int k = sDynamicBlack == null ? NO_SCALE : scaleSteps(ref, min(sDynamicBlack), META_TOLERANCE);
            return (k != NO_SCALE && k > 0) || (sDynamicWhite > 0 && bitsOfWhite(sDynamicWhite) > declared);
        }
    }

    /** The session camera's smallest static black (negative: not known yet). */
    static float staticBlack() {
        synchronized (LOCK) {
            return sStaticBlack;
        }
    }

    public static int sampledBits() {
        synchronized (LOCK) {
            return sSampledBits;
        }
    }

    public static float sampledFloor() {
        synchronized (LOCK) {
            return sSampledFloor;
        }
    }

    private static Levels resolveLocked(float[] staticBlack, int staticWhite) {
        return resolve(staticBlack, staticWhite, sDynamicBlack, sDynamicWhite, sSampledBits, sSampledFloor, sSaturation, sKnownBits, sWide,
                true, true);
    }

    /**
     * The levels of a shot's frame (Parameters): its own metadata, the samples of the session when the frame is of the session's
     * camera (same declared levels), the photo settings for the dynamic black and white.
     */
    public static Levels forFrame(float[] staticBlack, int staticWhite, float[] dynamicBlack, int dynamicWhite,
                                  boolean useDynamicBlack, boolean useDynamicWhite) {
        synchronized (LOCK) {
            final boolean session = sessionCamera(staticBlack, staticWhite);
            return resolve(staticBlack, staticWhite, dynamicBlack, dynamicWhite, session ? sSampledBits : 0,
                    session ? sSampledFloor : -1f, session ? sSaturation : 0, session ? sKnownBits : 0, sWide, useDynamicBlack,
                    useDynamicWhite);
        }
    }

    private static boolean sessionCamera(float[] staticBlack, int staticWhite) {
        return sStaticWhite != 0 && sStaticWhite == staticWhite;
    }

    /**
     * The session's levels for a frame of camera {@code c}: with the frame's own dynamic levels when {@code result} is given,
     * else the newest ones noted for that camera. For the viewfinder, the motion level, the clip estimate: the dynamic black is
     * taken when it is in the stream's units (or only drifts), the dynamic white when it is.
     */
    public static Levels current(CameraCharacteristics c, CaptureResult result) {
        final float[] stat = staticBlack(c);
        final int white = staticWhite(c);
        if (result != null) {
            final float[] dyn = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
            final Integer dw = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL);
            noteMetadata(stat, white, dyn, dw == null ? 0 : dw);
        }
        synchronized (LOCK) {
            if (sessionCamera(stat, white)) return resolveLocked(stat, white);
            return resolve(stat, white, null, 0, 0, -1f, sWide, true, true);
        }
    }

    /** The static black pattern of a camera (4 values, BlackLevelPattern index order), null when it has none. */
    public static float[] staticBlack(CameraCharacteristics c) {
        final android.hardware.camera2.params.BlackLevelPattern pattern = c == null ? null
                : c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
        if (pattern == null) return null;
        final float[] out = new float[4];
        for (int i = 0; i < 4; i++) out[i] = pattern.getOffsetForIndex(i % 2, i / 2);
        return out;
    }

    /** The static white of a camera, 0 when it has none. */
    public static int staticWhite(CameraCharacteristics c) {
        final Integer w = c == null ? null : c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
        return w == null ? 0 : w;
    }

    static float max(float[] v) {
        float m = -Float.MAX_VALUE;
        for (int i = 0; i < Math.min(4, v.length); i++) m = Math.max(m, v[i]);
        return m;
    }

    static float min(float[] v) {
        float m = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(4, v.length); i++) m = Math.min(m, v[i]);
        return m;
    }

    private static float[] filled(float v) {
        final float[] out = new float[4];
        Arrays.fill(out, v);
        return out;
    }

    private static float[] scaled(float[] v, int bits) {
        final float[] out = Arrays.copyOf(v, 4);
        final float k = bits >= 0 ? (1 << bits) : 1f / (1 << -bits);
        for (int i = 0; i < 4; i++) out[i] = Math.min(65535f, out[i] * k);
        return out;
    }
}

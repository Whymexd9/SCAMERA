package com.particlesdevs.photoncamera.processing.color;

import android.hardware.camera2.CaptureResult;

import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * P370 (B6): the white balance of a mosaic RAW viewfinder whose ISP runs no 3A (the X200 Ultra's ISZ modules under the vivo SDK
 * lease: the identity transform, gains frozen at R 2.434 / B 1.569 - MosaicNeutralResolver.ispIdle). The viewfinder showed those
 * frozen gains while the photo measures its own neutral (ScamProcessor.applyMosaicNeutral: the teacher's neutral blended with the
 * locus, else the P121 estimate on the camera's curve). Now, while the last results say idle: a measurement every
 * {@link #EVERY}th frame resolved the photo's way, smoothed in ln space with a {@link #DEAD_BAND} ln dead band; the viewfinder's
 * gains are 1 / that neutral (A2's table is looked up at it). Never the press or the photo's neutral. scam_dev.txt
 * "vf_mosaic_wb 0": the HAL's gains as before. One instance per CaptureController (its callback thread).
 */
public final class ViewfinderMosaicWb {
    /** A measurement every this many frames (8 - 16: the statistic is a 256-cell grid, cheap, but the frame is open). */
    static final int EVERY = 12;
    /** A neutral within this (ln r, ln b) of the shown one does not move it; a larger step moves it by {@link #STEP}. */
    static final double DEAD_BAND = 0.01, STEP = 0.5;
    /** Results kept for the idle test (MosaicNeutralResolver.ispIdle: at least 4 over 0.3 s of sensor time). */
    static final int KEEP = 24;
    static final long KEEP_NS = 1_000_000_000L;
    /** The lease's HAL gains are logged this often (C3: do they ever move?). */
    static final long LOG_MS = 5000;

    /** What a measurement of the frame gives (each asked for when needed): the photo's statistic and its P121 estimate (null: none). */
    public interface Measure {
        @Nullable MosaicNeutral.Stat stat();
        @Nullable MosaicNeutral.Result own();
        /** The result's neutral (R/G, 1, B/G), null: none. */
        @Nullable float[] hal();
    }

    /** What the idle test reads of the last results (P370 review: a sample each, not a second of CaptureResults). */
    private final ArrayDeque<MosaicNeutralResolver.IdleSample> recent = new ArrayDeque<>();
    private String camera;
    private int session = Integer.MIN_VALUE;
    private int frames;
    @Nullable private String idle;
    @Nullable private float[] shown;
    private long loggedMs = Long.MIN_VALUE;

    public static boolean on() {
        try {
            return com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevSwitch("vf_mosaic_wb", true);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Forget the shown neutral (a new camera or session, a plain stream, the ISP running again). */
    public synchronized void reset() {
        recent.clear();
        frames = 0;
        idle = null;
        shown = null;
    }

    /**
     * One viewfinder frame of camera {@code id} in session {@code sessionId}: its result, whether its stream is a mosaic, the
     * measurement (asked only when due) and the resolver's inputs (prior, teacher's same-sensor cameras). Returns the neutral
     * {R/G, 1, B/G} the viewfinder shows, null for the HAL's gains.
     */
    @Nullable
    public synchronized float[] frame(String id, int sessionId, @Nullable CaptureResult result, boolean mosaic, Measure measure,
                                      @Nullable MosaicNeutral.Prior prior, @Nullable java.util.List<String> sameSensor, long nowMs) {
        if (!mosaic || result == null || id == null) {
            if (shown != null || !recent.isEmpty()) reset();
            return null;
        }
        if (!id.equals(camera) || sessionId != session) {
            reset();
            camera = id;
            session = sessionId;
        }
        final MosaicNeutralResolver.IdleSample sample = MosaicNeutralResolver.IdleSample.of(result);
        recent.addLast(sample);
        final Long ts = sample.timestamp();
        while (recent.size() > KEEP) recent.removeFirst();
        if (ts != null) {
            // only the last second of sensor time: an older (running) ISP's result never holds the idle test back
            while (!recent.isEmpty()) {
                final Long first = recent.peekFirst().timestamp();
                if (first != null && ts - first <= KEEP_NS) break;
                recent.removeFirst();
            }
        }
        if (frames++ % EVERY != 0) return shown;
        idle = MosaicNeutralResolver.ispIdleOf(recent);
        if (nowMs - loggedMs >= LOG_MS) {
            loggedMs = nowMs;
            final android.hardware.camera2.params.RggbChannelVector g = result.get(CaptureResult.COLOR_CORRECTION_GAINS);
            com.particlesdevs.photoncamera.util.Log.i("SCAM_CAPTURE", String.format(Locale.ROOT,
                    "mosaic viewfinder camera %s: HAL gains %s, %s, shown neutral %s", id,
                    g == null ? "none" : String.format(Locale.ROOT, "R %.4f / G %.4f / B %.4f", g.getRed(), 0.5f * (g.getGreenEven() + g.getGreenOdd()), g.getBlue()),
                    idle == null ? "the ISP may run its 3A" : idle, shown == null ? "the HAL's" : String.format(Locale.ROOT, "%.3f / %.3f", shown[0], shown[2])));
        }
        if (idle == null) {
            shown = null;
            return null;
        }
        final float[] target = resolve(id, measure, prior, sameSensor);
        if (target != null) shown = smooth(shown, target);
        return shown;
    }

    /**
     * The photo's neutral for a frame without a route in an idle ISP session (ScamProcessor.applyMosaicNeutral): the teacher's neutral
     * blended with the locus when it has learned enough (MosaicTeacher.use, MosaicNeutral.taught; scam_dev "mosaic_awb_teacher_use
     * 0": never), else the P121 estimate on the curve; null for neither.
     */
    @Nullable
    static float[] resolve(String id, Measure measure, @Nullable MosaicNeutral.Prior prior, @Nullable java.util.List<String> sameSensor) {
        final MosaicNeutral.Stat stat = measure.stat();
        if (stat != null && MosaicTeacher.on() && MosaicTeacher.useMode() != MosaicTeacher.USE_NEVER) {
            final MosaicTeacher.Use teacher = MosaicTeacher.use(id, stat.r, stat.b, sameSensor);
            if (teacher != null && teacher.usable()) return MosaicNeutral.taught(id, stat, prior, teacher).neutral.clone();
        }
        final MosaicNeutral.Result own = measure.own();
        if (own == null) return null;
        // P315 (the photo's rule): an estimate far off every learned illuminant is not taken as one
        final float[] hal = measure.hal();
        if (hal != null && MosaicNeutralResolver.offLocus(id, own.learnedDistance, hal, prior) != null) return null;
        return own.neutral.clone();
    }

    /** {@code shown} moved toward {@code target} in ln space: not within DEAD_BAND, by STEP of the way beyond it; green 1. */
    static float[] smooth(@Nullable float[] shown, float[] target) {
        final double tr = Math.log(target[0] / target[1]), tb = Math.log(target[2] / target[1]);
        if (shown == null) return new float[]{(float) Math.exp(tr), 1f, (float) Math.exp(tb)};
        final double sr = Math.log(shown[0] / shown[1]), sb = Math.log(shown[2] / shown[1]);
        final double dr = tr - sr, db = tb - sb;
        if (Math.hypot(dr, db) <= DEAD_BAND) return shown;
        return new float[]{(float) Math.exp(sr + STEP * dr), 1f, (float) Math.exp(sb + STEP * db)};
    }

    /** The viewfinder's white-balance gains {R, G, B} of a neutral (green 1). */
    public static float[] gains(float[] neutral) {
        return new float[]{neutral[1] / neutral[0], 1f, neutral[1] / neutral[2]};
    }
}

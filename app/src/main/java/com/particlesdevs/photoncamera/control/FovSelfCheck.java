package com.particlesdevs.photoncamera.control;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import com.particlesdevs.photoncamera.control.subject.SubjectFrames;
import com.particlesdevs.photoncamera.util.Log;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P60: a module with vendor requests measures its own field of view against its plain sibling on the same Camera ID, so a
 * stream the sensor already cropped (ISZ through a vendor tag whose HAL remosaics in the ISP: a Bayer stream, block 1, that
 * {@link ZoomController#streamCropFor} cannot see) is not cropped a second time (OPPO Find X9 Ultra, 2026-10-08).
 *
 * <p>While a plain module (no vendor requests) is on screen, a 64 px gray copy of the viewfinder is kept every 0.5 s as the
 * reference of its camera. After a vendor module of the same camera opens, its first frames are compared with the
 * reference taken just before the switch: the scale s between the two views (normalised cross-correlation over a log grid
 * of scales and small shifts) against the scale the zoom expects tells the crop the stream really holds:
 * {@code c = s * r0 / r1} (r0 / r1: the residual crops applied on screen to the reference and to the module). Two
 * consistent measurements with a clear peak are required; the result is applied ({@link ZoomController#setStreamCrop}),
 * logged ("module X: measured field 1/2.00 of camera Y") and cached per module signature, so later sessions start with it.
 * A scene that changed too much (no clear peak) leaves everything as it was.
 */
public final class FovSelfCheck implements SubjectFrames.Sink {
    private static final String TAG = "FovSelfCheck";
    static final int W = 64;
    static final float MIN_NCC = 0.8f, PEAK_RATIO = 3f, AGREE = 0.03f;
    private static final long REF_PERIOD_MS = 500, REF_MAX_AGE_MS = 8000, SETTLE_MS = 700, MEASURE_PERIOD_MS = 300;
    private static final String PREFS = "fov_selfcheck";

    private static final FovSelfCheck INSTANCE = new FovSelfCheck();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FovSelfCheck");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    // session (set from the camera thread)
    private volatile String cameraId = "", slot = "", signature = "";
    private volatile boolean vendor, measuring;
    private volatile long sessionAtMs;
    private volatile float sessionCrop = Float.NaN;
    private volatile Runnable onCrop;
    private volatile Context appContext;
    // reference of the plain module (worker thread writes, GL thread reads only timestamps)
    private volatile float[] ref;
    private volatile int refH;
    private volatile String refCamera = "";
    private volatile float refResidual = 1f;
    private volatile long refAtMs = -1;
    private volatile long lastGrabMs;
    private volatile boolean busy;
    private float lastScale = Float.NaN;

    private FovSelfCheck() {}

    public static FovSelfCheck get() { return INSTANCE; }

    /**
     * A camera session started on {@code physicalId} for module {@code moduleSlot}; {@code vendorSignature} is empty for a
     * plain module. Returns the cached stream crop of this vendor module (NaN when it was never measured).
     */
    public float session(Context context, String physicalId, String moduleSlot, String vendorSignature, Runnable cropChanged) {
        appContext = context == null ? null : context.getApplicationContext();
        onCrop = cropChanged;
        cameraId = physicalId == null ? "" : physicalId;
        slot = moduleSlot == null ? "" : moduleSlot;
        signature = vendorSignature == null ? "" : vendorSignature;
        vendor = !signature.isEmpty();
        sessionAtMs = SystemClock.elapsedRealtime();
        lastScale = Float.NaN;
        final float cached = vendor ? cached(context, key()) : Float.NaN;
        measuring = vendor && Float.isNaN(cached);
        sessionCrop = cached;
        SubjectFrames.setSecondarySink(this);
        if (!Float.isNaN(cached)) Log.i(TAG, String.format(Locale.ROOT, "module %s: field 1/%.2f of camera %s (measured earlier)", slot, cached, cameraId));
        return cached;
    }

    /** The stream crop measured for the current session's module (NaN: not measured, the block rule applies). */
    public float sessionCrop() { return sessionCrop; }

    private String key() { return slot + "|" + cameraId + "|" + signature; }

    @Override
    public boolean wantsFrame(long nowNs) {
        if (busy) return false;
        final long now = SystemClock.elapsedRealtime();
        if (now - sessionAtMs < SETTLE_MS) return false;
        if (vendor) {
            if (!measuring || ref == null || !cameraId.equals(refCamera) || now - refAtMs > REF_MAX_AGE_MS + (now - sessionAtMs)) return false;
            return now - lastGrabMs >= MEASURE_PERIOD_MS;
        }
        return now - lastGrabMs >= REF_PERIOD_MS;
    }

    @Override
    public byte[] obtainBuffer(int bytes) { return new byte[bytes]; }

    @Override
    public void onFrame(byte[] rgba, int width, int height) {
        lastGrabMs = SystemClock.elapsedRealtime();
        busy = true;
        final boolean isVendor = vendor;
        final String cam = cameraId;
        final float residualNow = ZoomController.residual();
        final long at = lastGrabMs;
        worker.execute(() -> {
            try {
                int h = Math.max(8, Math.round((float) W * height / width));
                float[] gray = gray(rgba, width, height, W, h);
                if (!isVendor) {
                    ref = gray; refH = h; refCamera = cam; refResidual = residualNow; refAtMs = at;
                } else if (measuring && ref != null && refH == h) {
                    measure(gray, h, residualNow);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "frame: " + e);
            } finally {
                busy = false;
            }
        });
    }

    private void measure(float[] cur, int h, float residualNow) {
        float[] r = estimateScale(ref, cur, W, h);
        float s = r[0], ncc = r[1], second = r[2];
        if (!clearPeak(ncc, second)) {
            Log.d(TAG, String.format(Locale.ROOT, "no clear match (ncc %.2f, next %.2f): the scene changed", ncc, second));
            return;
        }
        if (Float.isNaN(lastScale) || Math.abs(s / lastScale - 1f) > AGREE) {
            lastScale = s;
            return;
        }
        measuring = false;
        float now = ZoomController.streamCrop();
        float crop = snap(s * refResidual / residualNow);
        Log.i(TAG, String.format(Locale.ROOT, "module %s: measured field 1/%.2f of camera %s (view scale %.3f, ncc %.2f; residual %.3f vs %.3f"
                + " on the reference; stream crop x%.2f -> x%.2f)", slot, crop, cameraId, s, ncc, residualNow, refResidual, now, crop));
        store(appContext, key(), crop);
        sessionCrop = crop;
        final Runnable changed = onCrop;
        com.particlesdevs.photoncamera.app.PhotonCamera.getMainHandler().post(() -> {
            if (ZoomController.setStreamCrop(crop) && changed != null) changed.run();
        });
    }

    /**
     * A match worth trusting: correlated (ncc >= {@link #MIN_NCC}) and distinct: the best scale's misfit 1 - ncc at least
     * {@link #PEAK_RATIO} times smaller than that of any scale more than 8 % away (a smooth scene correlates highly at
     * neighbouring scales too, so an absolute margin rejects good matches).
     */
    static boolean clearPeak(float ncc, float second) {
        return ncc >= MIN_NCC && (1f - second) >= PEAK_RATIO * (1f - ncc);
    }

    /** Whole crop factors within 6 % snap to them (2x / 4x ISZ); anything else as measured. */
    static float snap(float c) {
        float k = Math.round(c);
        return k >= 1f && Math.abs(c / k - 1f) <= 0.06f ? k : Math.max(1f, c);
    }

    /**
     * The scale s of {@code cur} against {@code ref} (both w x h gray, same aspect): cur(x) = ref(c + (x - c) / s + t).
     * Returns {s, best ncc, best ncc at a scale more than 8 % away}.
     */
    static float[] estimateScale(float[] ref, float[] cur, int w, int h) {
        float bestS = 1f, best = -2f;
        final int steps = 141; // 1/4 .. 8, 2.5 % apart
        float[] perScale = new float[steps];
        float[] scales = new float[steps];
        for (int i = 0; i < steps; ++i) {
            float s = (float) (0.25 * Math.pow(32.0, i / (double) (steps - 1)));
            scales[i] = s;
            float m = -2f;
            for (int ty = -3; ty <= 3; ++ty)
                for (int tx = -3; tx <= 3; ++tx) m = Math.max(m, ncc(ref, cur, w, h, s, tx, ty));
            perScale[i] = m;
            if (m > best) { best = m; bestS = s; }
        }
        // refine around the best scale
        for (float s = bestS * 0.975f; s <= bestS * 1.025f; s += bestS * 0.005f)
            for (float ty = -3; ty <= 3; ty += 0.5f)
                for (float tx = -3; tx <= 3; tx += 0.5f) {
                    float m = ncc(ref, cur, w, h, s, tx, ty);
                    if (m > best) { best = m; bestS = s; }
                }
        float second = -2f;
        for (int i = 0; i < steps; ++i) if (Math.abs(scales[i] / bestS - 1f) > 0.08f) second = Math.max(second, perScale[i]);
        return new float[]{bestS, best, second};
    }

    /** NCC of cur against ref sampled at c + (x - c) / s + t (bilinear), over the pixels of cur that land inside ref. */
    static float ncc(float[] ref, float[] cur, int w, int h, float s, float tx, float ty) {
        final float cx = (w - 1) * 0.5f, cy = (h - 1) * 0.5f;
        double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0;
        int n = 0;
        for (int y = 0; y < h; y += 1) {
            float ry = cy + (y - cy) / s + ty;
            if (ry < 0 || ry > h - 1.001f) continue;
            int y0 = (int) ry;
            float fy = ry - y0;
            for (int x = 0; x < w; x += 1) {
                float rx = cx + (x - cx) / s + tx;
                if (rx < 0 || rx > w - 1.001f) continue;
                int x0 = (int) rx;
                float fx = rx - x0;
                int i = y0 * w + x0;
                float a = (ref[i] * (1 - fx) + ref[i + 1] * fx) * (1 - fy) + (ref[i + w] * (1 - fx) + ref[i + w + 1] * fx) * fy;
                float b = cur[y * w + x];
                sa += a; sb += b; saa += a * a; sbb += b * b; sab += a * b;
                ++n;
            }
        }
        if (n < w * h / 8) return -1f; // too little overlap
        double va = saa - sa * sa / n, vb = sbb - sb * sb / n;
        if (va <= 1e-9 || vb <= 1e-9) return -1f;
        return (float) ((sab - sa * sb / n) / Math.sqrt(va * vb));
    }

    /** RGBA (rows bottom-up) to a gray w x h image, box-averaged. */
    static float[] gray(byte[] rgba, int width, int height, int w, int h) {
        float[] out = new float[w * h];
        float[] cnt = new float[w * h];
        for (int y = 0; y < height; ++y) {
            int oy = Math.min(h - 1, (height - 1 - y) * h / height);
            for (int x = 0; x < width; ++x) {
                int ox = Math.min(w - 1, x * w / width);
                int i = (y * width + x) * 4;
                float g = 0.299f * (rgba[i] & 0xFF) + 0.587f * (rgba[i + 1] & 0xFF) + 0.114f * (rgba[i + 2] & 0xFF);
                out[oy * w + ox] += g;
                cnt[oy * w + ox] += 1f;
            }
        }
        for (int i = 0; i < out.length; ++i) out[i] = cnt[i] > 0 ? out[i] / cnt[i] : 0f;
        return out;
    }

    private static float cached(Context context, String key) {
        if (context == null) return Float.NaN;
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p.contains(key) ? p.getFloat(key, Float.NaN) : Float.NaN;
    }

    private static void store(Context context, String key, float crop) {
        try {
            if (context != null) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(key, crop).apply();
        } catch (RuntimeException e) {
            Log.w(TAG, "cache: " + e);
        }
    }
}

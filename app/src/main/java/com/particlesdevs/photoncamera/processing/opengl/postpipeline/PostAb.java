package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Bitmap;

import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.processing.opengl.PostGlMode;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Allocator;
import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;

/**
 * Shot speed (wave 1, W1.0): scam_dev.txt "post_ab 1" runs the post pipeline of a shot twice on the same worker result, first
 * in the old GL model ({@link PostGlMode#legacy()}: the pipeline as before wave 1), then in the new one, and logs both times
 * and the MD5 of both bitmaps in one line:
 * <pre>POST AB old ms=... teardown=... md5=... | new ms=... md5=... EQUAL|DIFFERENT</pre>
 * The new run's bitmap goes on to the JPEG as in any shot. "post_ab_dump 1" also writes both bitmaps as post_ab_old.png /
 * post_ab_new.png to the external files dir. The old run gets the cleared effective-frame map of before W3.5 (the new run
 * reads the real map one-sided, ScamDenoise.effectiveFramesTexture).
 * The worker RGB, the effective-frame map and the clip flags are
 * kept for the second run (the first one frees the RGB after its upload and consumes the clip flags). Only for a hybrid /
 * SCAM HDR RGB of 16 MP or less without Ultra HDR (the copy of the RGB and a second bitmap must fit); otherwise one normal run.
 * A failure of the old run is logged and the shot goes on with the new one.
 */
public final class PostAb {
    private PostAb() {}

    /**
     * P48: scam_dev "post_ab_upload 1": the old run is the new GL model too, with only the worker RGB uploaded as RGB32F
     * instead of the RGBA32F bands (ScamRgb.rgbaUploadWanted), so EQUAL proves the band upload bit-exact on the phone.
     */
    static volatile boolean forceRgbUpload;

    /** post_ab is on and this shot can take it. */
    public static boolean wanted(Parameters p) {
        if (!PreferenceKeys.scamDevSwitch("post_ab", false)) return false;
        final boolean fits = p != null && p.scamRgb != null && p.scamRgbOwned && p.rawSize != null
                && (long) p.rawSize.x * p.rawSize.y <= 16_000_000L && !PhotonCamera.getSettings().gainMapPass();
        if (!fits) Log.i("SCAM_PIPELINE", "POST AB skipped: needs the owned worker RGB of 16 MP or less without Ultra HDR");
        return fits;
    }

    /** The A/B of one shot; returns the new model's bitmap ({@code pipeline} is the shot's own new-model pipeline). */
    public static Bitmap run(PostPipeline pipeline, ByteBuffer input, Parameters p) {
        final ByteBuffer rgb = p.scamRgb;
        final boolean owned = p.scamRgbOwned;
        final ByteBuffer clipFlags = ScamRgb.lastClipFlags;
        final ByteBuffer copy = Allocator.allocateAndCopy(rgb.capacity(), rgb, 0);
        if (copy == null) {
            Log.w("SCAM_PIPELINE", "POST AB skipped: no memory for the copy of the worker RGB");
            return pipeline.Run(input, p);
        }
        copy.order(rgb.order());
        String oldMd5 = "failed";
        long oldMs = -1, oldTeardown = -1;
        p.scamRgb = copy;
        p.scamRgbOwned = true;
        final PostPipeline old = new PostPipeline();
        old.tenBitOutput = pipeline.tenBitOutput; // the same output format in both runs
        old.p3Output = pipeline.p3Output; // and the same colour space (P46)
        final boolean uploadOnly = PreferenceKeys.scamDevSwitch("post_ab_upload", false);
        if (uploadOnly) forceRgbUpload = true; else PostGlMode.setLegacy(true);
        try {
            final long t0 = System.nanoTime();
            final Bitmap a = old.Run(input, p);
            final long t1 = System.nanoTime();
            old.close();
            oldTeardown = (System.nanoTime() - t1) / 1_000_000;
            oldMs = (t1 - t0) / 1_000_000;
            oldMd5 = md5(a);
            dump(a, "post_ab_old.png");
            a.recycle();
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.e("SCAM_PIPELINE", "POST AB old run failed: " + e);
            try { old.close(); } catch (RuntimeException ignored) {}
        } finally {
            PostGlMode.setLegacy(false);
            forceRgbUpload = false;
            // ScamRgb freed the copy after its upload and left a small decimated one; a run that failed earlier did not.
            if (p.scamRgb == copy) Allocator.free(copy);
            p.scamRgb = rgb;
            p.scamRgbOwned = owned;
            ScamRgb.lastClipFlags = clipFlags;
        }
        final long t2 = System.nanoTime();
        final Bitmap b = pipeline.Run(input, p);
        final long newMs = (System.nanoTime() - t2) / 1_000_000;
        final String newMd5 = md5(b);
        dump(b, "post_ab_new.png");
        Log.i("SCAM_PIPELINE", "POST AB old ms=" + oldMs + " teardown=" + oldTeardown + " md5=" + oldMd5
                + " | new ms=" + newMs + " md5=" + newMd5 + (oldMd5.equals(newMd5) ? " EQUAL" : " DIFFERENT")
                + " size=" + b.getWidth() + "x" + b.getHeight());
        return b;
    }

    /** scam_dev.txt "post_ab_dump 1": both bitmaps as lossless PNG in the app's external files dir, for a pixel diff. */
    private static void dump(Bitmap bitmap, String name) {
        if (!PreferenceKeys.scamDevSwitch("post_ab_dump", false)) return;
        try {
            final java.io.File dir = PhotonCamera.getAppContext().getExternalFilesDir(null);
            if (dir == null) return;
            try (java.io.OutputStream out = new java.io.FileOutputStream(new java.io.File(dir, name))) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
        } catch (Exception e) {
            Log.w("SCAM_PIPELINE", "POST AB dump failed: " + e);
        }
    }

    /** MD5 of the ARGB pixels of a bitmap, row by row (hex). */
    static String md5(Bitmap bitmap) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("MD5");
            final int w = bitmap.getWidth(), h = bitmap.getHeight(), rows = 64;
            final int[] pixels = new int[w * rows];
            final ByteBuffer bytes = ByteBuffer.allocate(w * rows * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int y = 0; y < h; y += rows) {
                final int n = Math.min(rows, h - y);
                bitmap.getPixels(pixels, 0, w, 0, y, w, n);
                bytes.clear();
                bytes.asIntBuffer().put(pixels, 0, w * n);
                digest.update(bytes.array(), 0, w * n * 4);
            }
            final StringBuilder hex = new StringBuilder();
            for (byte v : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", v & 255));
            return hex.toString();
        } catch (Exception e) {
            return "error:" + e.getClass().getSimpleName();
        }
    }
}

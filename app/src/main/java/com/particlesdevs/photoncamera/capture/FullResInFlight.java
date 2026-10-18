package com.particlesdevs.photoncamera.capture;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;

/**
 * P222 (roadmap speed-ux A3; the owner's X7 Ultra log 2026-10-17): the stills a FULLRES series keeps in flight. With P179's 3
 * the stills of a 50 MP series came in groups of 3 with 4-5 preview frames between them (resultFrame 116-118, 123-125,
 * 130-132 ...: 13 stills in 30 frame slots; a 250 ms PSL shot 12 in 24 slots, 5.5 s): the HAL had nothing of the series
 * queued while the oldest still completed, so the repeating preview took those slots. The RAWs are copied and closed as they
 * arrive (RAW16Saver.addImage), so more requests in flight hold no more reader buffers.
 * <ul>
 * <li>The native RAW-only route (vivo native, OPPO) up to 100 MP: min(series, {@link #DEFAULT}).</li>
 * <li>Above 100 MP: P179's {@link #OLD} (measured on the X200 Ultra: the preview stops at the shutter, the memory is at its
 * edge).</li>
 * <li>The X200 Ultra's SDK route: one at a time, as before (each still carries the SDK's own capture id and display time).</li>
 * <li>scam_dev.txt "fullres_in_flight N" (1..{@link #MAX}): N on every native route (3 = the count before P222).</li>
 * <li>A series with more than {@link #OLD} in flight that fails (a capture failure, a timeout, a lost or missing still, a
 * buffer error, a camera error) is remembered per camera and size: {@link #OLD} from the next series on. Bumping
 * {@link #TRIALS} in a later build tries the default again.</li>
 * </ul>
 * Not verified on a phone.
 */
final class FullResInFlight {
    private FullResInFlight() { }

    /** P179's count: the native route's before P222, and its count above 100 MP or after a failure. */
    static final int OLD = 3;
    /** P222: the native route's default up to 100 MP. */
    static final int DEFAULT = 6;
    /** The most scam_dev.txt fullres_in_flight may ask for. */
    static final int MAX = 8;
    /** The generation of the failure memory: a failure noted under another generation is forgotten. */
    static final int TRIALS = 1;
    private static final String FAILED = "module_fullres_inflight_fail_", WHY = "_why";

    /** The count of one series and where it comes from (for the log). */
    static final class Choice {
        final int count;
        final String source;

        Choice(int count, String source) {
            this.count = count;
            this.source = source;
        }

        @Override public String toString() {
            return count + " (" + source + ")";
        }
    }

    /**
     * The stills in flight for a series of {@code series} stills: {@code nativeRoute} the RAW-only native route (false: the
     * SDK route), {@code huge} above 100 MP, {@code dev} the scam_dev.txt value (used when an integer 1..MAX, else ignored),
     * {@code fallback} the failure noted for this camera and size (null: none).
     */
    static Choice choose(int series, boolean nativeRoute, boolean huge, float dev, @Nullable String fallback) {
        final int n = Math.max(1, series);
        if (!nativeRoute) return new Choice(1, "the SDK route: one at a time");
        final String noted = fallback == null ? "" : "; failed before with more than " + OLD + ": " + fallback;
        if (dev >= 1 && dev <= MAX && dev == Math.rint(dev))
            return new Choice(Math.min(n, (int) dev), "scam_dev fullres_in_flight " + (int) dev + noted);
        final String ignored = Float.isNaN(dev) || dev == 0 ? "" : "; scam_dev fullres_in_flight " + dev + " ignored (1.." + MAX + ")";
        if (fallback != null) return new Choice(Math.min(n, OLD), "after a failure with more than " + OLD + ": " + fallback + ignored);
        if (huge) return new Choice(Math.min(n, OLD), "above 100 MP, as P179" + ignored);
        return new Choice(Math.min(n, DEFAULT), "default" + ignored);
    }

    /** The count for a series of {@code series} stills of this camera's profile, with scam_dev.txt and the failure memory. */
    static Choice forSeries(@Nullable String cameraId, VivoSdkFullRes.Profile profile, int series) {
        return choose(series, profile.nativeRoute(), huge(profile), PreferenceKeys.scamDevNumber("fullres_in_flight", 0),
                fallback(preferences(), cameraId, profile));
    }

    static boolean huge(VivoSdkFullRes.Profile profile) {
        return (long) profile.size.getWidth() * profile.size.getHeight() > CaptureController.HUGE_SHOT_PIXELS;
    }

    /** The failure key of a camera's size. */
    static String key(String cameraId, VivoSdkFullRes.Profile profile) {
        return FAILED + cameraId + "_" + profile.size.getWidth() + "x" + profile.size.getHeight();
    }

    /** The failure noted for this camera and size in this generation, null without one. */
    @Nullable
    static String fallback(@Nullable SharedPreferences p, @Nullable String cameraId, @Nullable VivoSdkFullRes.Profile profile) {
        if (p == null || cameraId == null || profile == null) return null;
        try {
            final String key = key(cameraId, profile);
            if (p.getInt(key, 0) != TRIALS) return null;
            final String why = p.getString(key + WHY, null);
            return why == null || why.isEmpty() ? "a failed series" : why;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A failure that may be the count's: everything but a sensor mode or mosaic the still proved wrong (the row memory of
     * VivoNativeFullRes / OppoNativeFullRes takes those) and the camera closed or taken away under the series (the app left, a
     * camera switch, another client). An unknown failure counts.
     */
    static boolean counts(@Nullable String why) {
        if (why == null) return true;
        return !(why.contains("sensor mode is not active") || why.contains("mosaic does not match") || why.contains("not been verified")
                || why.startsWith("camera closed") || why.startsWith("camera disconnected"));
    }

    /**
     * A series that kept {@code count} stills in flight ended with {@code why} (failed, or completed with a still missing):
     * noted for this camera and size when the count was above {@link #OLD} and the failure {@link #counts}. True when noted.
     */
    static boolean failed(@Nullable SharedPreferences p, @Nullable String cameraId, @Nullable VivoSdkFullRes.Profile profile,
                          int count, @Nullable String why) {
        if (count <= OLD || !counts(why) || p == null || cameraId == null || profile == null) return false;
        final String text = why == null ? "unknown" : why.length() > 160 ? why.substring(0, 160) : why;
        try {
            final String key = key(cameraId, profile);
            p.edit().putInt(key, TRIALS).putString(key + WHY, text).apply();
        } catch (RuntimeException e) {
            return false;
        }
        com.particlesdevs.photoncamera.util.Log.w("SCAM_CAPTURE", "FULLRES in flight: camera " + cameraId + " " + profile.size
                + " failed with " + count + " stills in flight (" + text + "); " + OLD + " from the next series on");
        return true;
    }

    /** {@link #failed(SharedPreferences, String, VivoSdkFullRes.Profile, int, String)} in the app's preferences. */
    static boolean failed(@Nullable String cameraId, @Nullable VivoSdkFullRes.Profile profile, int count, @Nullable String why) {
        return count > OLD && counts(why) && failed(preferences(), cameraId, profile, count, why);
    }

    @Nullable
    private static SharedPreferences preferences() {
        try {
            return com.particlesdevs.photoncamera.app.ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
        } catch (RuntimeException e) {
            return null;
        }
    }
}

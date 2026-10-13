package com.particlesdevs.photoncamera.settings;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Range;
import android.util.Size;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * P86 Photo mode «Движение» (owner, 2026-10-11: «максимально хорошее движение и максимально быстрый буфер чтобы успевать ловить
 * всё»; the switch beside the settings gear in the viewfinder's top bar). While it is on:
 * <ul>
 *   <li>the viewfinder and the ZSL ring run at the fastest fixed frame rate the RAW stream allows (the AE range [f, f]): the
 *       ring is refreshed most often and no ring frame is exposed longer than 1/f s, so the frame at the press is sharp;</li>
 *   <li>P110: the shutter of the ring frames has a ceiling (pref_scam_hybrid_mm_shutter, 1/250 s; manual/MotionShutter):
 *       a longer shutter of the camera's AE becomes the ceiling with the ISO raised (the fixed frame rate alone left 1/30 s,
 *       the photo mode's own indoor shutter);</li>
 *   <li>the hybrid takes its own values for the merge (pref_scam_hybrid_mm_&lt;key&gt;, the «Движение» group of the Hybrid
 *       settings) instead of the photo values: fewer N frames (a short time span, a fast merge), no Shasta (long frames smear
 *       what moves), Bento only where the frame clips, the strongest motion robustness.</li>
 * </ul>
 * The mode flag is global (not per module); its merge values follow the module profiles like every hybrid setting.
 */
public final class MotionMode {
    public static final String PREF = "pref_motion_mode";
    /** Prefix (after pref_scam_hybrid_) of the mode's own merge values. */
    static final String PREFIX = "mm_";

    /** The hybrid keys the mode replaces and their defaults (values of the «Движение» group). */
    static final Map<String, Float> DEFAULTS;
    static {
        Map<String, Float> d = new HashMap<>();
        d.put("zsl_frames", 8f);        // 8 of the ring's newest frames: ~0.13-0.27 s span, a fast merge
        d.put("shasta", 0f);            // switch: no bracketed long frames
        d.put("bento", 1f);             // ultrashort frames only where the buffered frame clips
        d.put("boost_value", 10f);      // motion robustness boost at its maximum
        d.put("motion_threshold", 0.5f); // tiles count as moving from 0.5 RAW px of local flow (photo: 1.5)
        d.put("motion_boost", 1f);      // switch: the robustness boost on
        DEFAULTS = Collections.unmodifiableMap(d);
    }
    /** Frame rate of the ring: 0 = the fastest the RAW stream allows (default), else this rate if the camera has it. */
    static final String FPS = "fps";

    private MotionMode() {}

    public static boolean on() {
        return PreferenceKeys.isMotionMode();
    }

    public static void set(boolean value) {
        PreferenceKeys.setMotionMode(value);
    }

    /** Whether the mode replaces this hybrid key. */
    static boolean replaces(String key) {
        return DEFAULTS.containsKey(key);
    }

    /**
     * The AE target range of the mode's viewfinder / ring: the fixed range [f, f] with the highest f not above the RAW stream's
     * fastest rate ({@code rawMaxFps}, 0 = unknown: no limit) and not above {@code wantedFps} when that is set (> 0); null when the
     * camera lists no fixed range.
     */
    @Nullable
    static Range<Integer> fpsRange(@Nullable Range<Integer>[] available, float rawMaxFps, int wantedFps) {
        if (available == null) return null;
        // owner: «максимально возможный фпс видоискателя»: the camera's fastest fixed rate, within the RAW stream's limit when known
        final float cap = Math.min(rawMaxFps > 0 ? rawMaxFps + 0.5f : Float.MAX_VALUE, wantedFps > 0 ? wantedFps + 0.5f : Float.MAX_VALUE);
        Range<Integer> best = null;
        for (Range<Integer> r : available) {
            if (r == null || !r.getLower().equals(r.getUpper()) || r.getUpper() > cap) continue;
            if (best == null || r.getUpper() > best.getUpper()) best = r;
        }
        return best;
    }

    /** The mode's AE target range on this camera, or null (keep the photo range). */
    @Nullable
    public static Range<Integer> fpsRange(@Nullable CameraCharacteristics c, int rawFormat, @Nullable Size rawSize) {
        if (c == null) return null;
        float rawMaxFps = 0;
        try {
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null && rawSize != null) {
                long minNs = map.getOutputMinFrameDuration(rawFormat, rawSize);
                if (minNs > 0) rawMaxFps = 1e9f / minNs;
            }
        } catch (RuntimeException ignored) {
            // format / size not listed: the camera's fastest fixed rate
        }
        final int wanted = Math.round(PreferenceKeys.hybridValue(PREFIX + FPS, 0f));
        return fpsRange(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES), rawMaxFps, wanted);
    }
}

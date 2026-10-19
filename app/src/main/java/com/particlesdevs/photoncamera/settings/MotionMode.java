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
 *   <li>the frame rate is the module's («Частота кадров RAW», P266; owner, 2026-10-19: «фпс ... автоматически», by hand
 *       there): the mode's own rate row (the camera's fastest fixed rate) is gone - one setting for one thing;</li>
 *   <li>P110: the shutter of the ring frames has a ceiling (pref_scam_hybrid_mm_shutter, 1/250 s; manual/MotionShutter):
 *       a longer shutter of the camera's AE becomes the ceiling with the ISO raised (the fixed frame rate alone left 1/30 s,
 *       the photo mode's own indoor shutter);</li>
 *   <li>P111, after the stock vivo «Моментальный снимок» (motion deblur): the ceiling drops by how fast the scene moves
 *       (capture/MotionMeter on the ring's RAW, MotionShutter.Levels), the moment is the frame on the screen at the touch of the
 *       shutter, the merge's base is that frame, ring frames with a longer shutter stay out, the frames after the shutter keep
 *       the ceiling, the moving subject keeps its detail (no widened base kernel), lamp flicker is checked on the frames;</li>
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
        // P111: no widened base kernel where donors were rejected (the moving subject): its detail stays, the denoise (stronger
        // there through the effective-frame map) takes the noise
        d.put("widen_below", 0f);
        DEFAULTS = Collections.unmodifiableMap(d);
    }
    private MotionMode() {}

    public static boolean on() {
        return PreferenceKeys.isMotionMode();
    }

    public static void set(boolean value) {
        PreferenceKeys.setMotionMode(value);
    }

    /**
     * P113: ring frames a «Движение» shot takes while {@code pending} earlier shots wait for their merge: all it wants with an
     * empty queue, at most 4 behind 2-3 shots, 2 behind more (the stock snapshot mode drops to fewer frames, down to one, when its
     * capture queue fills): a fast series keeps going without the memory and the merge time of full bursts piling up.
     */
    public static int framesForQueue(int wanted, int pending) {
        if (pending >= 4) return Math.max(1, Math.min(wanted, 2));
        if (pending >= 2) return Math.max(1, Math.min(wanted, 4));
        return wanted;
    }

    /** Whether the mode replaces this hybrid key. */
    static boolean replaces(String key) {
        return DEFAULTS.containsKey(key);
    }

}

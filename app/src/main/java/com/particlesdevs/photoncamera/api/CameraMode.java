package com.particlesdevs.photoncamera.api;

import androidx.annotation.StringRes;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;

import java.util.stream.Stream;

public enum CameraMode {
    UNLIMITED(R.string.mode_unlimited),
    RAWVIDEO(R.string.mode_rawvideo),
    MOTION(R.string.mode_motion),
    PHOTO(R.string.mode_photo),
    NIGHT(R.string.mode_night),
    VIDEO(R.string.mode_video);

    int stringId;

    CameraMode(@StringRes int stringId) {
        this.stringId = stringId;
    }

    public static CameraMode valueOf(int modeOrdinal) {
        for (CameraMode mode : values()) {
            if (modeOrdinal == mode.ordinal()) {
                return mode;
            }
        }
        return MOTION;
    }

    public static Integer[] nameIds() {
        return Stream.of(values()).map(mode -> mode.stringId).toArray(Integer[]::new);
    }

    /** The deliberately reduced capture UI: fast ZSL/HDR and Night only. */
    public static CameraMode[] userModes() {
        return new CameraMode[]{MOTION, NIGHT};
    }

    public static Integer[] userModeNameIds() {
        return Stream.of(userModes()).map(mode -> mode.stringId).toArray(Integer[]::new);
    }

    public static int userModeIndex(CameraMode mode) {
        CameraMode[] modes = userModes();
        for (int i = 0; i < modes.length; i++) if (modes[i] == mode) return i;
        return 0;
    }

    public static CameraMode userModeAt(int index) {
        CameraMode[] modes = userModes();
        return modes[Math.max(0, Math.min(index, modes.length - 1))];
    }

}

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

    /**
     * The stored mode; the retired UNLIMITED / RAWVIDEO modes (no processor any more) and Night (P25, owner's answer 4:
     * Photo is the only mode on screen) read as MOTION, which the screen shows as «Фото».
     */
    public static CameraMode valueOf(int modeOrdinal) {
        for (CameraMode mode : values()) {
            if (modeOrdinal == mode.ordinal()) {
                return mode == UNLIMITED || mode == RAWVIDEO || mode == NIGHT ? MOTION : mode;
            }
        }
        return MOTION;
    }

    /** Still photo modes (the ZSL routes); tripod detection runs in them. */
    public static boolean isStill(CameraMode mode) {
        return mode == MOTION || mode == PHOTO || mode == NIGHT;
    }

    public static Integer[] nameIds() {
        return Stream.of(values()).map(mode -> mode.stringId).toArray(Integer[]::new);
    }

    /** The capture UI: Photo only (P25: the Night mode, which differed only by the tripod detection, is gone). */
    public static CameraMode[] userModes() {
        return new CameraMode[]{MOTION};
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

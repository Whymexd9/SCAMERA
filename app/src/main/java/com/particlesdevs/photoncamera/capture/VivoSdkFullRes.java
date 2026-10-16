package com.particlesdevs.photoncamera.capture;

import android.util.Size;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.api.CameraMode;
import com.particlesdevs.photoncamera.capture.spoof.CameraPackageSpoof;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

public final class VivoSdkFullRes {
    private VivoSdkFullRes() { }

    public static final class Profile {
        public final Size size;
        public final int sensorMode;
        public final int nativeBlock;

        private Profile(int width, int height, int sensorMode) {
            this(width, height, sensorMode, 0);
        }

        Profile(int width, int height, int sensorMode, int nativeBlock) {
            size = new Size(width, height);
            this.sensorMode = sensorMode;
            this.nativeBlock = nativeBlock;
        }
    }

    public static Profile profile(String cameraId) {
        if (nativeRaw()) return nativeProfile(cameraId);
        if ("3".equals(cameraId)) return new Profile(8192, 6144, 13);
        if ("4".equals(cameraId)) return new Profile(8192, 6144, 11);
        if ("5".equals(cameraId)) return new Profile(8160, 6144, 18);
        return null;
    }

    /** The native FULLRES build on the X200 Ultra (its own package; the default package takes the SDK profiles). */
    static boolean nativeRaw() {
        android.content.Context context = ScameraApp.getAppContext();
        return context != null && VivoQcomModes.device() && VivoPd2454NativeRaw.PACKAGE.equals(context.getPackageName());
    }

    static Profile nativeProfile(String cameraId) {
        if ("3".equals(cameraId) || "4".equals(cameraId)) return new Profile(8192, 6144, 4, 2);
        if ("5".equals(cameraId)) return PixelPro10.tele200() ? TELE_200 : TELE_50;
        return null;
    }

    /** P179: the telephoto's two native sizes - 200 MP (mode 0, Tetra 16320x12288) and 50 MP (mode 1, Quad 8160x6144). */
    static final Profile TELE_200 = new Profile(16320, 12288, 0, 4), TELE_50 = new Profile(8160, 6144, 1, 2);

    /** P179: the camera offers both sizes (the X200 Ultra's telephoto, native build): a FULLRES button for each. */
    public static boolean teleSizes(String cameraId) {
        return "5".equals(cameraId) && nativeRaw();
    }

    static int frameCount(int requested, long frameBytes, long availableBytes) {
        if (frameBytes <= 0) throw new IllegalArgumentException("Invalid FULLRES frame size");
        int count = Math.max(1, Math.min(64, requested));
        // Retained RAWs must leave room for the camera, frame copies and subsequent processing.
        return availableBytes >= 0 ? (int) Math.min(count, availableBytes / 2 / frameBytes) : count;
    }

    public static boolean supportsMode(CameraMode mode) {
        return CameraMode.isStill(mode);
    }

    public static boolean available(String cameraId) {
        return offered(cameraId) && !SensorModeSwitches.sensorPreset(
                com.particlesdevs.photoncamera.settings.ModuleRegistry.active()) && !Dcg14Mode.on();
    }

    public static boolean offered(String cameraId) {
        return VivoQcomModes.device() && profile(cameraId) != null
                && "off".equals(CameraPackageSpoof.signature(ScameraApp.getAppContext()))
                && PreferenceKeys.scamDevSwitch("vivo_sdk_fullres", true);
    }
}

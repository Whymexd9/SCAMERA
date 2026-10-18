package com.particlesdevs.photoncamera.capture;

import android.util.Size;

import androidx.annotation.Nullable;

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
        /**
         * P208 (X100 / X300 Ultra 200 MP, the stock camera's session of 2026-10-17): the viewfinder is the camera's own RAW stream in
         * its normal mode (no forceSensorMode), the full-size RAW stream only takes the stills, the HAL switches the sensor for them.
         */
        public final boolean stillOnly;

        private Profile(int width, int height, int sensorMode) {
            this(width, height, sensorMode, 0);
        }

        Profile(int width, int height, int sensorMode, int nativeBlock) {
            this(width, height, sensorMode, nativeBlock, false);
        }

        Profile(int width, int height, int sensorMode, int nativeBlock, boolean stillOnly) {
            size = new Size(width, height);
            this.sensorMode = sensorMode;
            this.nativeBlock = nativeBlock;
            this.stillOnly = stillOnly && nativeBlock > 0;
        }

        /**
         * The native route (RAW-only session, forceSensorMode / the OPPO mode, the own RAW viewfinder): a Quad / Tetra mosaic
         * (nativeBlock 2 / 4) or P194's hardware-remosaicked plain Bayer (nativeBlock 1); 0 is the X200 Ultra's SDK route.
         */
        public boolean nativeRoute() {
            return nativeBlock > 0;
        }

        /** P194: the native route's plain Bayer row of the X100 / X300 Ultra's hardware remosaic. */
        public boolean nativeBayer() {
            return nativeBlock == 1;
        }
    }

    public static Profile profile(String cameraId) {
        return profile(cameraId, PixelPro10.tele200());
    }

    /** P181: the camera's profile at the given size of a camera with two (200 MP and 50 MP); others have one size. */
    public static Profile profile(String cameraId, boolean tele200) {
        if (OppoNativeFullRes.device()) return OppoNativeFullRes.profile(cameraId); // P192: one size, the sensor's own mosaic
        final boolean nativeBuild = nativeRaw();
        return choose(cameraId, nativeBuild, nativeBuild && hardwareRemosaicOn(), tele200);
    }

    /**
     * The FULLRES profile of a camera: the default package's SDK route (the sensor's hardware remosaic, plain Bayer); the
     * native build's own mosaic (Quad / Tetra, the own remosaic), or P182 (owner, 2026-10-17: «дай возможность для fullres
     * включать аппаратный ремозаик») with «Аппаратный ремозаик» on its 50 MP shots by the SDK route's hardware-remosaic modes -
     * there is no hardware-remosaic 200 MP mode, a 200 MP shot keeps the own remosaic.
     */
    static Profile choose(String cameraId, boolean nativeBuild, boolean hardwareRemosaic, boolean tele200) {
        return choose(VivoNativeFullRes.phone(), cameraId, nativeBuild, hardwareRemosaic, tele200);
    }

    /**
     * {@link #choose} on a given phone (null: not one of the native tables, as the X200 Ultra). P194 (owner, 2026-10-17: the
     * hardware remosaic on the X100 / X300 Ultra too): they have no verified SDK mode, so their 50 MP hardware remosaic is the
     * native route with the row their HAL gives remosaicked (VivoNativeFullRes.hardwareProfile, plain Bayer); a camera without
     * such a row keeps its own mosaic whatever the switch.
     */
    static Profile choose(@Nullable VivoNativeFullRes.Phone phone, String cameraId, boolean nativeBuild, boolean hardwareRemosaic,
                          boolean tele200) {
        if (!nativeBuild) return sdkProfile(cameraId);
        if (phone == VivoNativeFullRes.Phone.X100_ULTRA || phone == VivoNativeFullRes.Phone.X300_ULTRA) {
            final boolean large = tele200 && VivoNativeFullRes.twoSizes(phone, cameraId, true);
            final Profile hardware = hardwareRemosaic && !large ? VivoNativeFullRes.hardwareProfile(phone, cameraId, true) : null;
            return hardware != null ? hardware : VivoNativeFullRes.profile(phone, cameraId, tele200);
        }
        final boolean large = tele200 && VivoNativeFullRes.twoSizes(cameraId);
        final Profile hardware = sdkProfile(cameraId);
        if (hardwareRemosaic && !large && hardware != null) return hardware;
        return VivoNativeFullRes.profile(cameraId, tele200);
    }

    /** The X200 Ultra's hardware-remosaic FULLRES modes (P109m / P109p, the SDK route): DNGHWREMOSAIC Bayer at 50 MP. */
    static Profile sdkProfile(String cameraId) {
        if ("3".equals(cameraId)) return new Profile(8192, 6144, 13);
        if ("4".equals(cameraId)) return new Profile(8192, 6144, 11);
        if ("5".equals(cameraId)) return new Profile(8160, 6144, 18);
        return null;
    }

    /** P182: the setting «Аппаратный ремозаик» (FULLRES 50 MP by the sensor's hardware remosaic). */
    public static final String PREF_HW_REMOSAIC = "pref_fullres_hw_remosaic";

    /**
     * P182: the switch acts here: the native build on the X200 Ultra, whose hardware-remosaic modes are known; P194 the X100 /
     * X300 Ultra's native build too (the main camera's 50 MP, VivoNativeFullRes.hardwareRow). Never the OPPO Find X7 Ultra.
     */
    public static boolean hardwareRemosaicOffered() {
        return !OppoNativeFullRes.device() && nativeRaw();
    }

    static boolean hardwareRemosaicOn() {
        return !OppoNativeFullRes.device() && VivoNativeFullRes.phone() != null && PreferenceKeys.isFullResHwRemosaic();
    }

    /**
     * The native FULLRES build (its own package; the default package takes the SDK profiles): the X200 Ultra, P180 the X100 /
     * X300 Ultra unless their switch turns them off (VivoNativeFullRes).
     */
    static boolean nativeRaw() {
        android.content.Context context = ScameraApp.getAppContext();
        return context != null && VivoNativeFullRes.device() && VivoPd2454NativeRaw.PACKAGE.equals(context.getPackageName());
    }

    /** The phone's native profile of a camera (P180: VivoNativeFullRes' table; the X200 Ultra's anywhere else). */
    static Profile nativeProfile(String cameraId) {
        return VivoNativeFullRes.profile(cameraId, PixelPro10.tele200());
    }

    /** P179: the telephoto's two native sizes - 200 MP (mode 0, Tetra 16320x12288) and 50 MP (mode 1, Quad 8160x6144). */
    static final Profile TELE_200 = new Profile(16320, 12288, 0, 4), TELE_50 = new Profile(8160, 6144, 1, 2);

    /**
     * P179: the camera offers both sizes (the X200 Ultra's telephoto, native build): a FULLRES button for each; P180 the X100
     * Ultra's telephoto, the X300 Ultra's main camera and telephoto too.
     */
    public static boolean teleSizes(String cameraId) {
        return nativeRaw() && VivoNativeFullRes.twoSizes(cameraId);
    }

    static int frameCount(int requested, long frameBytes, long availableBytes) {
        if (frameBytes <= 0) throw new IllegalArgumentException("Invalid FULLRES frame size");
        int count = Math.max(1, Math.min(64, requested));
        // Retained RAWs must leave room for the camera, frame copies and subsequent processing.
        return availableBytes >= 0 ? (int) Math.min(count, availableBytes / 2 / frameBytes) : count;
    }

    /**
     * P190: frames of an explicit FULLRES series (pref_scam_hybrid_fullres50_frames / fullres200_frames) the merge can hold beside
     * {@code availableBytes}: the worker's fixed part ({@code huge}: 20 B, else 40 B per output pixel, scam-hybrid.h), the copied-out
     * reference RAW, then per frame its RAW, its binned copy (2 B / block^2 per pixel) and the GPU strip windows (3.8 KB per
     * column). A 200 MP merge runs with the camera closed: the HAL's four RAW buffers count as free. 0 when nothing fits.
     */
    static int seriesFit(long pixels, int width, long frameBytes, int block, int grid, boolean huge, long availableBytes) {
        if (availableBytes < 0) return 64;
        final double halFree = huge ? 4.0 * frameBytes : 0, fixed = (huge ? 20.0 : 40.0) * pixels * grid * grid;
        final double perFrame = frameBytes + 2.0 * pixels / ((double) block * block) + 3800.0 * width;
        return (int) Math.max(0, Math.min(64, Math.floor((availableBytes + halFree - fixed - frameBytes) / perFrame)));
    }

    /**
     * P190: frames of a FULLRES series: {@link #frameCount}'s RAW guard, at least the legacy count (200 MP: 4, as before P190; 50 MP:
     * 2, the merge's minimum), beyond it as many as {@link #seriesFit} gives. Up to 4 frames at 200 MP exactly the P179 count.
     */
    static int seriesCount(int requested, long pixels, int width, long frameBytes, int block, int grid, boolean huge, long availableBytes) {
        final int guard = frameCount(requested, frameBytes, availableBytes);
        final int legacy = Math.min(huge ? 4 : 2, guard);
        return Math.min(guard, Math.max(legacy, seriesFit(pixels, width, frameBytes, block, grid, huge, availableBytes)));
    }

    public static boolean supportsMode(CameraMode mode) {
        return CameraMode.isStill(mode);
    }

    public static boolean available(String cameraId) {
        return offered(cameraId) && !SensorModeSwitches.sensorPreset(
                com.particlesdevs.photoncamera.settings.ModuleRegistry.active()) && !Dcg14Mode.on();
    }

    public static boolean offered(String cameraId) {
        // P192: the OPPO Find X7 Ultra's native modes, its telephotos listed through the Oplus package (OppoNativeFullRes)
        if (OppoNativeFullRes.device()) return profile(cameraId) != null;
        // P180: the X100 / X300 Ultra only by the native route (the CTS package; their switch not off)
        return (VivoQcomModes.device() || nativeRaw()) && profile(cameraId) != null
                && "off".equals(CameraPackageSpoof.signature(ScameraApp.getAppContext()))
                && PreferenceKeys.scamDevSwitch("vivo_sdk_fullres", true);
    }

    /**
     * P214h (speed-ux C2; P210: «all own rows fail, the size goes off, the button hides»): the camera would offer FULLRES - the
     * conditions of {@link #offered} but its profile - yet every row it tried failed (VivoNativeFullRes / OppoNativeFullRes
     * trials). Its button stays, dimmed; a tap says why (FullResTrials.status) and offers «Попробовать снова».
     */
    public static boolean exhausted(String cameraId) {
        if (cameraId == null || offered(cameraId)) return false;
        if (OppoNativeFullRes.device()) return OppoNativeFullRes.exhausted(cameraId);
        return nativeRaw() && VivoNativeFullRes.hasTable(VivoNativeFullRes.phone(), cameraId)
                && "off".equals(CameraPackageSpoof.signature(ScameraApp.getAppContext()))
                && PreferenceKeys.scamDevSwitch("vivo_sdk_fullres", true);
    }

    /**
     * P214h: the camera's buttons are two (200 MP and 50 MP) whatever the trials say: a size whose rows all failed keeps its
     * button, dimmed (it gave way to the other size's single button before, P186). The X200 Ultra's as {@link #teleSizes}.
     */
    public static boolean teleButtons(String cameraId) {
        return nativeRaw() && VivoNativeFullRes.twoSizes(VivoNativeFullRes.phone(), cameraId, false);
    }

    /** P214h: that size ({@code large}: 200 MP) of a camera with two buttons has no row left (dimmed). */
    public static boolean sizeExhausted(String cameraId, boolean large) {
        return nativeRaw() && !OppoNativeFullRes.device()
                && VivoNativeFullRes.sizeExhausted(VivoNativeFullRes.phone(), cameraId, large, hardwareRemosaicOn());
    }

    /** P192: the running FULLRES is the OPPO Find X7 Ultra's (no SDK lease, the own AE and white balance). */
    static boolean oppo() {
        return OppoNativeFullRes.device();
    }
}

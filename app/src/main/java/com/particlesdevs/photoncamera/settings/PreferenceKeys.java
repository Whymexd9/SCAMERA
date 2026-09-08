package com.particlesdevs.photoncamera.settings;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import androidx.exifinterface.media.ExifInterface;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.api.CameraMode;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.util.Log;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/* loaded from: classes8.dex */
public class PreferenceKeys {
    private static final Set<String> COMMON_KEYS = new HashSet();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String PER_LENS_KEY_PREFIX = "settings_for_camera_";
    public static final String SCOPE_GLOBAL = "default_scope";
    private static final String TAG = "PreferenceKeys";
    private static PreferenceKeys preferenceKeys;
    private final SettingsManager settingsManager;

    /**
     * Id of the selected noise-model profile, or {@code "auto"} to keep the
     * SENSOR_NOISE_PROFILE reported by Camera2.
     */
    public static String getNoiseModelProfileId() {
        return getAcesString("pref_noise_model_profile_key", "auto");
    }

    /** Master switch for the observed-sigma multiplier computed in ESD4D (tag DynamicNoise). */
    public static boolean isDynamicNoiseModelEnabled() {
        return preferenceKeys.settingsManager.getBoolean(
                "default_scope", "pref_noise_dynamic_enabled_key", true);
    }

    /** Scales the whole noise model. 1.0 leaves the calibration untouched. */
    public static float getNoiseModelCoefficient() {
        return Float.parseFloat(getAcesString("pref_noise_model_coefficient_key", "1.0"));
    }

    /**
     * Derive the burst's shutter from the TET waypoint curve (see {@code TetModel})
     * instead of the per-frame heuristics, and hold that shutter constant across the
     * bracket so the ends differ by gain alone. This is what a GCam shot dump shows:
     * one shutter for every frame in the burst, "Desired exposure time factor:
     * 1.000000", with the whole bracket spread carried by "Desired TET factor".
     * Off by default so the two behaviours can be compared on one build.
     */
    /**
     * Mains frequency used to snap the TET model's shutter to a whole number of flicker
     * periods, matching GCam's apply_antibanding. 120 covers 60 Hz mains (the burst dump
     * reports scene_flicker 120); 100 covers 50 Hz. 0 disables the snap.
     */
    public static int getAntibandingHz() {
        return Integer.parseInt(getAcesString("pref_antibanding_hz_key", "120"));
    }

    public static boolean isTetModelEnabled() {
        return preferenceKeys.settingsManager.getBoolean(
                "default_scope", "pref_tet_model_enabled_key", false);
    }

    /** Clamp the O term's digital gain to 1, i.e. ignore gain applied above the analogue ISO. */
    public static boolean isNoiseDigitalGainDisabled() {
        return preferenceKeys.settingsManager.getBoolean(
                "default_scope", "pref_noise_disable_digital_gain_key", false);
    }

    /**
     * Tuning factor on the noise variance in the merge shrinkage operator. Larger accepts
     * more of the aligned frame (more denoising, less robustness). HDR+ fixes this to 8.
     */
    public static float getMergeRobustness() {
        return Float.parseFloat(getAcesString("pref_merge_robustness_key", "8.0"));
    }

    /**
     * A sample of the alternate frame below this many noise sigmas carries no usable signal
     * (it is under the sensor's noise and quantisation floor) and is not merged. 0 disables
     * the check. This is the counterpart of the highlight mask for very short frames.
     */
    public static float getMergeFloorSigmas() {
        return Float.parseFloat(getAcesString("pref_merge_floor_sigmas_key", "2.0"));
    }

    /**
     * Frames whose exposure ratio to the reference exceeds this are dropped from the merge
     * entirely; 0 disables the limit. Google's own bursts stay around 33x.
     */
    public static float getMergeMaxExposureRatio() {
        return Float.parseFloat(getAcesString("pref_merge_max_exposure_ratio_key", "64.0"));
    }

    /**
     * Upper bound on the long frame's shutter, in sensor readout periods (1/30 s each).
     * GCam's equivalent, camera.shasta_zsl.max_exptime_ms, runs at two periods. 0 removes
     * the cap; any EV the cap leaves unspent is taken from gain instead.
     */
    public static float getLongFrameShutterCapPeriods() {
        return Float.parseFloat(getAcesString("pref_long_frame_shutter_cap_key", "2.0"));
    }

    /**
     * Hard ceiling on the ratio between the longest and shortest frame of the burst.
     * GCam's tuning caps this at max_hdr_ratio_default = 9.8 (15.3 in Night Sight) and
     * raises the short exposure until the burst fits, rather than letting the user pick an
     * arbitrary spread: past this point the short frame holds nothing but noise and the
     * long frame nothing but clipping, and alignment between them stops working. 0 removes
     * the ceiling.
     */
    public static float getMaxHdrRatio() {
        return Float.parseFloat(getAcesString("pref_max_hdr_ratio_key", "9.8"));
    }

    /** Samples at or above this fraction of full scale are treated as clipped and not merged. */
    public static float getMergeClipLevel() {
        return Float.parseFloat(getAcesString("pref_merge_clip_level_key", "0.99"));
    }

    /**
     * Disagreement between the four alignment tiles blended at a pixel, in pixels, at which
     * the local alignment field is considered unusable and the unaligned frame is used.
     */
    public static float getMergeTilingTolerance() {
        return Float.parseFloat(getAcesString("pref_merge_tiling_tolerance_key", "4.0"));
    }

    /** Noise ISO curve: off, soft, medium or strong compression of the model's ISO response. */
    public static String getNoiseIsoCurve() {
        return getAcesString("pref_noise_iso_curve_key", "off");
    }

    /** Lower clamp for the ISO fed to the noise model; 0 disables the clamp. */
    public static int getNoiseIsoMin() {
        return Integer.parseInt(getAcesString("pref_noise_iso_min_key", "0"));
    }

    /** Upper clamp for the ISO fed to the noise model; 0 disables the clamp. */
    public static int getNoiseIsoMax() {
        return Integer.parseInt(getAcesString("pref_noise_iso_max_key", "0"));
    }

    /** Fixed ISO for the noise model regardless of the capture; 0 uses the real sensitivity. */
    public static int getNoiseIsoManual() {
        return Integer.parseInt(getAcesString("pref_noise_iso_manual_key", "0"));
    }

    public static float getAcesCustomGamma() {
        return getAcesFloat("pref_aces_custom_gamma_key", "2.2");
    }

    public static float getAcesExposure() {
        return Float.parseFloat(getAcesString("pref_aces_exposure_key", "0.0"));
    }

    private static float getAcesFloat(String str, String str2) {
        return Float.parseFloat(getAcesString(str, str2));
    }

    public static int getAcesGammaCurve() {
        return Integer.parseInt(getAcesString("pref_aces_gamma_curve_key", "0"));
    }

    public static float getAcesGamut() {
        return Float.parseFloat(getAcesString("pref_aces_gamut_key", "100")) / 100.0f;
    }

    public static float getAcesHighlightDesat() {
        return Float.parseFloat(getAcesString("pref_aces_highlight_desat_key", "100")) / 100.0f;
    }

    public static float getAcesHueProtection() {
        return getAcesFloat("pref_aces_hue_protection_key", "100") / 100.0f;
    }

    public static float getAcesMidGray() {
        return getAcesFloat("pref_aces_mid_gray_key", "0.18");
    }

    public static int getAcesOutput() {
        return Integer.parseInt(getAcesString("pref_aces_output_key", "0"));
    }

    public static float getAcesPeak() {
        return Float.parseFloat(getAcesString("pref_aces_peak_key", "100.0"));
    }

    public static float getAcesShoulder() {
        return getAcesFloat("pref_aces_shoulder_key", "50") / 100.0f;
    }

    private static String getAcesString(String str, String str2) {
        return preferenceKeys.settingsManager.getString("default_scope", str, str2);
    }

    public static float getAcesSurround() {
        return Float.parseFloat(getAcesString("pref_aces_surround_key", "1.0"));
    }

    public static float getAcesToe() {
        return getAcesFloat("pref_aces_toe_key", "50") / 100.0f;
    }

    public static float getAcesToneContrast() {
        return getAcesFloat("pref_aces_tone_contrast_key", "1.0");
    }

    public static int getAcesToneCurve() {
        return Integer.parseInt(getAcesString("pref_aces_tone_curve_key", "0"));
    }

    public static float getAcesToneMix() {
        return getAcesFloat("pref_aces_tone_mix_key", "100") / 100.0f;
    }

    public static boolean isAcesEnabled() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", "pref_aces_enabled_key", false);
    }

    static {
        COMMON_KEYS.add(Key.CAMERA_ID.mValue);
        COMMON_KEYS.add(Key.KEY_SAVE_PER_LENS_SETTINGS.mValue);
        COMMON_KEYS.add(Key.KEY_SHOW_AF_DATA.mValue);
        COMMON_KEYS.add(Key.KEY_THEME_ACCENT.mValue);
        COMMON_KEYS.add(Key.KEY_THEME.mValue);
        COMMON_KEYS.add(Key.KEY_SHOW_GRID.mValue);
        COMMON_KEYS.add(Key.KEY_SHOW_WATERMARK.mValue);
        COMMON_KEYS.add(Key.KEY_SHOW_ROUND_EDGE.mValue);
        COMMON_KEYS.add(Key.KEY_CAMERA_SOUNDS.mValue);
        COMMON_KEYS.add(Key.KEY_SHOW_GRADIENT.mValue);
        COMMON_KEYS.add(Key.KEY_AF_MODE.mValue);
        COMMON_KEYS.add(Key.KEY_FOCUS_PEAK.mValue);
        COMMON_KEYS.add(Key.KEY_AE_MODE.mValue);
        COMMON_KEYS.add(Key.CAMERA_MODE.mValue);
        COMMON_KEYS.add(Key.KEY_SAVE_RAW.mValue);
    }

    private PreferenceKeys(SettingsManager settingsManager) {
        this.settingsManager = settingsManager;
    }

    public static void initialise(SettingsManager settingsManager) {
        preferenceKeys = new PreferenceKeys(settingsManager);
    }

    public static void setDefaults(Context context) {
        SettingsManager settingsManager = preferenceKeys.settingsManager;
        Resources resources = context.getResources();
        settingsManager.setInitial("default_scope", Key.KEY_HDRX, resources.getBoolean(R.bool.pref_hdrx_mode_default));
        settingsManager.setInitial("default_scope", Key.KEY_EIS_PHOTO, resources.getBoolean(R.bool.pref_eis_photo_default));
        settingsManager.setInitial("default_scope", Key.KEY_QUAD_BAYER, resources.getBoolean(R.bool.pref_quad_bayer_default));
        settingsManager.setInitial("default_scope", Key.KEY_REMOSAIC, resources.getBoolean(R.bool.pref_remosaic_default));
        settingsManager.setInitial("default_scope", Key.KEY_ULTRAHDR, resources.getBoolean(R.bool.pref_ultrahdr_default));
        settingsManager.setInitial("default_scope", Key.KEY_FPS_PREVIEW, 0);
        settingsManager.setInitial("default_scope", Key.KEY_AE_MODE, resources.getString(R.string.pref_ae_mode_default));
        settingsManager.setInitial("default_scope", Key.CAMERA_MODE, resources.getString(R.string.pref_camera_mode_default));
        settingsManager.setInitial("default_scope", Key.KEY_COUNTDOWN_TIMER, 0);
        settingsManager.setInitial("default_scope", Key.KEY_BRACKETING_MODE, 0);
        settingsManager.setInitial("default_scope", Key.KEY_AE_METERING_STD, -1);
        settingsManager.setInitial("default_scope", Key.KEY_VIDEO_RESOLUTION, resources.getString(R.string.pref_video_resolution_default));
        settingsManager.setInitial("default_scope", Key.KEY_RAWVIDEO_DOWNSCALE_4X, false);
        settingsManager.setInitial("default_scope", Key.KEY_RAWVIDEO_WRITE_ZIP, true);
        settingsManager.setInitial("default_scope", Key.KEY_RAWVIDEO_CROP_169, true);
        settingsManager.setDefaults(Key.CAMERA_ID, resources.getString(R.string.camera_id_default), new String[]{"0", "1"});
        settingsManager.setDefaults(Key.TONEMAP, resources.getString(R.string.tonemap_default), new String[]{resources.getString(R.string.tonemap_default)});
        settingsManager.setDefaults(Key.GAMMA, resources.getString(R.string.gamma_default), new String[]{resources.getString(R.string.gamma_default)});
        settingsManager.setDefaults(Key.KEY_SHOW_AF_DATA, "0", new String[]{"0", "1", ExifInterface.GPS_MEASUREMENT_2D, ExifInterface.GPS_MEASUREMENT_3D});
        settingsManager.addListener(new SettingsManager.OnSettingChangedListener() { // from class: com.particlesdevs.photoncamera.settings.PreferenceKeys$$ExternalSyntheticLambda1
            @Override // com.particlesdevs.photoncamera.settings.SettingsManager.OnSettingChangedListener
            public final void onSettingChanged(SettingsManager settingsManager2, String str) {
                PreferenceKeys.lambda$setDefaults$0(settingsManager2, str);
            }
        });
    }

    static /* synthetic */ void lambda$setDefaults$0(SettingsManager settingsManager1, String key) {
        if (key == null) {
            return;
        }
        if (isPerLensSettingsOn()) {
            if (key.equals(Key.CAMERA_ID.mValue)) {
                loadSettingsForCamera(getCameraID());
            }
            if (!COMMON_KEYS.contains(key)) {
                saveJsonForCamera(getCameraID());
            }
        }
        PhotonCamera.getSettings().loadCache();
    }

    public static void addIds(String[] ids) {
        if (ids != null) {
            SettingsManager settingsManager = preferenceKeys.settingsManager;
            Log.d(TAG, "Added IDS:" + Arrays.toString(ids));
            settingsManager.setDefaults(Key.CAMERA_ID, ids[0], ids);
            Map<String, ?> map = settingsManager.getDefaultPreferences().getAll();
            map.keySet().removeAll(COMMON_KEYS);
            map.keySet().removeIf(new Predicate() { // from class: com.particlesdevs.photoncamera.settings.PreferenceKeys$$ExternalSyntheticLambda2
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    return PreferenceKeys.lambda$addIds$1((String) obj);
                }
            });
            String json = GSON.toJson(map);
            for (String cameraId : ids) {
                settingsManager.setInitial(Key.PER_LENS_FILE_NAME.mValue, PER_LENS_KEY_PREFIX + cameraId, json);
            }
        }
    }

    static /* synthetic */ boolean lambda$addIds$1(String key) {
        return key != null && (key.startsWith("pref_tunable_") || key.startsWith("pref_sensorconfig_"));
    }

    private static void saveJsonForCamera(String cameraID) {
        SettingsManager settingsManager = preferenceKeys.settingsManager;
        Map<String, ?> map = settingsManager.getDefaultPreferences().getAll();
        map.keySet().removeAll(COMMON_KEYS);
        map.keySet().removeIf(new Predicate() { // from class: com.particlesdevs.photoncamera.settings.PreferenceKeys$$ExternalSyntheticLambda0
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return PreferenceKeys.lambda$saveJsonForCamera$2((String) obj);
            }
        });
        String hashmapAsJson = GSON.toJson(map);
        String alreadySavedJSON = settingsManager.getString(Key.PER_LENS_FILE_NAME.mValue, PER_LENS_KEY_PREFIX + cameraID, "");
        if (!alreadySavedJSON.equals(hashmapAsJson)) {
            settingsManager.set(Key.PER_LENS_FILE_NAME.mValue, PER_LENS_KEY_PREFIX + getCameraID(), hashmapAsJson);
        }
    }

    static /* synthetic */ boolean lambda$saveJsonForCamera$2(String key) {
        return key != null && (key.startsWith("pref_tunable_") || key.startsWith("pref_sensorconfig_"));
    }

    public static void loadSettingsForCamera(String cameraID) {
        HashMap<String, ?> map;
        SettingsManager settingsManager = preferenceKeys.settingsManager;
        String alreadySavedJSON = settingsManager.getString(Key.PER_LENS_FILE_NAME.mValue, PER_LENS_KEY_PREFIX + cameraID, (String) null);
        if (alreadySavedJSON == null || (map = (HashMap) GSON.fromJson(alreadySavedJSON, HashMap.class)) == null) {
            return;
        }
        for (Map.Entry<String, ?> e : map.entrySet()) {
            String key = e.getKey();
            if (key == null || (!key.startsWith("pref_tunable_") && !key.startsWith("pref_sensorconfig_"))) {
                Object value = e.getValue();
                if (value instanceof Boolean) {
                    value = ((Boolean) value).booleanValue() ? "1" : "0";
                }
                settingsManager.set("default_scope", key, value.toString());
            }
        }
    }

    public static void setActivityTheme(Activity activity) {
        Map<String, Integer> map = new HashMap<>();
        map.put("default", 0);
        map.put("red", Integer.valueOf(R.style.RedTheme));
        map.put("blue", Integer.valueOf(R.style.BlueTheme));
        map.put("orange", Integer.valueOf(R.style.OrangeTheme));
        map.put("green", Integer.valueOf(R.style.GreenTheme));
        map.put("eszdman", Integer.valueOf(R.style.EszdmanTheme));
        map.put("pink", Integer.valueOf(R.style.PinkTheme));
        map.put("cyan", Integer.valueOf(R.style.CyanTheme));
        map.put("teal", Integer.valueOf(R.style.TealTheme));
        map.put("white", Integer.valueOf(R.style.WhiteTheme));
        SettingsManager sm = preferenceKeys.settingsManager;
        String theme = sm.getString("default_scope", Key.KEY_THEME_ACCENT, activity.getResources().getString(R.string.pref_theme_accent_default_value));
        boolean showGradient = sm.getBoolean("default_scope", Key.KEY_SHOW_GRADIENT, activity.getResources().getBoolean(R.bool.pref_show_gradient_def_value));
        if (showGradient) {
            activity.getTheme().applyStyle(R.style.GradientBackgroundTheme, true);
        }
        if (theme != null) {
            Integer themeRes = map.get(theme.toLowerCase());
            activity.getTheme().applyStyle(themeRes != null ? themeRes.intValue() : 0, true);
        }
    }

    public static int getAfDataValue() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_SHOW_AF_DATA).intValue();
    }

    public static boolean isAfDataOn() {
        return getAfDataValue() > 0;
    }

    public static boolean isFullDebugOn() {
        return getAfDataValue() == 3;
    }

    public static boolean isHorizonOn() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", Key.KEY_SHOW_HORIZON);
    }

    public static int isSystemNrOn() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_ENABLE_SYSTEM_NR).intValue();
    }

    public static boolean isRemosaicOn() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", Key.KEY_REMOSAIC);
    }

    public static boolean isDisableAligningOn() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", Key.KEY_DISABLE_ALIGNINIG);
    }

    public static boolean isShowWatermarkOn() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", Key.KEY_SHOW_WATERMARK);
    }

    public static boolean isPerLensSettingsOn() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", Key.KEY_SAVE_PER_LENS_SETTINGS);
    }

    public static boolean isEnhancedProcessionOn() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", Key.KEY_ENHANCED_PROCESSING);
    }

    public static boolean isHdrxNrOn() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", Key.KEY_HDRX_NR);
    }

    public static int isSaveRaw() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_SAVE_RAW).intValue();
    }

    public static boolean isBatterySaverOn() {
        return getBool(Key.KEY_ENERGY_SAVING);
    }

    public static boolean isAspect169On() {
        return getBool(Key.KEY_WIDE169);
    }

    public static boolean isBinningOn() {
        return getBool(Key.KEY_BINNING);
    }

    public static void setBatterySaver(boolean value) {
        preferenceKeys.settingsManager.set("default_scope", Key.KEY_ENERGY_SAVING, value);
    }

    public static void setSaveRaw(int value) {
        preferenceKeys.settingsManager.set("default_scope", Key.KEY_SAVE_RAW, value);
    }

    public static boolean isRoundEdgeOn() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", Key.KEY_SHOW_ROUND_EDGE);
    }

    public static int getGridValue() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_SHOW_GRID).intValue();
    }

    public static void setGridValue(int value) {
        preferenceKeys.settingsManager.set("default_scope", Key.KEY_SHOW_GRID, value);
    }

    public static boolean isCameraSoundsOn() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", Key.KEY_CAMERA_SOUNDS);
    }

    public static int getChromaNrValue() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_CHROMA_NR_SEEKBAR).intValue();
    }

    public static int getLumaNrValue() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_LUMA_NR_SEEKBAR).intValue();
    }

    public static int getFrameCountValue() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_FRAME_COUNT).intValue();
    }

    public static int getZslBufferCountValue() {
        if (preferenceKeys == null || preferenceKeys.settingsManager == null) {
            return 8;
        }
        return Math.max(0, Math.min(100, preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_ZSL_BUFFER_COUNT).intValue()));
    }

    public static int getShortFrameCountValue() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_SHORT_FRAME_COUNT).intValue();
    }

    public static int getLongFrameCountValue() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_LONG_FRAME_COUNT).intValue();
    }

    public static int getShortExposureEvValue() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_SHORT_EXPOSURE_EV).intValue();
    }

    public static int getLongExposureEvValue() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_LONG_EXPOSURE_EV).intValue();
    }

    public static int getHighlightSuppressionValue() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_HIGHLIGHT_SUPPRESSION).intValue();
    }

    public static int getProcessingBackendValue() {
        if (preferenceKeys == null || preferenceKeys.settingsManager == null) {
            return 0;
        }
        int stored = preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_PROCESSING_BACKEND).intValue();
        if (stored == 4) {
            return 2;
        }
        return stored;
    }

    public static boolean isFullGpuProcessing() {
        return (preferenceKeys == null || preferenceKeys.settingsManager == null || preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_PROCESSING_BACKEND).intValue() != 4) ? false : true;
    }

    public static float getSharpnessValue() {
        return preferenceKeys.settingsManager.getFloat("default_scope", Key.KEY_SHARPNESS_SEEKBAR).floatValue();
    }

    public static int getSharpeningMode() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_SHARPENING_ENABLED).intValue();
    }

    private static float sharpFloat(Key key) {
        return preferenceKeys.settingsManager.getFloat("default_scope", key).floatValue();
    }

    private static int sharpInt(Key key) {
        return preferenceKeys.settingsManager.getInteger("default_scope", key).intValue();
    }

    public static float getSharpRadius() {
        return sharpFloat(Key.KEY_SHARP_RADIUS);
    }

    public static float getSharpLensStrength() {
        return sharpFloat(Key.KEY_SHARP_LENS_STRENGTH);
    }

    public static int getSharpLensIterations() {
        return sharpInt(Key.KEY_SHARP_LENS_ITERATIONS);
    }

    public static float getSharpGaussianRadius() {
        return sharpFloat(Key.KEY_SHARP_GAUSSIAN_RADIUS);
    }

    public static float getSharpGaussianAmount() {
        return sharpFloat(Key.KEY_SHARP_GAUSSIAN_AMOUNT);
    }

    public static int getSharpSmartThreshold() {
        return sharpInt(Key.KEY_SHARP_SMART_THRESHOLD);
    }

    public static float getSharpBilateralRadius() {
        return sharpFloat(Key.KEY_SHARP_BILATERAL_RADIUS);
    }

    public static float getSharpGuidedRadius() {
        return sharpFloat(Key.KEY_SHARP_GUIDED_RADIUS);
    }

    public static float getSharpRlRadius() {
        return sharpFloat(Key.KEY_SHARP_RL_RADIUS);
    }

    public static int getSharpThreshold() {
        return sharpInt(Key.KEY_SHARP_THRESHOLD);
    }

    public static float getSharpEdge() {
        return sharpFloat(Key.KEY_SHARP_EDGE);
    }

    public static float getSharpBilateral() {
        return sharpFloat(Key.KEY_SHARP_BILATERAL);
    }

    public static int getSharpTolerance() {
        return sharpInt(Key.KEY_SHARP_TOLERANCE);
    }

    public static float getSharpLocalContrast() {
        return sharpFloat(Key.KEY_SHARP_LOCAL_CONTRAST);
    }

    public static float getSharpEpsilon() {
        return sharpFloat(Key.KEY_SHARP_EPSILON);
    }

    public static float getSharpTexture() {
        return sharpFloat(Key.KEY_SHARP_TEXTURE);
    }

    public static float getSharpGrain() {
        return sharpFloat(Key.KEY_SHARP_GRAIN);
    }

    public static float getSharpRlAmount() {
        return sharpFloat(Key.KEY_SHARP_RL_AMOUNT);
    }

    public static int getSharpRlIterations() {
        return sharpInt(Key.KEY_SHARP_RL_ITERATIONS);
    }

    public static float getSharpDamping() {
        return sharpFloat(Key.KEY_SHARP_DAMPING);
    }

    public static int getSharpShadowProtection() {
        return sharpInt(Key.KEY_SHARP_SHADOW_PROTECTION);
    }

    public static int getSharpHighlightProtection() {
        return sharpInt(Key.KEY_SHARP_HIGHLIGHT_PROTECTION);
    }

    public static int getSharpHaloControl() {
        return sharpInt(Key.KEY_SHARP_HALO_CONTROL);
    }

    public static boolean isFalseColorCorrectionEnabled() {
        return getBool(Key.KEY_FALSE_COLOR_ENABLED);
    }

    public static int getFalseColorStrength() {
        return sharpInt(Key.KEY_FALSE_COLOR_STRENGTH);
    }

    public static int getDefringePurple() {
        return sharpInt(Key.KEY_DEFRINGE_PURPLE);
    }

    public static int getDefringeGreen() {
        return sharpInt(Key.KEY_DEFRINGE_GREEN);
    }

    public static int getCaRed() {
        return sharpInt(Key.KEY_CA_RED);
    }

    public static int getCaBlue() {
        return sharpInt(Key.KEY_CA_BLUE);
    }

    public static float getToneBaseExposure() {
        return sharpFloat(Key.KEY_TONE_BASE_EXPOSURE);
    }

    public static float getToneTargetLuma() {
        return sharpFloat(Key.KEY_TONE_TARGET_LUMA);
    }

    public static float getToneExposureSigma() {
        return sharpFloat(Key.KEY_TONE_EXPOSURE_SIGMA);
    }

    public static float getToneContrastWeight() {
        return sharpFloat(Key.KEY_TONE_CONTRAST_WEIGHT);
    }

    public static int getTonePyramidLevels() {
        return sharpInt(Key.KEY_TONE_PYRAMID_LEVELS);
    }

    public static int getToneResolutionDivisor() {
        return sharpInt(Key.KEY_TONE_RESOLUTION_DIVISOR);
    }

    public static float getToneLocalContrast() {
        return sharpFloat(Key.KEY_TONE_LOCAL_CONTRAST);
    }

    public static float getToneExposureLowLimit() {
        return sharpFloat(Key.KEY_TONE_EXPOSURE_LOW);
    }

    public static float getToneExposureHighLimit() {
        return sharpFloat(Key.KEY_TONE_EXPOSURE_HIGH);
    }

    public static float getToneLaplaceFloor() {
        return sharpFloat(Key.KEY_TONE_LAPLACE_FLOOR);
    }

    public static float getToneHighlightLimit() {
        return sharpFloat(Key.KEY_TONE_HIGHLIGHT_LIMIT);
    }

    public static boolean isNrLumaEnabled() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", Key.KEY_NR_LUMA_ENABLED, true);
    }

    public static boolean isNrChromaEnabled() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", Key.KEY_NR_CHROMA_ENABLED, true);
    }

    public static boolean isNrMoireEnabled() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", Key.KEY_NR_MOIRE_ENABLED, true);
    }

    public static boolean isAiDenoiseEnabled() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", Key.KEY_AI_DENOISE_ENABLED, false);
    }

    public static int getAiDenoiseStrength() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_AI_DENOISE_STRENGTH).intValue();
    }

    public static int getAiDenoiseLuma() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_AI_DENOISE_LUMA).intValue();
    }

    public static int getAiDenoiseChroma() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_AI_DENOISE_CHROMA).intValue();
    }

    public static String getAiDenoiseModel() {
        return preferenceKeys.settingsManager.getString("default_scope", Key.KEY_AI_DENOISE_MODEL, "fast");
    }

    public static boolean isRawMfsrEnabled() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", Key.KEY_RAW_MFSR_ENABLED, false);
    }

    private static float mfsrFloat(Key key, float fallback) {
        try {
            String v = preferenceKeys.settingsManager.getString(
                    "default_scope", key, String.valueOf(fallback));
            return Float.parseFloat(v.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * RAW MFSR kernel regression parameters, after Wronski et al. 2019 section 5.1.
     * They shape the anisotropic Gaussian kernel used to resample the aligned frame:
     * kDetail/kDenoise set its width on detailed and on flat areas, kStretch/kShrink
     * its anisotropy along and across an edge, and Dth/Dtr where the transition
     * between "flat" and "detail" sits on the gradient magnitude.
     */
    public static float getMfsrKDetail()  { return mfsrFloat(Key.KEY_MFSR_K_DETAIL, 0.5f); }
    public static float getMfsrKDenoise() { return mfsrFloat(Key.KEY_MFSR_K_DENOISE, 1.0f); }
    public static float getMfsrKStretch() { return mfsrFloat(Key.KEY_MFSR_K_STRETCH, 4.0f); }
    public static float getMfsrKShrink()  { return mfsrFloat(Key.KEY_MFSR_K_SHRINK, 2.0f); }
    public static float getMfsrDth()      { return mfsrFloat(Key.KEY_MFSR_DTH, 0.005f); }
    public static float getMfsrDtr()      { return mfsrFloat(Key.KEY_MFSR_DTR, 0.02f); }

    /** Coarse-grid spacing for the kernel field, in packed quads (Jiang et al. 2022). */
    public static int getMfsrTensorStride() {
        return Math.max(1, Math.round(mfsrFloat(Key.KEY_MFSR_TENSOR_STRIDE, 8f)));
    }

    /** Gradient noise gate in sigmas for the structure tensor (Liba et al. 2019). */
    public static float getMfsrGradK() { return mfsrFloat(Key.KEY_MFSR_GRAD_K, 2.5f); }

    public static boolean isRaisrEnabled() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", Key.KEY_RAISR_ENABLED, false);
    }

    public static int getRaisrFilterScale() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_RAISR_FILTER_SCALE, 2).intValue();
    }

    public static int getRaisrOutputScale() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_RAISR_OUTPUT_SCALE, 10).intValue();
    }

    public static int getRaisrStrength() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_RAISR_STRENGTH, 70).intValue();
    }

    public static int getRaisrHaloProtection() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_RAISR_HALO, 70).intValue();
    }

    public static int getRaisrAliasingSuppression() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_RAISR_ALIASING, 35).intValue();
    }

    public static String getRaisrMode() {
        return preferenceKeys.settingsManager.getString("default_scope", Key.KEY_RAISR_MODE, "quality");
    }

    public static int getRtLumaDenoise() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_RT_NR_LUMA).intValue();
    }

    public static int getRtChromaDenoise() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_RT_NR_CHROMA).intValue();
    }

    public static int getRtDetailRecovery() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_RT_NR_DETAIL).intValue();
    }

    public static int getRtMoireDenoise() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_RT_NR_MOIRE).intValue();
    }

    public static boolean isCaptureOneEnabled() {
        return getBool(Key.KEY_CAPTURE_ONE_ENABLED);
    }

    public static int getC1Lcc() {
        return sharpInt(Key.KEY_C1_LCC);
    }

    public static int getC1LccRed() {
        return sharpInt(Key.KEY_C1_LCC_RED);
    }

    public static int getC1LccBlue() {
        return sharpInt(Key.KEY_C1_LCC_BLUE);
    }

    public static int getC1Clarity() {
        return sharpInt(Key.KEY_C1_CLARITY);
    }

    public static int getC1Structure() {
        return sharpInt(Key.KEY_C1_STRUCTURE);
    }

    public static int getC1Moire() {
        return sharpInt(Key.KEY_C1_MOIRE);
    }

    public static int getC1SinglePixel() {
        return sharpInt(Key.KEY_C1_SINGLE_PIXEL);
    }

    public static int getC1Skin() {
        return sharpInt(Key.KEY_C1_SKIN);
    }

    public static int getC1ColorHue() {
        return sharpInt(Key.KEY_C1_COLOR_HUE);
    }

    public static int getC1ColorRange() {
        return sharpInt(Key.KEY_C1_COLOR_RANGE);
    }

    public static int getC1ColorShift() {
        return sharpInt(Key.KEY_C1_COLOR_SHIFT);
    }

    public static int getC1ColorSaturation() {
        return sharpInt(Key.KEY_C1_COLOR_SATURATION);
    }

    public static int getC1Shadows() {
        return sharpInt(Key.KEY_C1_SHADOWS);
    }

    public static int getC1Midtones() {
        return sharpInt(Key.KEY_C1_MIDTONES);
    }

    public static int getC1Highlights() {
        return sharpInt(Key.KEY_C1_HIGHLIGHTS);
    }

    public static String getZslMergeAlgorithm() {
        return preferenceKeys.settingsManager.getString("default_scope", Key.KEY_ZSL_MERGE_ALGORITHM, "legacy");
    }

    public static String getNightMergeAlgorithm() {
        return preferenceKeys.settingsManager.getString("default_scope", Key.KEY_NIGHT_MERGE_ALGORITHM, "legacy");
    }

    public static int getHdrPlusDenoiseStrength() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_HDRPLUS_DENOISE_STRENGTH, 100).intValue();
    }

    public static int getHdrPlusLowDenoise() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_HDRPLUS_LOW_DENOISE, 100).intValue();
    }

    public static int getHdrPlusHighDenoise() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_HDRPLUS_HIGH_DENOISE, 100).intValue();
    }

    public static int getHdrPlusChromaDenoise() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_HDRPLUS_CHROMA_DENOISE, 100).intValue();
    }

    public static boolean isHdrPlusMergeEnabled() {
        CameraMode mode = CameraMode.valueOf(getCameraModeOrdinal());
        return "hdrplus".equals(mode == CameraMode.NIGHT ? getNightMergeAlgorithm() : getZslMergeAlgorithm());
    }

    public static float getCompressorValue() {
        return preferenceKeys.settingsManager.getFloat("default_scope", Key.KEY_COMPRESSOR_SEEKBAR).floatValue();
    }

    public static float getGainValue() {
        return preferenceKeys.settingsManager.getFloat("default_scope", Key.KEY_GAIN_SEEKBAR).floatValue();
    }

    public static float getSaturationValue() {
        return preferenceKeys.settingsManager.getFloat("default_scope", Key.KEY_SATURATION_SEEKBAR).floatValue();
    }

    public static float getContrastValue() {
        return preferenceKeys.settingsManager.getFloat("default_scope", Key.KEY_CONTRAST_SEEKBAR).floatValue();
    }

    public static int getAlignMethodValue() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_ALIGN_METHOD).intValue();
    }

    public static int getColorMethodValue() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_COLOR_METHOD).intValue();
    }

    public static int getFocusPeakValue() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_FOCUS_PEAK).intValue();
    }

    public static int getPreviewFormatValue() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_PREVIEW_FORMAT).intValue();
    }

    public static int getCFAValue() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_CFA).intValue();
    }

    public static int getThemeValue() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_THEME).intValue();
    }

    public static boolean isHdrXOn() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", Key.KEY_HDRX);
    }

    public static void setHdrX(boolean value) {
        preferenceKeys.settingsManager.set("default_scope", Key.KEY_HDRX, value);
    }

    public static boolean isEisPhotoOn() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", Key.KEY_EIS_PHOTO);
    }

    public static void setEisPhoto(boolean value) {
        preferenceKeys.settingsManager.set("default_scope", Key.KEY_EIS_PHOTO, value);
    }

    public static int getFpsMode() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_FPS_PREVIEW).intValue();
    }

    public static void setFpsMode(int value) {
        preferenceKeys.settingsManager.set("default_scope", Key.KEY_FPS_PREVIEW, value);
    }

    public static boolean isQuadBayerOn() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", Key.KEY_QUAD_BAYER);
    }

    public static boolean isUltraHdrOn() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", Key.KEY_ULTRAHDR);
    }

    public static void setQuadBayer(boolean value) {
        preferenceKeys.settingsManager.set("default_scope", Key.KEY_QUAD_BAYER, value);
    }

    public static String getCameraID() {
        return preferenceKeys.settingsManager.getString(Key.CAMERAS_PREFERENCE_FILE_NAME.mValue, Key.CAMERA_ID);
    }

    public static int getCountdownTimerIndex() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_COUNTDOWN_TIMER).intValue();
    }

    public static void setCountdownTimerIndex(int valueMS) {
        preferenceKeys.settingsManager.set("default_scope", Key.KEY_COUNTDOWN_TIMER, valueMS);
    }

    public static int getBracketingMode() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_BRACKETING_MODE).intValue();
    }

    public static void setBracketingMode(int value) {
        preferenceKeys.settingsManager.set("default_scope", Key.KEY_BRACKETING_MODE, value);
    }

    public static int getAeMeteringStd() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_AE_METERING_STD).intValue();
    }

    public static void setAeMeteringStd(int value) {
        preferenceKeys.settingsManager.set("default_scope", Key.KEY_AE_METERING_STD, value);
    }

    public static void setCameraID(String value) {
        preferenceKeys.settingsManager.set(Key.CAMERAS_PREFERENCE_FILE_NAME.mValue, Key.CAMERA_ID, value);
    }

    public static int getAfMode() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_AF_MODE).intValue();
    }

    public static int getAeMode() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_AE_MODE).intValue();
    }

    public static void setAeMode(int value) {
        preferenceKeys.settingsManager.set("default_scope", Key.KEY_AE_MODE, value);
    }

    public static int getCameraModeOrdinal() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.CAMERA_MODE).intValue();
    }

    public static void setCameraModeOrdinal(int value) {
        preferenceKeys.settingsManager.set("default_scope", Key.CAMERA_MODE, value);
    }

    public static String getToneMap() {
        return preferenceKeys.settingsManager.getString("default_scope", Key.TONEMAP);
    }

    public static String getPref(Key key) {
        return preferenceKeys.settingsManager.getString("default_scope", key);
    }

    public static Set<String> getStringSet(Key key) {
        return preferenceKeys.settingsManager.getStringSet("default_scope", key, new HashSet(0));
    }

    public static boolean getBool(Key key) {
        return preferenceKeys.settingsManager.getBoolean("default_scope", key);
    }

    public static float getFloat(Key key) {
        return preferenceKeys.settingsManager.getFloat("default_scope", key).floatValue();
    }

    public static String getVideoResolution() {
        return preferenceKeys.settingsManager.getString("default_scope", Key.KEY_VIDEO_RESOLUTION, "1920x1080");
    }

    public static void setVideoResolution(String value) {
        preferenceKeys.settingsManager.set("default_scope", Key.KEY_VIDEO_RESOLUTION, value);
    }

    public static boolean isRawVideoDownscale4x() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", Key.KEY_RAWVIDEO_DOWNSCALE_4X);
    }

    public static boolean isRawVideoWriteZip() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", Key.KEY_RAWVIDEO_WRITE_ZIP);
    }

    public static boolean isRawVideoCrop169() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", Key.KEY_RAWVIDEO_CROP_169);
    }

    public enum Key {
        KEY_PREF_VERSION(R.string._pref_version),
        KEY_ENABLE_SYSTEM_NR(R.string.pref_enable_system_nr_key),
        KEY_SAVE_PER_LENS_SETTINGS(R.string.pref_save_per_lens_settings),
        KEY_DISABLE_ALIGNINIG(R.string.pref_disable_aligning_key),
        KEY_SHOW_WATERMARK(R.string.pref_show_watermark_key),
        KEY_ENERGY_SAVING(R.string.pref_energy_safe_key),
        KEY_WIDE169(R.string.pref_wide169_key),
        KEY_BINNING(R.string.pref_binning_key),
        KEY_ENHANCED_PROCESSING(R.string.pref_enhanced_processing_key),
        KEY_HDRX_NR(R.string.pref_hdrx_nr_key),
        KEY_SHOW_ROUND_EDGE(R.string.pref_show_roundedge_key),
        KEY_SHOW_GRID(R.string.pref_show_grid_key),
        KEY_CAMERA_SOUNDS(R.string.pref_camera_sounds_key),
        KEY_CHROMA_NR_SEEKBAR(R.string.pref_chroma_nr_seekbar_key),
        KEY_LUMA_NR_SEEKBAR(R.string.pref_luma_nr_seekbar_key),
        KEY_COMPRESSOR_SEEKBAR(R.string.pref_compressor_seekbar_key),
        KEY_NOISESTR_SEEKBAR(R.string.pref_noise_seekbar_key),
        KEY_MERGE_SEEKBAR(R.string.pref_merge_seekbar_key),
        KEY_GAIN_SEEKBAR(R.string.pref_gain_seekbar_key),
        KEY_SHADOWS_SEEKBAR(R.string.pref_shadows_seekbar_key),
        KEY_FRAME_COUNT(R.string.pref_frame_count_key),
        KEY_ZSL_BUFFER_COUNT(R.string.pref_zsl_buffer_count_key),
        KEY_SHORT_FRAME_COUNT(R.string.pref_short_frame_count_key),
        KEY_LONG_FRAME_COUNT(R.string.pref_long_frame_count_key),
        KEY_SHORT_EXPOSURE_EV(R.string.pref_short_exposure_ev_key),
        KEY_LONG_EXPOSURE_EV(R.string.pref_long_exposure_ev_key),
        KEY_HIGHLIGHT_SUPPRESSION(R.string.pref_highlight_suppression_key),
        KEY_PROCESSING_BACKEND(R.string.pref_processing_backend_key),
        KEY_CONTRAST_SEEKBAR(R.string.pref_contrast_seekbar_key),
        KEY_SHARPNESS_SEEKBAR(R.string.pref_sharpness_seekbar_key),
        KEY_SHARPENING_ENABLED(R.string.pref_sharpening_enabled_key),
        KEY_SHARP_RADIUS(R.string.pref_sharp_radius_key),
        KEY_SHARP_LENS_STRENGTH(R.string.pref_sharp_lens_strength_key),
        KEY_SHARP_LENS_ITERATIONS(R.string.pref_sharp_lens_iterations_key),
        KEY_SHARP_GAUSSIAN_RADIUS(R.string.pref_sharp_gaussian_radius_key),
        KEY_SHARP_GAUSSIAN_AMOUNT(R.string.pref_sharp_gaussian_amount_key),
        KEY_SHARP_SMART_THRESHOLD(R.string.pref_sharp_smart_threshold_key),
        KEY_SHARP_BILATERAL_RADIUS(R.string.pref_sharp_bilateral_radius_key),
        KEY_SHARP_GUIDED_RADIUS(R.string.pref_sharp_guided_radius_key),
        KEY_SHARP_RL_RADIUS(R.string.pref_sharp_rl_radius_key),
        KEY_SHARP_THRESHOLD(R.string.pref_sharp_threshold_key),
        KEY_SHARP_EDGE(R.string.pref_sharp_edge_key),
        KEY_SHARP_BILATERAL(R.string.pref_sharp_bilateral_key),
        KEY_SHARP_TOLERANCE(R.string.pref_sharp_tolerance_key),
        KEY_SHARP_LOCAL_CONTRAST(R.string.pref_sharp_local_contrast_key),
        KEY_SHARP_EPSILON(R.string.pref_sharp_epsilon_key),
        KEY_SHARP_TEXTURE(R.string.pref_sharp_texture_key),
        KEY_SHARP_GRAIN(R.string.pref_sharp_grain_key),
        KEY_SHARP_RL_AMOUNT(R.string.pref_sharp_rl_amount_key),
        KEY_SHARP_RL_ITERATIONS(R.string.pref_sharp_rl_iterations_key),
        KEY_SHARP_DAMPING(R.string.pref_sharp_damping_key),
        KEY_SHARP_SHADOW_PROTECTION(R.string.pref_sharp_shadow_protection_key),
        KEY_SHARP_HIGHLIGHT_PROTECTION(R.string.pref_sharp_highlight_protection_key),
        KEY_SHARP_HALO_CONTROL(R.string.pref_sharp_halo_control_key),
        KEY_FALSE_COLOR_ENABLED(R.string.pref_false_color_enabled_key),
        KEY_FALSE_COLOR_STRENGTH(R.string.pref_false_color_strength_key),
        KEY_DEFRINGE_PURPLE(R.string.pref_defringe_purple_key),
        KEY_DEFRINGE_GREEN(R.string.pref_defringe_green_key),
        KEY_CA_RED(R.string.pref_ca_red_key),
        KEY_CA_BLUE(R.string.pref_ca_blue_key),
        KEY_TONE_BASE_EXPOSURE(R.string.pref_tone_base_exposure_key),
        KEY_TONE_TARGET_LUMA(R.string.pref_tone_target_luma_key),
        KEY_TONE_EXPOSURE_SIGMA(R.string.pref_tone_exposure_sigma_key),
        KEY_TONE_CONTRAST_WEIGHT(R.string.pref_tone_contrast_weight_key),
        KEY_TONE_PYRAMID_LEVELS(R.string.pref_tone_pyramid_levels_key),
        KEY_TONE_RESOLUTION_DIVISOR(R.string.pref_tone_resolution_divisor_key),
        KEY_TONE_LOCAL_CONTRAST(R.string.pref_tone_local_contrast_key),
        KEY_TONE_EXPOSURE_LOW(R.string.pref_tone_exposure_low_key),
        KEY_TONE_EXPOSURE_HIGH(R.string.pref_tone_exposure_high_key),
        KEY_TONE_LAPLACE_FLOOR(R.string.pref_tone_laplace_floor_key),
        KEY_TONE_HIGHLIGHT_LIMIT(R.string.pref_tone_highlight_limit_key),
        KEY_NR_LUMA_ENABLED(R.string.pref_nr_luma_enabled_key),
        KEY_NR_CHROMA_ENABLED(R.string.pref_nr_chroma_enabled_key),
        KEY_NR_MOIRE_ENABLED(R.string.pref_nr_moire_enabled_key),
        KEY_AI_DENOISE_ENABLED(R.string.pref_ai_denoise_enabled_key),
        KEY_AI_DENOISE_STRENGTH(R.string.pref_ai_denoise_strength_key),
        KEY_AI_DENOISE_LUMA(R.string.pref_ai_denoise_luma_key),
        KEY_AI_DENOISE_CHROMA(R.string.pref_ai_denoise_chroma_key),
        KEY_AI_DENOISE_MODEL(R.string.pref_ai_denoise_model_key),
        KEY_RAW_MFSR_ENABLED(R.string.pref_raw_mfsr_enabled_key),
        KEY_MFSR_K_DETAIL(R.string.pref_mfsr_k_detail_key),
        KEY_MFSR_K_DENOISE(R.string.pref_mfsr_k_denoise_key),
        KEY_MFSR_K_STRETCH(R.string.pref_mfsr_k_stretch_key),
        KEY_MFSR_K_SHRINK(R.string.pref_mfsr_k_shrink_key),
        KEY_MFSR_DTH(R.string.pref_mfsr_dth_key),
        KEY_MFSR_DTR(R.string.pref_mfsr_dtr_key),
        KEY_MFSR_TENSOR_STRIDE(R.string.pref_mfsr_tensor_stride_key),
        KEY_MFSR_GRAD_K(R.string.pref_mfsr_grad_k_key),
        KEY_RAISR_ENABLED(R.string.pref_raisr_enabled_key),
        KEY_RAISR_FILTER_SCALE(R.string.pref_raisr_filter_scale_key),
        KEY_RAISR_OUTPUT_SCALE(R.string.pref_raisr_output_scale_key),
        KEY_RAISR_STRENGTH(R.string.pref_raisr_strength_key),
        KEY_RAISR_HALO(R.string.pref_raisr_halo_key),
        KEY_RAISR_ALIASING(R.string.pref_raisr_aliasing_key),
        KEY_RAISR_MODE(R.string.pref_raisr_mode_key),
        KEY_RT_NR_LUMA(R.string.pref_rt_nr_luma_key),
        KEY_RT_NR_CHROMA(R.string.pref_rt_nr_chroma_key),
        KEY_RT_NR_DETAIL(R.string.pref_rt_nr_detail_key),
        KEY_RT_NR_MOIRE(R.string.pref_rt_nr_moire_key),
        KEY_CAPTURE_ONE_ENABLED(R.string.pref_capture_one_enabled_key),
        KEY_C1_LCC(R.string.pref_c1_lcc_key),
        KEY_C1_LCC_RED(R.string.pref_c1_lcc_red_key),
        KEY_C1_LCC_BLUE(R.string.pref_c1_lcc_blue_key),
        KEY_C1_CLARITY(R.string.pref_c1_clarity_key),
        KEY_C1_STRUCTURE(R.string.pref_c1_structure_key),
        KEY_C1_MOIRE(R.string.pref_c1_moire_key),
        KEY_C1_SINGLE_PIXEL(R.string.pref_c1_single_pixel_key),
        KEY_C1_SKIN(R.string.pref_c1_skin_key),
        KEY_C1_COLOR_HUE(R.string.pref_c1_color_hue_key),
        KEY_C1_COLOR_RANGE(R.string.pref_c1_color_range_key),
        KEY_C1_COLOR_SHIFT(R.string.pref_c1_color_shift_key),
        KEY_C1_COLOR_SATURATION(R.string.pref_c1_color_saturation_key),
        KEY_C1_SHADOWS(R.string.pref_c1_shadows_key),
        KEY_C1_MIDTONES(R.string.pref_c1_midtones_key),
        KEY_C1_HIGHLIGHTS(R.string.pref_c1_highlights_key),
        KEY_ZSL_MERGE_ALGORITHM(R.string.pref_zsl_merge_algorithm_key),
        KEY_NIGHT_MERGE_ALGORITHM(R.string.pref_night_merge_algorithm_key),
        KEY_HDRPLUS_DENOISE_STRENGTH(R.string.pref_hdrplus_denoise_strength_key),
        KEY_HDRPLUS_LOW_DENOISE(R.string.pref_hdrplus_low_denoise_key),
        KEY_HDRPLUS_HIGH_DENOISE(R.string.pref_hdrplus_high_denoise_key),
        KEY_HDRPLUS_CHROMA_DENOISE(R.string.pref_hdrplus_chroma_denoise_key),
        KEY_EXPOCOMPENSATE_SEEKBAR(R.string.pref_expocompensation_seekbar_key),
        KEY_SATURATION_SEEKBAR(R.string.pref_saturation_seekbar_key),
        KEY_ALIGN_METHOD(R.string.pref_align_method_key),
        KEY_COLOR_METHOD(R.string.pref_color_method_key),
        KEY_FOCUS_PEAK(R.string.pref_peak_method_key),
        KEY_PREVIEW_FORMAT(R.string.pref_preview_format_key),
        KEY_TELEGRAM(R.string.pref_telegram_channel_key),
        KEY_CONTRIBUTORS(R.string.pref_contributors_key),
        KEY_THEME(R.string.pref_theme_key),
        KEY_THEME_ACCENT(R.string.pref_theme_accent_key),
        KEY_SHOW_GRADIENT(R.string.pref_show_gradient_key),
        KEY_HIDE_GALLERY_ICON(R.string.pref_hide_gallery_icon_key),
        KEY_AF_MODE(R.string.pref_af_mode_key),
        KEY_AE_MODE(R.string.pref_ae_mode_key),
        KEY_AE_METERING_STD(R.string.pref_ae_metering_std_key),
        KEY_BRACKETING_MODE(R.string.pref_bracketing_key),
        KEY_COUNTDOWN_TIMER(R.string.pref_countdown_timer_key),
        KEY_PREVIEW_RESOLUTION(R.string.pref_preview_resolution_key),
        KEY_VIDEO_RESOLUTION(R.string.pref_video_resolution_key),
        KEY_RAWVIDEO_DOWNSCALE_4X(R.string.pref_rawvideo_downscale_4x_key),
        KEY_RAWVIDEO_WRITE_ZIP(R.string.pref_rawvideo_write_zip_key),
        KEY_RAWVIDEO_CROP_169(R.string.pref_rawvideo_crop_169_key),
        KEY_SHOW_AF_DATA(R.string.pref_show_afdata_key),
        KEY_SHOW_HORIZON(R.string.pref_horizon),
        KEY_SAVE_RAW(R.string.pref_save_raw_key),
        KEY_CFA(R.string.pref_cfa_key),
        KEY_REMOSAIC(R.string.pref_remosaic_key),
        KEY_HDRX(R.string.pref_hdrx_key),
        KEY_EIS_PHOTO(R.string.pref_eis_photo_key),
        KEY_QUAD_BAYER(R.string.pref_quad_bayer_key),
        KEY_FPS_PREVIEW(R.string.pref_fps_preview_key),
        KEY_ULTRAHDR(R.string.pref_ultrahdr_key),
        CAMERA_ID(R.string.camera_id),
        TONEMAP(R.string.tonemap_key),
        GAMMA(R.string.gamma_key),
        CAMERA_MODE(R.string.pref_camera_mode_key),
        CAMERAS_PREFERENCE_FILE_NAME(R.string._cameras),
        ALL_CAMERA_IDS_KEY(R.string.all_camera_ids),
        FRONT_IDS_KEY(R.string.front_camera_ids),
        BACK_IDS_KEY(R.string.back_camera_ids),
        ALL_CAMERA_LENS_KEY(R.string.all_camera_lens),
        CAMERA_COUNT_KEY(R.string.all_camera_count),
        DEVICES_PREFERENCE_FILE_NAME(R.string._devices),
        ALL_DEVICES_NAMES_KEY(R.string.all_devices_names),
        PER_LENS_FILE_NAME(R.string._per_lens),
        FOLDERS_LIST(R.string.pref_folders_list);

        public final String mValue;

        Key(int stringId) {
            this.mValue = PhotonCamera.getStringStatic(stringId);
        }
    }
}

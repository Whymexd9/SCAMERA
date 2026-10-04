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
        return SettingsNumericRules.normalized(str, preferenceKeys.settingsManager.getString("default_scope", str, str2), str2);
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
        COMMON_KEYS.add("settings_audit_schema");
        COMMON_KEYS.add(Key.FOLDERS_LIST.mValue);
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
        moduleProfiles = null;
    }

    private static ModuleProfiles moduleProfiles;
    public static ModuleProfiles profiles() {
        if (moduleProfiles == null) moduleProfiles = new ModuleProfiles(preferenceKeys.settingsManager);
        return moduleProfiles;
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
        if(profiles().isApplying())return;
        profiles().changed(key);
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
        profiles().activate(cameraID);
    }

    public static void setActivityTheme(Activity activity) {
        Map<String, Integer> map = new HashMap<>();
        map.put("default", R.style.LavenderAccentTheme);
        map.put("lavender", R.style.LavenderAccentTheme);
        map.put("amber", R.style.AmberAccentTheme);
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

    /** Signature text lines (default "SHOT ON" / "SCAMERA"); an empty line is left out. */
    public static String getWatermarkLine1() {
        return preferenceKeys.settingsManager.getString("default_scope", "pref_watermark_line1", "SHOT ON");
    }
    public static String getWatermarkLine2() {
        return preferenceKeys.settingsManager.getString("default_scope", "pref_watermark_line2", "SCAMERA");
    }
    public static boolean isWatermarkLogoOn() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", "pref_watermark_logo", true);
    }
    /** Height of the signature in percent of the frame height (stock: 6.7). */
    public static float getWatermarkHeightPercent() {
        return (float) SettingsNumericRules.value("pref_watermark_size",
                preferenceKeys.settingsManager.getString("default_scope", "pref_watermark_size", "7"), 7);
    }
    public static float getWatermarkOpacity() {
        return (float) SettingsNumericRules.value("pref_watermark_opacity",
                preferenceKeys.settingsManager.getString("default_scope", "pref_watermark_opacity", "100"), 100) / 100f;
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
        return Math.max(isVivoNiceEnabled() ? 4 : isVivoHdrEnabled() ? 3 : 1, preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_FRAME_COUNT).intValue());
    }

    public static int getShortFrameCountValue() {
        return Math.max(isVivoHdrEnabled() ? 1 : 0, preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_SHORT_FRAME_COUNT).intValue());
    }

    public static int getLongFrameCountValue() {
        return Math.max(isVivoNiceEnabled() ? 1 : 0, preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_LONG_FRAME_COUNT).intValue());
    }

    public static int getShortExposureEvValue() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_SHORT_EXPOSURE_EV).intValue();
    }

    public static int getLongExposureEvValue() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_LONG_EXPOSURE_EV).intValue();
    }

    public static int getHighlightSuppressionValue() {
        return Math.max(isVivoHdrEnabled() ? 100 : 0, preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_HIGHLIGHT_SUPPRESSION).intValue());
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

    private static float sharpFloat(Key key) {
        return preferenceKeys.settingsManager.getFloat("default_scope", key).floatValue();
    }

    private static int sharpInt(Key key) {
        return preferenceKeys.settingsManager.getInteger("default_scope", key).intValue();
    }

    public static boolean isRemosaicEnabled() {
        // SCAM HDR on a mosaic stream owns the remosaic itself (VivoNiceMosaic): the stored switches of the module
        // stay as they are, but every other branch sees a plain-bayer pipeline.
        if (isNiceMosaic()) return false;
        return isSabreEnabled() ? getMultiFrameBlock() > 1
                : !isRawMfsrEnabled() && getBool(Key.KEY_REMOSAIC_ENABLED);
    }

    /** Samples per colour block: 2 quad bayer, 4 tetra squared. */
    public static int getRemosaicBlockSize() {
        if (isNiceMosaic()) return niceMosaicBlock();
        return isSabreEnabled() ? getMultiFrameBlock() : sharpInt(Key.KEY_REMOSAIC_BLOCK) == 2 ? 2 : 4;
    }

    /**
     * SCAM HDR on a Quad / Tetra stream (the ISZ modules): off (plain Bayer only, as before), scamera (GPU remosaic of
     * every frame), detail (Tetra Detail v2 on every frame), mfr (Multi-frame Remosaic of the N frames), neural
     * (the NPU quad / HexQuad models on the N frames); the short and long frames always take the GPU remosaic.
     */
    public static String niceMosaicMode() {
        String mode = preferenceKeys.settingsManager.getString("default_scope", "pref_vivo_nice_mosaic", "off");
        return mode == null ? "off" : mode;
    }

    /** The Sabre kernel over the frames' own mosaic samples (sabre / neural_sabre): the donors of the merge are the raw Quad / Tetra sites. */
    public static boolean isNiceMosaicSabre() {
        String mode = niceMosaicMode();
        return "sabre".equals(mode) || "neural_sabre".equals(mode);
    }

    /** How the plain-bayer burst for the network is built: scamera / detail / mfr / neural (sabre = scamera, neural_sabre = neural). */
    public static String niceMosaicBase() {
        String mode = niceMosaicMode();
        return "sabre".equals(mode) ? "scamera" : "neural_sabre".equals(mode) ? "neural" : mode;
    }

    /** Raw preferences only: this is consulted by isRemosaicEnabled() and isVivoHdrEnabled(). */
    public static boolean isNiceMosaic() {
        return !"off".equals(niceMosaicMode()) && isGcamStageEnabled("pref_vivo_hdr_enabled")
                && isGcamStageEnabled("pref_vivo_nice_enabled") && !isRawMfsrEnabled();
    }

    /** Colour block of the module's mosaic: from its forced sensor mode (7 = Tetra 4x4, 5 = Quad 2x2), else the remosaic block. */
    public static int niceMosaicBlock() {
        int forced = Math.round(scamInternalValue("mosaic_block", 0f)); // SCAM HDR only: the hybrid merges plain Bayer
        if (forced == 2 || forced == 4) return forced;
        int mode = ModuleRegistry.sensorMode(ModuleRegistry.active());
        if (mode == 7) return 4;
        if (mode == 5) return 2;
        return sharpInt(Key.KEY_REMOSAIC_BLOCK) == 2 ? 2 : 4;
    }

    /** Colour-block side of the RAW stream for statistics and the raw viewfinder: 1 for plain bayer. */
    public static int mosaicBlock() {
        return isRawMfsrEnabled() ? getMultiFrameBlock() : (isRemosaicEnabled() || isNiceMosaic()) ? getRemosaicBlockSize() : 1;
    }

    /** Interpolate green along edges instead of across them. */
    public static boolean isRemosaicSteered() {
        return getBool(Key.KEY_REMOSAIC_STEERED);
    }

    /** Bound interpolated colour differences by the measured ones nearby. */
    public static boolean isRemosaicClampDiffs() {
        return getBool(Key.KEY_REMOSAIC_CLAMP);
    }

    /** Save the node's own output as a DNG, for isolating it from later stages. */
    public static boolean isRemosaicDump() {
        return getBool(Key.KEY_REMOSAIC_DUMP);
    }

    /** Divide out the per-site response profile inside a colour block. */
    public static boolean isRemosaicFlatField() {
        return getBool(Key.KEY_REMOSAIC_FLATFIELD);
    }

    /** 0 nearest, 1 sharp, 2 balanced, 3 smooth. */
    public static boolean isTetraResponseCorrection() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", "pref_tetra_response_key", true);
    }

    public static String getRemosaicBackend() {
        if(isNiceMosaic()) return "detail".equals(niceMosaicMode()) && niceMosaicBlock() == 4 ? "tetra_detail" : "scamera";
        if(isSabreEnabled()) return "scamera";
        return preferenceKeys.settingsManager.getString("default_scope", Key.KEY_REMOSAIC_BACKEND, "scamera");
    }

    /** Separate six-frame experimental HP9 path, before ordinary RAW fusion. */
    public static boolean isHexQuadCaptureEnabled() {
        return isRemosaicEnabled() && "hp9_hexquad".equals(getRemosaicBackend());
    }

    /** Main camera 2x2 Quad (2x ISZ): vendor IMX06C quad model, four equal RAWs. */
    public static boolean isQuadNeuralCaptureEnabled() {
        return isRemosaicEnabled() && "imx06c_quad".equals(getRemosaicBackend());
    }

    /** Either NPU burst remosaic: the burst is equal-exposure and owned by the worker. */
    public static boolean isNeuralBurstRemosaic() {
        return isHexQuadCaptureEnabled() || isQuadNeuralCaptureEnabled();
    }

    public static int neuralBurstFrames() {
        return isQuadNeuralCaptureEnabled() ? getQuadFrames() : getHexQuadFrames();
    }

    /** Hybrid reconstruction weights; not exposed parameters of the closed neural model. */
    public static float getHexQuadLuma() {
        return RawTherapeeSettings.number("hexquad_luma",50,0,100);
    }

    public static float getHexQuadChroma() {
        return RawTherapeeSettings.number("hexquad_chroma",100,0,100);
    }

    public static float getHexQuadExposureEv() {
        return RawTherapeeSettings.number("hexquad_exposure_ev",0,-2,2);
    }

    public static int getHexQuadModelScale() {
        return "1".equals(preferenceKeys.settingsManager.getString("default_scope","hexquad_model","2"))?1:2;
    }

    public static boolean isHexQuadAutoIso() {
        return preferenceKeys.settingsManager.getBoolean("default_scope","hexquad_auto_iso",false);
    }

    public static HexQuadOptions getHexQuadOptions(int iso) {
        return new HexQuadOptions(iso,getHexQuadModelScale(),
                preferenceKeys.settingsManager.getBoolean("default_scope","hexquad_full_resolution",false),
                RawTherapeeSettings.number("hexquad_noise_overall",1,.5f,2),
                RawTherapeeSettings.number("hexquad_noise_photon",1,.5f,2),
                RawTherapeeSettings.number("hexquad_noise_readout",1,.5f,2),
                getHexQuadLuma(),getHexQuadChroma(),isHexQuadAutoIso(),
                RawTherapeeSettings.number("hexquad_iso_low_luma",35,0,100),
                RawTherapeeSettings.number("hexquad_iso_low_chroma",85,0,100),
                RawTherapeeSettings.number("hexquad_iso_high_luma",70,0,100),
                RawTherapeeSettings.number("hexquad_iso_high_chroma",100,0,100),
                RawTherapeeSettings.number("hexquad_texture",0,0,100),
                true /* Unified hybrid; legacy hexquad_compute selection is no longer used. */);
    }

    /** Quad 2x2 (2x ISZ) denoise controls: same meaning as the HexQuad ones, own keys. */
    public static HexQuadOptions getQuadOptions(int iso) {
        return new HexQuadOptions(iso,1,false,
                RawTherapeeSettings.number("quad2x2_noise_overall",1,.5f,2),
                RawTherapeeSettings.number("quad2x2_noise_photon",1,.5f,2),
                RawTherapeeSettings.number("quad2x2_noise_readout",1,.5f,2),
                RawTherapeeSettings.number("quad2x2_luma",50,0,100),
                RawTherapeeSettings.number("quad2x2_chroma",100,0,100),
                preferenceKeys.settingsManager.getBoolean("default_scope","quad2x2_auto_iso",false),
                RawTherapeeSettings.number("quad2x2_iso_low_luma",35,0,100),
                RawTherapeeSettings.number("quad2x2_iso_low_chroma",85,0,100),
                RawTherapeeSettings.number("quad2x2_iso_high_luma",70,0,100),
                RawTherapeeSettings.number("quad2x2_iso_high_chroma",100,0,100),
                0f,false);
    }

    public static float getQuadExposureEv() {
        return RawTherapeeSettings.number("quad2x2_exposure_ev",0,-2,2);
    }

    public static boolean isQuadPostDenoiseEnabled() {
        return preferenceKeys.settingsManager.getBoolean("default_scope","quad2x2_post_denoise",false);
    }

    public static boolean isHexQuadPostDenoiseEnabled() {
        return preferenceKeys.settingsManager.getBoolean("default_scope","hexquad_post_denoise",false);
    }

    public static int getRemosaicProfile() {
        return Math.max(0, Math.min(3, sharpInt(Key.KEY_REMOSAIC_PROFILE)));
    }

    /** Mosaic phase; the block grid does not always start at pixel 0. */
    public static int[] getRemosaicPhase() {
        return new int[]{ sharpInt(Key.KEY_REMOSAIC_PHASE_X), sharpInt(Key.KEY_REMOSAIC_PHASE_Y) };
    }

    public static boolean isSharpUsmEnabled() {
        return getBool(Key.KEY_SHARP_USM_ENABLED);
    }

    public static boolean isSharpDeconvEnabled() {
        return getBool(Key.KEY_SHARP_DECONV_ENABLED);
    }

    public static boolean isSharpMicroEnabled() {
        return getBool(Key.KEY_SHARP_MICRO_ENABLED);
    }

    public static float getSharpRadius() {
        return sharpFloat(Key.KEY_SHARP_RADIUS);
    }

    public static float getSharpAmount() {
        return sharpFloat(Key.KEY_SHARP_AMOUNT) * hybridSharpStrength();
    }

    public static float getSharpContrast() {
        return sharpFloat(Key.KEY_SHARP_CONTRAST);
    }

    public static int getSharpThresholdBottomLeft() {
        return sharpInt(Key.KEY_SHARP_THRESHOLD_BOTTOM_LEFT);
    }

    public static int getSharpThresholdTopLeft() {
        return sharpInt(Key.KEY_SHARP_THRESHOLD_TOP_LEFT);
    }

    public static int getSharpThresholdTopRight() {
        return sharpInt(Key.KEY_SHARP_THRESHOLD_TOP_RIGHT);
    }

    public static int getSharpThresholdBottomRight() {
        return sharpInt(Key.KEY_SHARP_THRESHOLD_BOTTOM_RIGHT);
    }

    public static boolean isSharpEdgesOnly() {
        return getBool(Key.KEY_SHARP_EDGES_ONLY);
    }

    public static float getSharpEdgesRadius() {
        return sharpFloat(Key.KEY_SHARP_EDGES_RADIUS);
    }

    public static int getSharpEdgesTolerance() {
        return sharpInt(Key.KEY_SHARP_EDGES_TOLERANCE);
    }

    public static boolean isSharpHaloControl() {
        return getBool(Key.KEY_SHARP_HALO_CONTROL);
    }

    public static float getSharpHaloAmount() {
        return sharpFloat(Key.KEY_SHARP_HALO_AMOUNT);
    }

    private static Key stageKey(Key k1, Key k2, Key k3, int stage) {
        return stage == 2 ? k2 : (stage == 3 ? k3 : k1);
    }

    /** 0 gaussian, 1 pillbox (defocus), 2 Airy (diffraction). */
    public static int getSharpDeconvKernel(int stage) {
        return sharpInt(stageKey(Key.KEY_SHARP_DECONV_KERNEL_1,
                Key.KEY_SHARP_DECONV_KERNEL_2, Key.KEY_SHARP_DECONV_KERNEL_3, stage));
    }

    public static float getSharpDeconvRadius(int stage) {
        return sharpFloat(stageKey(Key.KEY_SHARP_DECONV_RADIUS_1,
                Key.KEY_SHARP_DECONV_RADIUS_2, Key.KEY_SHARP_DECONV_RADIUS_3, stage));
    }

    public static float getSharpDeconvAmount(int stage) {
        return sharpFloat(stageKey(Key.KEY_SHARP_DECONV_AMOUNT_1,
                Key.KEY_SHARP_DECONV_AMOUNT_2, Key.KEY_SHARP_DECONV_AMOUNT_3, stage));
    }

    public static int getSharpDeconvIterations(int stage) {
        return sharpInt(stageKey(Key.KEY_SHARP_DECONV_ITERATIONS_1,
                Key.KEY_SHARP_DECONV_ITERATIONS_2, Key.KEY_SHARP_DECONV_ITERATIONS_3, stage));
    }

    public static float getSharpDeconvDamping(int stage) {
        return sharpFloat(stageKey(Key.KEY_SHARP_DECONV_DAMPING_1,
                Key.KEY_SHARP_DECONV_DAMPING_2, Key.KEY_SHARP_DECONV_DAMPING_3, stage));
    }

    public static float getSharpDeconvHalo() {
        return sharpFloat(Key.KEY_SHARP_DECONV_HALO);
    }

    public static float getSharpDeconvHaloMargin() {
        return sharpFloat(Key.KEY_SHARP_DECONV_HALO_MARGIN);
    }

    public static float getSharpDeconvHaloMacro() {
        return sharpFloat(Key.KEY_SHARP_DECONV_HALO_MACRO);
    }

    public static float getSharpMicroAmount() {
        return sharpFloat(Key.KEY_SHARP_MICRO_AMOUNT) * hybridSharpStrength();
    }

    public static int getSharpMicroUniformity() {
        return sharpInt(Key.KEY_SHARP_MICRO_UNIFORMITY);
    }

    public static float getSharpMicroContrast() {
        return sharpFloat(Key.KEY_SHARP_MICRO_CONTRAST);
    }

    public static boolean isSharpMicroMatrix3x3() {
        return getBool(Key.KEY_SHARP_MICRO_MATRIX_3X3);
    }

    /** NICE: flush the HAL request queue before the bracket (shutter lag ~0.4 s -> ~0.15 s). */
    public static boolean isNiceFastCapture() {
        if (isHybridShot()) return hybridSwitch("fast_capture", true);
        return preferenceKeys.settingsManager.getBoolean("default_scope", "pref_nice_fast_capture", true);
    }

    public static boolean isZslQualitySelectionEnabled() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", "pref_zsl_quality_selection_key", false);
    }

    public static boolean isSaliencyProtectionEnabled() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", "pref_saliency_protection_key", false);
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

    public static boolean isNrLumaEnabled() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", Key.KEY_NR_LUMA_ENABLED, true);
    }

    public static boolean isNrChromaEnabled() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", Key.KEY_NR_CHROMA_ENABLED, true);
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

    /**
     * The vivo-style processing route (own merge, SCAMERA tone, no second HDR+ denoise): the LMC hybrid (its own switch,
     * section «LMC-гибрид») or the autonomous HDR / SCAM HDR switches. Exclusive with the native remosaic engines and
     * RAW MFSR; stored choices are retained.
     */
    public static boolean isVivoHdrEnabled() {
        return isLmcHybridEnabled() || isAutonomousHdrSwitchOn();
    }
    /** pref_vivo_hdr_enabled on a RAW path the autonomous HDR can merge (SCAM HDR's own switch, without the hybrid). */
    private static boolean isAutonomousHdrSwitchOn() {
        return isGcamStageEnabled("pref_vivo_hdr_enabled") && isVivoRouteCompatible();
    }
    /** Plain Bayer or the SCAMERA remosaic, no RAW MFSR: what both the hybrid and SCAM HDR merge. */
    private static boolean isVivoRouteCompatible() {
        return !isRawMfsrEnabled() && (!isRemosaicEnabled() || "scamera".equals(getRemosaicBackend()));
    }
    public static boolean isNiceDespeckleEnabled() {
        if (isHybridShot()) return hybridSwitch("despeckle", true);
        return preferenceKeys.settingsManager.getBoolean("default_scope", "pref_vivo_nice_post_despeckle", true);
    }
    public static boolean isNiceDiagnosticsEnabled() {
        if (isHybridShot()) return hybridSwitch("diagnostics", false);
        return preferenceKeys.settingsManager.getBoolean("default_scope", "pref_vivo_nice_diagnostics", true);
    }
    /** true: vivo stock AE solver via root observer; false: SCAMERA planner (no root, any device). */
    /** LMC curve presets: "off" or an asset path under assets/curves (Tone/..., Gamma/...). */
    public static String getLmcToneCurve() {
        return preferenceKeys.settingsManager.getString("default_scope", "pref_lmc_tone_curve", "off");
    }
    public static String getLmcGammaCurve() {
        return preferenceKeys.settingsManager.getString("default_scope", "pref_lmc_gamma_curve", "off");
    }
    /** Curve strength as a 0..1 blend with the identity. */
    public static float getLmcToneCurveStrength() {
        return RawTherapeeSettings.number("pref_lmc_tone_curve_strength", 100, 0, 100) / 100f;
    }
    public static float getLmcGammaCurveStrength() {
        return RawTherapeeSettings.number("pref_lmc_gamma_curve_strength", 100, 0, 100) / 100f;
    }

    /**
     * Optional root features (off by default; everything works without root):
     * the vivo stock-AE observer for the NICE bracket, and su as the fallback
     * launcher when the worker is not installed as a native library.
     */
    public static boolean isRootEnabled() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", "pref_root_enabled", false);
    }
    public static boolean useStockBracketPlanner() {
        // The hybrid's N frames are the ZSL ring at the preview exposure: always the SCAMERA plan of that exposure.
        if (isHybridShot()) return false;
        return isRootEnabled()
                && "stock".equals(preferenceKeys.settingsManager.getString("default_scope", "pref_vivo_nice_planner", "stock"))
                && com.particlesdevs.photoncamera.capture.VivoStockAe.supportedDevice();
    }
    /** NICE: build L from the ZSL N frames instead of capturing it after the shutter. */
    public static boolean isNiceZslLong() {
        if (isHybridShot()) return false; // the hybrid takes no L frame (Shasta brackets after the press instead)
        return preferenceKeys.settingsManager.getBoolean("default_scope", "pref_nice_zsl_long", true);
    }
    public static boolean isNicePlannerAdaptive() {
        if (isHybridShot()) return hybridSwitch("planner_adaptive", true);
        return preferenceKeys.settingsManager.getBoolean("default_scope", "pref_vivo_nice_planner_adaptive", true);
    }
    /** NICE noise profile source: auto | imx06c | camera2 | settings. */
    public static String getNiceNoiseSource() {
        if (isHybridShot()) return hybridString("noise_source", "auto");
        return preferenceKeys.settingsManager.getString("default_scope", "pref_vivo_nice_noise_source", "auto");
    }
    /** RAW lens shading map: auto | apply | skip (HAL already corrected RAW_SENSOR). */
    public static String getRawLscMode() {
        return preferenceKeys.settingsManager.getString("default_scope", "pref_raw_lsc_mode", "auto");
    }
    /** Lower the reported RAW black level to the frame's measured dark floor when it is higher. */
    public static boolean isRawBlackFromData() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", "pref_raw_black_from_data", true);
    }
    /** Exposure Fusion may only brighten; highlights are left to the tone shoulder. */
    public static boolean isNiceFusionLiftOnly() {
        if (isHybridShot()) return hybridSwitch("fusion_lift_only", true);
        return preferenceKeys.settingsManager.getBoolean("default_scope", "pref_vivo_nice_fusion_lift_only", true);
    }
    /** NICE motion (CRE) source: auto (vendor, else APK copy) | bundled (always APK copy). */
    public static String getNiceCreSource() {
        if (isHybridShot()) return hybridString("cre_source", "auto");
        return preferenceKeys.settingsManager.getString("default_scope", "pref_vivo_nice_cre_source", "auto");
    }
    public static boolean isNiceFusionEnabled() {
        if (isHybridShot()) return hybridSwitch("fusion_enabled", true);
        return preferenceKeys.settingsManager.getBoolean("default_scope", "pref_vivo_nice_fusion_enabled", true);
    }
    /** ISO level 1..5 of the NICE normal reference, as in the settings screen. */
    public static int niceIsoLevel(int iso) {
        return iso <= 200 ? 1 : iso <= 800 ? 2 : iso <= 3200 ? 3 : iso <= 12800 ? 4 : 5;
    }
    /** Luma/chroma strength inside NICE (0..2, 1 = model as trained), with the ISO-level multiplier. */
    public static float getNiceLuma(int iso) {
        return Math.max(0f, Math.min(2f, niceInternalValue("luma", 1f) * niceInternalValue("luma_iso" + niceIsoLevel(iso), 1f)));
    }
    public static float getNiceChroma(int iso) {
        return Math.max(0f, Math.min(2f, niceInternalValue("chroma", 1f) * niceInternalValue("chroma_iso" + niceIsoLevel(iso), 1f)));
    }
    /** The SoC the vivo NICE network was built for (Hexagon v79): SCAM HDR (NICE) runs there; the LMC hybrid runs anywhere. */
    public static boolean isVivoNetSoc() {
        return LmcHybridKeys.vivoNetSoc();
    }

    // ---- LMC hybrid: a route of its own (section «LMC-гибрид», lmc_hybrid_screen), separate from SCAM HDR ----

    /**
     * Prefix of every LMC hybrid preference: pref_lmc_hybrid_&lt;key&gt;. A new hybrid setting gets a key with this prefix in
     * res/xml/preferences.xml (category of lmc_hybrid_screen), numeric bounds in SettingsNumericRules, and is read with
     * {@link #hybridValue}, {@link #hybridSwitch}, {@link #hybridString} or {@link #hybridList}.
     */
    public static final String HYBRID_PREFIX = LmcHybridKeys.PREFIX;
    /** Master switch of the LMC hybrid; independent of SCAM HDR (pref_vivo_hdr_enabled / pref_vivo_nice_enabled). */
    public static final String HYBRID_ENABLED_KEY = LmcHybridKeys.ENABLED;
    /** Hybrid defaults that differ from the fallback the shared nodes pass; any other key keeps the caller's fallback. */
    private static final Map<String, Float> HYBRID_DEFAULTS = new HashMap<>();
    static {
        HYBRID_DEFAULTS.put("zsl_frames", 20f);  // the Sabre kernel shows detail from ~12-15 frames (SCAM HDR NICE: 4)
        HYBRID_DEFAULTS.put("soft_tone", 0f);    // AgX + Exposure Fusion with the Bento headroom
        HYBRID_DEFAULTS.put("bento_factor", 8f); // LMC ultrashort_tet_factor
    }

    /** Fresh-install state of the hybrid's switch (written once by SettingsMigration), see {@link LmcHybridKeys#defaultOn()}. */
    public static boolean hybridDefaultOn() {
        return LmcHybridKeys.defaultOn();
    }
    /** The hybrid's own switch, pref_lmc_hybrid_enabled; nice_dev.txt "hybrid 0/1" overrides it for A/B tests. */
    public static boolean isLmcHybridSwitchOn() {
        Float override = niceDevValue("hybrid");
        if (override != null) return override > 0f;
        return preferenceKeys.settingsManager.getBoolean("default_scope", HYBRID_ENABLED_KEY, false);
    }
    /**
     * The LMC hybrid (Sabre 6.1 kernel x LMC 9.6 rejection x Bento x Shasta, any GLES 3.1 GPU) takes the shot: its switch
     * is on and the RAW path is plain Bayer or the SCAMERA remosaic without RAW MFSR. Independent of SCAM HDR and wins over
     * it when both are on; only a Quad/Tetra stream of SCAM HDR's mosaic mode (ISZ modules) stays with the network route,
     * which converts the mosaic to Bayer first (the hybrid merges plain Bayer only).
     */
    public static boolean isLmcHybridEnabled() {
        return isLmcHybridSwitchOn() && isVivoRouteCompatible() && !isNiceMosaic();
    }
    /** SCAM HDR's switches are on (autonomous HDR + SCAM HDR RAW), whether or not the hybrid takes the shot. */
    public static boolean isScamHdrSwitchOn() {
        return isAutonomousHdrSwitchOn() && isGcamStageEnabled("pref_vivo_nice_enabled");
    }
    /** SCAM HDR (the vivo NICE network) takes the shot: its switches are on and the hybrid does not take it. */
    public static boolean isScamHdrNiceEnabled() {
        return isScamHdrSwitchOn() && !isLmcHybridEnabled();
    }
    /**
     * The NICE capture route is used (ZSL RAW ring, SCAMERA plan, worker, SCAMERA tone), by the LMC hybrid or by SCAM HDR;
     * {@link #isLmcHybridEnabled()} and {@link #isScamHdrNiceEnabled()} tell them apart.
     */
    public static boolean isVivoNiceEnabled() {
        return isLmcHybridEnabled() || isScamHdrSwitchOn();
    }

    private static final int SHOT_SCAM = 1, SHOT_HYBRID = 2;
    /**
     * Per-shot settings profile of the thread that processes the shot: HdrxProcessor sets it around its whole processing
     * (merge, post pipeline, saving) and clears it afterwards. The nodes shared by SCAM HDR and the hybrid then read the
     * knobs of the route that merged the shot, even when the switches changed between the press and the processing.
     * Thread-confined on purpose: the camera thread plans and captures the next shot (and the viewfinder runs) while the
     * NICE processing thread still works on the previous one, and those must keep reading the live route.
     */
    private static final ThreadLocal<Integer> shotProfile = new ThreadLocal<>();
    public static void beginShotProfile(boolean hybrid) { shotProfile.set(hybrid ? SHOT_HYBRID : SHOT_SCAM); }
    public static void endShotProfile() { shotProfile.remove(); }
    /**
     * The LMC hybrid's settings are in force: on a thread processing a shot, whether the hybrid merged it; on any other
     * thread (viewfinder, capture and plan of the next shot) whether the hybrid takes the next shot. Then every SCAM HDR
     * getter of this class (niceInternalValue/Switch, vivoHdrValue, fusion, despeckle, noise and CRE source, ...) reads the
     * hybrid's copy pref_lmc_hybrid_&lt;key&gt; instead of the SCAM HDR key.
     */
    public static boolean isHybridShot() {
        final Integer profile = shotProfile.get();
        return profile != null ? profile == SHOT_HYBRID : isLmcHybridEnabled();
    }
    /** A hybrid shot is being post-processed on this thread (strict: false outside HdrxProcessor). */
    public static boolean isHybridShotProcessing() {
        final Integer profile = shotProfile.get();
        return profile != null && profile == SHOT_HYBRID;
    }
    /** @deprecated the hybrid is not a SCAM HDR engine any more: {@link #isHybridShot()} (per shot) or {@link #isLmcHybridEnabled()}. */
    @Deprecated
    public static boolean isNiceHybridEnabled() { return isHybridShot(); }

    /**
     * Output of the hybrid merge (pref_lmc_hybrid_output): "sensor" (1x grid, default), "12"/"16"/"20" = Sabre 6.1 2x
     * grid resized to that many megapixels (4:3 of the sensor; the sensor size itself when within 10 %), "2x" = the native
     * 2x grid. nice_dev.txt: "hybrid_output 0|12|16|20|2".
     */
    public static String hybridOutputMode() {
        Float dev = niceDevValue("hybrid_output");
        if (dev != null) { int v = Math.round(dev); return v == 2 ? "2x" : v == 12 || v == 16 || v == 20 ? String.valueOf(v) : "sensor"; }
        return hybridString("output", "sensor");
    }
    /**
     * Downsampler of the final bitmap after the 2x pipeline (pref_lmc_hybrid_downsampler): "lanczos" (3 lobes),
     * "bicubic" (Catmull-Rom), "area" (box average), "bilinear". nice_dev.txt: "hybrid_downsampler 0..3".
     */
    public static String hybridDownsampler() {
        Float dev = niceDevValue("hybrid_downsampler");
        if (dev != null) { int v = Math.round(dev); return v == 1 ? "bicubic" : v == 2 ? "area" : v == 3 ? "bilinear" : "lanczos"; }
        return hybridString("downsampler", "lanczos");
    }
    private static final String[] HYBRID_OUTPUT_VALUES = {"sensor", "12", "16", "20", "2x"};
    private static final String[] HYBRID_DOWNSAMPLER_VALUES = {"lanczos", "bicubic", "area", "bilinear"};
    /** Index of pref_lmc_hybrid_output in {sensor, 12, 16, 20, 2x} for the quick-settings chips. */
    public static int hybridOutputIndex() {
        String m = hybridOutputMode();
        for (int i = 0; i < HYBRID_OUTPUT_VALUES.length; i++) if (HYBRID_OUTPUT_VALUES[i].equals(m)) return i;
        return 0;
    }
    public static void setHybridOutputIndex(int index) {
        setHybridValue("output", HYBRID_OUTPUT_VALUES[Math.max(0, Math.min(HYBRID_OUTPUT_VALUES.length - 1, index))]);
    }
    public static int hybridDownsamplerIndex() {
        String m = hybridDownsampler();
        for (int i = 0; i < HYBRID_DOWNSAMPLER_VALUES.length; i++) if (HYBRID_DOWNSAMPLER_VALUES[i].equals(m)) return i;
        return 0;
    }
    public static void setHybridDownsamplerIndex(int index) {
        setHybridValue("downsampler", HYBRID_DOWNSAMPLER_VALUES[Math.max(0, Math.min(HYBRID_DOWNSAMPLER_VALUES.length - 1, index))]);
    }
    public static String hybridDownsamplerName() {
        switch (hybridDownsampler()) { case "bicubic": return "Bicubic"; case "area": return "Area"; case "bilinear": return "Bilinear"; default: return "Lanczos-3"; }
    }
    /** Final JPEG size for the sensor size w x h: sensor, 12/16/20 MP (4:3 of the sensor) or the native 2x grid. */
    public static android.graphics.Point hybridFinalSize(int w, int h) {
        String mode = hybridOutputMode();
        if ("2x".equals(mode)) return new android.graphics.Point(2 * w, 2 * h);
        if ("12".equals(mode) || "16".equals(mode) || "20".equals(mode)) return hybridTargetSize(Integer.parseInt(mode), w, h);
        return new android.graphics.Point(w, h);
    }
    public static android.graphics.Point hybridTargetSize(int megapixels, int w, int h) {
        final double aspect = (double) w / h, pixels = megapixels * 1_000_000.0;
        // Classic sizes: 4:3 widths on a 64 px step keep both sides on the 16 px JPEG MCU grid (4032x3024, 4608x3456, 5184x3888).
        final int step = Math.abs(aspect - 4.0 / 3.0) < 0.01 ? 64 : 16;
        int tw = (int) Math.round(Math.sqrt(pixels * aspect) / step) * step;
        int th = (int) Math.round(tw / aspect / 16) * 16;
        if (Math.abs((long) tw * th - (long) w * h) < 0.1 * w * h) { tw = w; th = h; } // the sensor size counts as its own megapixel class
        tw = Math.max(64, Math.min(2 * w, tw)); th = Math.max(64, Math.min(2 * h, th));
        return new android.graphics.Point(tw, th);
    }
    /** "key value" lines for the worker's hybrid_tuning.txt, from the pref_lmc_hybrid_* preferences (nice_dev.txt "hybrid_<key>"). */
    public static String hybridTuningText() {
        StringBuilder out = new StringBuilder();
        String[][] keys = {
            {"bento", "bento"},
            {"cdm", "cdm"}, {"boostEnable", "boost"},
            {"kernelScale", "kernel"}, {"weightCap", "weight_cap"},
            {"fwe", "fwe"}, {"dilateScale", "dilate"},
            {"shastaSharpness", "shasta_sharpness"}, {"bentoUsWeight", "bento_weight"},
            {"shastaMaxRatio", "shasta_max_ratio"}, {"filterVariance", "filter_variance"},
            {"bentoUsSigma", "bento_sigma"}, {"bentoFrames", "bento_frames"}, {"bentoChromaSigma", "bento_chroma_sigma"},
            {"dilateFloor", "dilate_floor"}, {"widenBelow", "widen_below"}, {"chromaDiff", "chroma_diff"},
            {"bentoValidate", "bento_validate"},
            {"rawNoise", "tensor_noise"}, {"snrScale", "snr_scale"},
            {"boost", "boost_value"}, {"varianceThreshold", "boost_threshold"},
            {"lutHiSigma", "lut_sigma"},
            // Round 5 (research/hybrid5/impl_worker.md): Sabre 6.1 kernel 0 off / 1 always / 2 auto (night: 6.1 SNR key <= s61MaxKey),
            // highlights and outliers (hot*), LMC 9.6 Bento fallback checks. Absent keys keep the worker defaults.
            {"sabre61", "sabre61"}, {"s61MaxKey", "s61_max_key"}, {"s61Mode", "s61_mode"},
            // F6 tile-local alignment (research/hybrid5/f6_local_align.md): 0 off / 1 bilinear field / 2 constant per tile;
            // the 6.1 auto rule in daylight needs F6 and this much RMS donor motion; daylight 6.1 noise multipliers.
            {"localAlign", "local_align"}, {"s61MinMotion", "s61_min_motion"},
            {"s61DayTensorNoise", "s61_day_tensor_noise"}, {"s61DayGdNoise", "s61_day_gd_noise"},
            {"s61TensorNoise", "s61_tensor_noise"}, {"s61GdNoise", "s61_gd_noise"},
            {"hotSigma", "hot_sigma"}, {"hotBaseSigma", "hot_base_sigma"}, {"hotFrames", "hot_frames"},
            {"hotCross", "hot_cross"}, {"hotMaxLevel", "hot_max_level"}, {"hotMaxKey", "hot_max_key"},
            {"bentoInvalid", "bento_invalid"},
        };
        for (String[] k : keys) {
            Float dev = niceDevValue("hybrid_" + k[1]);
            String v = dev != null ? dev.toString() : hybridString(k[1], "");
            if (v == null || v.isEmpty()) continue;
            try { out.append(k[0]).append(' ').append(Float.parseFloat(v.trim())).append('\n'); } catch (NumberFormatException ignored) {}
        }
        if (!hybridSwitch("shasta", true)) out.append("shastaEnable 0\n");
        // Switches of the worker's round-5 fixes (on by default there): written only when turned off.
        if (!hybridSwitch("cell_clip", true)) out.append("cellClip 0\n");
        if (!hybridSwitch("chroma_diff_clamp", true)) out.append("chromaDiffClamp 0\n");
        if (!hybridSwitch("bento_lmc", true)) out.append("bentoLmc 0\n");
        return out.toString();
    }
    /** The hybrid's N frames from the ZSL ring (pref_lmc_hybrid_zsl_frames, 4..44, default 20). */
    public static int getHybridZslFrames() {
        return Math.round(hybridValue("zsl_frames", 20f));
    }
    /**
     * LMC hybrid tone: the ArkCam 1.23 / LMC 9.6 photo tone (ArkStats -> ArkFusion -> ArkCombine: Smart-HDR AE, exposure
     * fusion on a guided filter, OKLab grading, AgX Custom, detail of the Sabre merge) instead of the SCAMERA exposure,
     * Exposure Fusion and AgX render. pref_lmc_hybrid_ark_tone, on by default; nice_dev.txt "hybrid_ark_tone 0/1".
     * Its knobs are pref_lmc_hybrid_ark_&lt;key&gt; (read with {@link #hybridValue}, defaults = ArkCam 2.85 X8U).
     */
    /**
     * Quick buttons of the settings sheet (pinned parameters): SettingType names, comma separated, oldest first; null when
     * the user never pinned anything. Key ui_sheet_quick: a "pref_" key would be a per-module setting that ModuleProfiles
     * swaps on a lens change, the pins would then follow the lens.
     */
    public static String getSheetQuick() {
        try {
            return preferenceKeys.settingsManager.getString("default_scope", "ui_sheet_quick", null);
        } catch (RuntimeException error) { return null; }
    }
    public static void setSheetQuick(String types) {
        try {
            preferenceKeys.settingsManager.set("default_scope", "ui_sheet_quick", types);
        } catch (RuntimeException ignored) {}
    }
    /**
     * Master switch of every noise reduction of the hybrid (pref_lmc_hybrid_denoise, on by default; nice_dev.txt
     * "hybrid_denoise 0/1"): off skips the denoise after the merge (GCam/LMC finish or NLM, despeckle), the Bento
     * highlight denoise of the ARK fusion and the chroma denoise of lifted shadows. The merge itself is untouched.
     */
    public static boolean isHybridDenoiseEnabled() {
        return hybridSwitch("denoise", true);
    }
    public static boolean isArkToneEnabled() {
        return hybridSwitch("ark_tone", true);
    }
    /**
     * Sharpening of the SCAM HDR / hybrid render: "ark" (hybrid with the ARK tone: ArkCam's own luma sharpening before the
     * detail delta, ArkLumaSharpen; RawTherapee without the ARK tone), "rt" (RawTherapee with its own settings), "scam"
     * (NiceSharpen) or "off". Hybrid: pref_lmc_hybrid_sharp_mode (nice_dev.txt "hybrid_sharp_mode 0|1|2|3" = rt, scam,
     * off, ark), default ark. SCAM HDR: NiceSharpen with its soft tone and sharp_mode > 0, else RawTherapee.
     */
    public static String niceSharpenMode() {
        if (isHybridShot()) {
            Float dev = niceDevValue("hybrid_sharp_mode");
            if (dev != null) return dev >= 2.5f ? "ark" : dev >= 1.5f ? "off" : dev >= 0.5f ? "scam" : "rt";
            String v = hybridString("sharp_mode", "ark");
            return "rt".equals(v) || "scam".equals(v) || "off".equals(v) ? v : "ark";
        }
        return isNiceSoftTone() && niceInternalValue("sharp_mode", 1f) > 0f ? "scam" : "rt";
    }
    /** RawTherapee USM / microcontrast amount multiplier on a hybrid shot (pref_lmc_hybrid_sharp_strength), 1 elsewhere. */
    private static float hybridSharpStrength() {
        return isHybridShotProcessing() ? Math.max(0f, Math.min(2f, hybridValue("sharp_strength", 1f))) : 1f;
    }
    /**
     * Share of the scene illuminant's colour kept in SCAM HDR (0..1). -1 = auto: 30 % on the 8 Gen 3
     * (matches GCam/stock there); 35 % where the original network runs (vivo, the colour temperature
     * from the shot's white balance): a lamp-lit scene stays a little warmer than the stock render.
     */
    public static float getNiceWarmRetention() {
        float v = niceInternalValue("warm_retention", -1f);
        if (v < 0f) v = !isVivoNetSoc() ? 30f : 35f;
        return Math.max(-0.6f, Math.min(1f, v / 100f));
    }
    /**
     * How far the SCAM HDR tone is pulled to a GCam/LMC render (0..1): darker shadows, brighter whites.
     * -1 = auto: on where the original network runs (vivo), where the fused tone is much brighter
     * than GCam; the 8 Gen 3 render already agrees with it.
     */
    public static float getNiceGcamTone() {
        float v = niceInternalValue("gcam_tone", -1f);
        if (v < 0f) v = !isVivoNetSoc() ? 0f : 100f;
        return Math.max(0f, Math.min(1f, v / 100f));
    }
    /** Mid-frequency local contrast (texture) of the SCAM HDR render, 0..2 (1 = matched to a GCam/LMC render). */
    public static float getNiceTexture() {
        return Math.max(0f, Math.min(2f, niceInternalValue("texture", 1f)));
    }
    /**
     * SCAM HDR soft tone (a GCam/LMC-like render: scene-keyed exposure, toe + soft shoulder, no clipping of the
     * whites, shadows lifted only when the scene needs it). 0 = the former AgX/fusion/LMC-curve stack.
     */
    public static boolean isNiceSoftTone() {
        // The LMC hybrid renders through AgX + Exposure Fusion with the Bento headroom (until the ARK tone replaces it).
        if (isHybridShot()) return false;
        return niceInternalValue("soft_tone", 1f) > 0f;
    }
    /** Weight of the other burst frames in the NICE reference, 0..1 (1 = all frames). */
    public static float getNiceMerge() {
        return Math.max(0f, Math.min(1f, niceInternalValue("merge", 100f) / 100f));
    }
    /** Extra EV for the NICE long frame over the stock plan (0 = stock). */
    public static float getNiceLongBoostEv() {
        return niceInternalValue("long_boost_ev", 1.1f);
    }
    /** Normal-exposure N frames taken from the ZSL ring: SCAM HDR 4..50; on a hybrid shot the hybrid's own count. */
    public static int getNiceZslFrames() {
        return Math.round(niceInternalValue("zsl_frames", 4f));
    }

    /** Equal-exposure RAWs for the neural remosaics: HP9 HexQuad 6..50, Quad 2x2 4..50. */
    public static int getHexQuadFrames() {
        return (int)Math.round(SettingsNumericRules.value("pref_hexquad_frames",
                preferenceKeys.settingsManager.getString("default_scope","pref_hexquad_frames","6"),6));
    }

    public static int getQuadFrames() {
        return (int)Math.round(SettingsNumericRules.value("pref_quad_frames",
                preferenceKeys.settingsManager.getString("default_scope","pref_quad_frames","4"),4));
    }

    private static long niceDevStamp = -1, niceDevChecked;
    private static java.util.Map<String, Float> niceDevValues = java.util.Collections.emptyMap();
    /**
     * Developer overrides for the NICE internal values: "key value" lines in nice_dev.txt of the app's external files
     * dir (key without the pref_vivo_nice_ prefix) replace the preference for the next shot. Re-read when the file
     * changes, at most every 2 s.
     */
    private static Float niceDevValue(String key) {
        long now = System.nanoTime();
        if (niceDevStamp == -1 || now - niceDevChecked > 2_000_000_000L) {
            niceDevChecked = now;
            java.util.Map<String, Float> values = java.util.Collections.emptyMap();
            long stamp = 0;
            try {
                java.io.File dir = com.particlesdevs.photoncamera.app.PhotonCamera.getAppContext().getExternalFilesDir(null);
                java.io.File file = dir == null ? null : new java.io.File(dir, "nice_dev.txt");
                if (file != null && file.isFile()) {
                    stamp = file.lastModified();
                    if (stamp == niceDevStamp) return niceDevValues.get(key);
                    values = new java.util.HashMap<>();
                    for (String line : new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8).split("\r?\n")) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length == 2) try { values.put(parts[0], Float.parseFloat(parts[1])); } catch (NumberFormatException ignored) { }
                    }
                }
            } catch (java.io.IOException | RuntimeException ignored) { }
            niceDevStamp = stamp;
            niceDevValues = values;
        }
        return niceDevValues.get(key);
    }

    /**
     * LMC hybrid number pref_lmc_hybrid_&lt;key&gt;, clamped by its SettingsNumericRules bounds; nice_dev.txt
     * "hybrid_&lt;key&gt; v" overrides it; unset or invalid: the hybrid default of the key, else {@code fallback}.
     * Independent of the shot profile and of SCAM HDR.
     */
    public static float hybridValue(String key, float fallback) {
        Float override = niceDevValue("hybrid_" + key);
        if (override != null) return override;
        return hybridStored(key, fallback);
    }
    /** LMC hybrid switch pref_lmc_hybrid_&lt;key&gt; (Boolean or "0"/"1"); nice_dev.txt "hybrid_&lt;key&gt; 0/1" overrides it. */
    public static boolean hybridSwitch(String key, boolean fallback) {
        Float override = niceDevValue("hybrid_" + key);
        if (override != null) return override > 0f;
        return hybridStoredSwitch(key, fallback);
    }
    /**
     * nice_dev.txt overrides of hybrid list settings for A/B without the menu: "hybrid_&lt;key&gt; i" picks the i-th entry value
     * (order of arrays.xml), e.g. "hybrid_dn_engine 1" = nlm. Other list keys have their own getters (output, downsampler,
     * sharp_mode) or none.
     */
    private static final Map<String, String[]> HYBRID_DEV_LISTS = new HashMap<>();
    static {
        HYBRID_DEV_LISTS.put("dn_engine", new String[]{"gcam", "nlm"});
        HYBRID_DEV_LISTS.put("dn_strength_map", new String[]{"auto", "frames", "uniform"});
    }
    /** LMC hybrid text value pref_lmc_hybrid_&lt;key&gt; (a ListPreference entry value), {@code fallback} when unset. */
    public static String hybridString(String key, String fallback) {
        final String[] devList = HYBRID_DEV_LISTS.get(key);
        if (devList != null) {
            Float dev = niceDevValue("hybrid_" + key);
            if (dev != null) {
                int i = Math.round(dev);
                if (i >= 0 && i < devList.length) return devList[i];
            }
        }
        try {
            String v = preferenceKeys.settingsManager.getString("default_scope", HYBRID_PREFIX + key, fallback);
            return v == null || v.trim().isEmpty() ? fallback : v.trim();
        } catch (RuntimeException error) { return fallback; }
    }
    /**
     * LMC hybrid number list pref_lmc_hybrid_&lt;key&gt; = "a,b,c,..." (e.g. a value per noise level), each value clamped by
     * {@link SettingsNumericRules#listBounds}; {@code fallback} when unset, of another length or not a number.
     */
    public static float[] hybridList(String key, float[] fallback) {
        final String full = HYBRID_PREFIX + key;
        String v;
        try { v = preferenceKeys.settingsManager.getString("default_scope", full, ""); }
        catch (RuntimeException error) { v = ""; }
        return SettingsNumericRules.listValue(full, v, fallback);
    }
    /** Stores pref_lmc_hybrid_&lt;key&gt;: a Boolean as a switch, anything else as text (quick-settings chips). */
    public static void setHybridValue(String key, Object value) {
        if (value instanceof Boolean) preferenceKeys.settingsManager.set("default_scope", HYBRID_PREFIX + key, (boolean) (Boolean) value);
        else preferenceKeys.settingsManager.set("default_scope", HYBRID_PREFIX + key, String.valueOf(value));
    }
    private static float hybridDefault(String key, float fallback) {
        Float v = HYBRID_DEFAULTS.get(key);
        return v != null ? v : fallback;
    }
    private static float hybridStored(String key, float fallback) {
        final float def = hybridDefault(key, fallback);
        final String full = HYBRID_PREFIX + key;
        try {
            String v = preferenceKeys.settingsManager.getString("default_scope", full, "");
            return v == null || v.trim().isEmpty() ? def : (float) SettingsNumericRules.value(full, v.trim(), def);
        } catch (RuntimeException error) { return def; }
    }
    private static boolean hybridStoredSwitch(String key, boolean fallback) {
        final boolean def = hybridDefault(key, fallback ? 1f : 0f) > 0f;
        try { return preferenceKeys.settingsManager.getBoolean("default_scope", HYBRID_PREFIX + key, def); }
        catch (RuntimeException error) { return def; }
    }
    /** nice_dev.txt override of a shared knob on a hybrid shot: "hybrid_&lt;key&gt;", else the plain "&lt;key&gt;" line. */
    private static Float hybridDevValue(String key) {
        Float override = niceDevValue("hybrid_" + key);
        return override != null ? override : niceDevValue(key);
    }
    /**
     * The key a SCAM HDR preference is read from for the current shot: on a hybrid shot its hybrid copy (see
     * {@link #hybridCopyKey}), otherwise the key itself. For nodes that read pref_nice_* / pref_agx_nice_* directly.
     */
    public static String profileKey(String key) {
        return key != null && isHybridShot() ? hybridCopyKey(key) : key;
    }
    /**
     * The hybrid's copy of a SCAM HDR key: pref_vivo_nice_hybrid_&lt;k&gt;, pref_vivo_nice_&lt;k&gt;, pref_nice_&lt;k&gt; -&gt;
     * pref_lmc_hybrid_&lt;k&gt;; pref_agx_nice_&lt;k&gt; -&gt; pref_lmc_hybrid_agx_&lt;k&gt;; pref_vivo_hdr_&lt;k&gt; -&gt;
     * pref_lmc_hybrid_hdr_&lt;k&gt;; any other key is returned unchanged. The settings migration uses the same mapping.
     */
    public static String hybridCopyKey(String key) {
        return LmcHybridKeys.copyKey(key);
    }
    /** A number of a SCAM HDR key read directly (RawTherapeeSettings style), from the hybrid's copy on a hybrid shot. */
    public static float profileNumber(String key, float fallback, float lo, float hi) {
        return RawTherapeeSettings.number(profileKey(key), fallback, lo, hi);
    }

    /**
     * Boolean SCAM HDR internal switch pref_vivo_nice_&lt;key&gt; (nice_dev.txt "key 0/1" overrides it). On a hybrid shot
     * the hybrid's copy pref_lmc_hybrid_&lt;key&gt;; keys starting with "hybrid_" always name a hybrid setting.
     */
    public static boolean niceInternalSwitch(String key, boolean fallback) {
        if (key.startsWith("hybrid_")) return hybridSwitch(key.substring("hybrid_".length()), fallback);
        if (isHybridShot()) {
            Float override = hybridDevValue(key);
            return override != null ? override > 0f : hybridStoredSwitch(key, fallback);
        }
        return scamInternalSwitch(key, fallback);
    }
    /**
     * SCAM HDR internal value pref_vivo_nice_&lt;key&gt;, clamped by SettingsNumericRules (nice_dev.txt "key v" overrides it).
     * On a hybrid shot the same call reads the hybrid's copy pref_lmc_hybrid_&lt;key&gt; (nice_dev.txt "hybrid_&lt;key&gt;",
     * then "key") and never the SCAM HDR key; keys starting with "hybrid_" always name a hybrid setting.
     */
    public static float niceInternalValue(String key, float fallback) {
        if (key.startsWith("hybrid_")) return hybridValue(key.substring("hybrid_".length()), fallback);
        if (isHybridShot()) {
            Float override = hybridDevValue(key);
            return override != null ? override : hybridStored(key, fallback);
        }
        return scamInternalValue(key, fallback);
    }
    private static boolean scamInternalSwitch(String key, boolean fallback) {
        Float override = niceDevValue(key);
        if (override != null) return override > 0f;
        try { return preferenceKeys.settingsManager.getBoolean("default_scope", "pref_vivo_nice_" + key, fallback); }
        catch (RuntimeException error) { return fallback; }
    }
    private static float scamInternalValue(String key, float fallback) {
        String fullKey="pref_vivo_nice_"+key;
        Float override = niceDevValue(key);
        if (override != null) return override;
        try {
            return (float)SettingsNumericRules.value(fullKey,
                    preferenceKeys.settingsManager.getString("default_scope",fullKey,String.valueOf(fallback)),fallback);
        } catch(RuntimeException error) { return fallback; }
    }
    /** SCAM HDR tone value pref_vivo_hdr_&lt;key&gt;; on a hybrid shot the hybrid's pref_lmc_hybrid_hdr_&lt;key&gt; (nice_dev "hybrid_hdr_&lt;key&gt;"). */
    public static float vivoHdrValue(String key, float fallback) {
        if (isHybridShot()) {
            Float override = niceDevValue("hybrid_hdr_" + key);
            return override != null ? override : hybridStored("hdr_" + key, fallback);
        }
        String fullKey = "pref_vivo_hdr_" + key;
        try {
            return (float) SettingsNumericRules.value(fullKey,
                    preferenceKeys.settingsManager.getString("default_scope", fullKey, String.valueOf(fallback)), fallback);
        } catch (RuntimeException error) { return fallback; }
    }

    /** RAW stream format: auto | raw16 (RAW_SENSOR) | raw10 | raw12 — the camera must offer it, else auto order. */
    public static String getRawStreamFormat() {
        return preferenceKeys.settingsManager.getString("default_scope", "pref_raw_stream_format", "auto");
    }
    public static boolean isGcamStageEnabled(String key) {
        return preferenceKeys.settingsManager.getBoolean("default_scope",key,false);
    }
    public static float gcamValue(String key,float fallback,float min,float max) {
        try {
            float value=Float.parseFloat(preferenceKeys.settingsManager.getString("default_scope",key,String.valueOf(fallback)));
            return Float.isFinite(value)?Math.max(min,Math.min(max,value)):fallback;
        } catch(RuntimeException error) { return fallback; }
    }

    public static boolean isSabreEnabled() {
        return isRawMfsrEnabled() && "sabre".equals(multiFrameText("pref_mfsr_engine_key", "native"));
    }

    private static String multiFrameText(String key, String fallback) {
        return preferenceKeys.settingsManager.getString("default_scope", key, fallback);
    }
    public static int getMultiFrameBlock() {
        return com.particlesdevs.photoncamera.remosaic.BurstPolicy.block(multiFrameText("pref_mfsr_source_key","1"));
    }
    public static String getMultiFrameCfa() { return multiFrameText("pref_mfsr_cfa_key","auto"); }
    public static int getMultiFrameCount() {
        return (int)SettingsNumericRules.value("pref_mfsr_frames_key",multiFrameText("pref_mfsr_frames_key","15"),15);
    }
    public static boolean isMultiFrameFpnEnabled() {
        return preferenceKeys.settingsManager.getBoolean("default_scope","pref_mfsr_fpn_key",true);
    }
    public static boolean isMultiFrameCalibration() {
        return isRawMfsrEnabled() && !isSabreEnabled() && preferenceKeys.settingsManager.getBoolean("default_scope","pref_mfsr_calibrate_key",false);
    }
    public static void finishMultiFrameCalibration() {
        preferenceKeys.settingsManager.set("default_scope","pref_mfsr_calibrate_key",false);
    }
    public static float getMultiFrameRedCa() {
        return (float)SettingsNumericRules.value("pref_mfsr_red_ca_key",multiFrameText("pref_mfsr_red_ca_key","1"),1);
    }
    public static float getMultiFrameBlueCa() {
        return (float)SettingsNumericRules.value("pref_mfsr_blue_ca_key",multiFrameText("pref_mfsr_blue_ca_key","1"),1);
    }

    public static boolean isSensorSharpeningEnabled() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", "pref_sensor_sharpening_enabled", true);
    }

    private static float mfsrFloat(Key key, float fallback) {
        try {
            String v = preferenceKeys.settingsManager.getString(
                    "default_scope", key, String.valueOf(fallback));
            return (float) SettingsNumericRules.value(key.mValue, v, fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Highlight handling. Recovery merges from the unclipped channels of a partly
     * saturated quad instead of rejecting it; protection desaturates towards the
     * quad mean near the clip so the differing per-channel saturation points do
     * not tint blown areas.
     */
    /**
     * Replay the last shot's tone curve on the viewfinder. Shows the tonemapping
     * and shadow/highlight placement of the result; cannot show detail that needs
     * a burst.
     */
    /**
     * How much the HDR+ luma and chroma denoise strengths follow the gain, per
     * stop above base ISO. 0 keeps the old fixed behaviour; 0.25 means a strength
     * set in daylight is a quarter stronger at each doubling of ISO.
     *
     * Chroma defaults higher than luma: colour speckle is objectionable well
     * before luma grain is, and smoothing chroma costs almost no detail.
     */
    public static float getHdrPlusLumaGainSlope() {
        return Float.parseFloat(getAcesString("pref_hdrplus_luma_gain_slope_key", "0.15"));
    }

    public static float getHdrPlusChromaGainSlope() {
        return Float.parseFloat(getAcesString("pref_hdrplus_chroma_gain_slope_key", "0.30"));
    }

    /**
     * Signal-to-noise ratio the merged frame is aimed at. Denoise strength is
     * scaled by how far this shot falls short of it, so a clean frame is denoised
     * less than a noisy one without touching the sliders. GCam's dumps show a
     * merged estimate around 110 on a well-lit shot.
     */
    /**
     * Denoise multipliers per SNR band. GCam's tuning is laid out the same way -
     * a separate set of levels for Very Low, Low, Med, High and Very High - rather
     * than one number scaled continuously, because what a frame needs at SNR 5 is
     * not a scaled version of what it needs at SNR 100.
     *
     * <p>Each band carries three multipliers: low frequency, high frequency and
     * chroma. Low and high are separate on purpose: the merge shader mixes the two
     * by their ratio, so scaling both by the same amount cancels out and changes
     * nothing - which is why the earlier single-scale attempt had no visible
     * effect.
     */
    public static int snrBandOf(float snr) {
        if (snr < 8f) return 0;      // Very Low
        if (snr < 20f) return 1;     // Low
        if (snr < 45f) return 2;     // Med
        if (snr < 90f) return 3;     // High
        return 4;                    // Very High
    }

    public static String snrBandName(int band) {
        switch (band) {
            case 0: return "VeryLow";
            case 1: return "Low";
            case 2: return "Med";
            case 3: return "High";
            default: return "VeryHigh";
        }
    }

    private static final String[] SNR_BANDS = {"verylow", "low", "med", "high", "veryhigh"};
    // Defaults fall from heavy at low SNR to light at high SNR. Chroma stays
    // higher than luma throughout: colour noise survives frame averaging better
    // and is the more objectionable of the two.
    private static final float[] DEF_LOW    = {1.60f, 1.30f, 1.00f, 0.80f, 0.60f};
    private static final float[] DEF_HIGH   = {0.70f, 0.85f, 1.00f, 1.10f, 1.20f};
    private static final float[] DEF_CHROMA = {2.00f, 1.60f, 1.20f, 0.90f, 0.70f};

    public static float getSnrBandLow(int band) {
        return Float.parseFloat(getAcesString(
                "pref_snr_" + SNR_BANDS[band] + "_low_key", String.valueOf(DEF_LOW[band])));
    }

    public static float getSnrBandHigh(int band) {
        return Float.parseFloat(getAcesString(
                "pref_snr_" + SNR_BANDS[band] + "_high_key", String.valueOf(DEF_HIGH[band])));
    }

    public static float getSnrBandChroma(int band) {
        return Float.parseFloat(getAcesString(
                "pref_snr_" + SNR_BANDS[band] + "_chroma_key", String.valueOf(DEF_CHROMA[band])));
    }

    public static float getHdrPlusSnrTarget() {
        return Float.parseFloat(getAcesString("pref_hdrplus_snr_target_key", "110"));
    }

    /**
     * How hard the SNR scale bites on luma and on chroma. 0 pins that channel to
     * the slider and ignores SNR; 1 follows it fully. Chroma defaults higher
     * because colour noise survives frame averaging better than luma noise and is
     * the more objectionable of the two.
     */
    public static float getHdrPlusSnrLumaExp() {
        return Float.parseFloat(getAcesString("pref_hdrplus_snr_luma_exp_key", "0.6"));
    }

    public static float getHdrPlusSnrChromaExp() {
        return Float.parseFloat(getAcesString("pref_hdrplus_snr_chroma_exp_key", "1.0"));
    }

    /** Develop the preview RAW stream as the viewfinder instead of showing the ISP image. */
    public static boolean isLiveViewfinderRawEnabled() {
        return preferenceKeys.settingsManager.getBoolean(
                "default_scope", Key.KEY_LIVE_VIEWFINDER_RAW, false);
    }

    /** Retired ISP tone approximation. Old backups must not enable a second preview path. */
    @Deprecated
    public static boolean isLiveViewfinderLookEnabled() {
        return false;
    }

    public static boolean isHighlightRecoveryEnabled() {
        return preferenceKeys.settingsManager.getBoolean(
                "default_scope", Key.KEY_HIGHLIGHT_RECOVERY, false);
    }

    public static int getHighlightRecoveryMinOk() {
        return Math.max(1, Math.min(4, (int) mfsrFloat(Key.KEY_HIGHLIGHT_RECOVERY_MIN_OK, 2f)));
    }

    public static boolean isHighlightProtectionEnabled() {
        return preferenceKeys.settingsManager.getBoolean(
                "default_scope", Key.KEY_HIGHLIGHT_PROTECTION, false);
    }

    public static float getHighlightProtectionKnee() {
        return Math.max(0f, Math.min(0.99f, mfsrFloat(Key.KEY_HIGHLIGHT_PROTECTION_KNEE, 0.8f)));
    }

    public static float getHighlightProtectionStrength() {
        return Math.max(0f, Math.min(1f, mfsrFloat(Key.KEY_HIGHLIGHT_PROTECTION_STRENGTH, 1.0f)));
    }

    public static boolean isRaisrEnabled() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", Key.KEY_RAISR_ENABLED, false);
    }

    public static String getVivoUpscaleBackend() {
        return preferenceKeys.settingsManager.getString("default_scope", Key.KEY_VIVO_UPSCALE_BACKEND, "raisr");
    }

    public static int getVivoDownscaleKernel() {
        int value = preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_VIVO_DOWNSCALE_KERNEL, 0);
        return value >= 2 && value <= 5 ? value : 0;
    }

    public static String getVivoDownscaleSize() {
        return preferenceKeys.settingsManager.getString("default_scope", Key.KEY_VIVO_DOWNSCALE_SIZE, "original");
    }

    public static int getRaisrFilterScale() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_RAISR_FILTER_SCALE, 2).intValue();
    }

    public static int getRaisrOutputScale() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_RAISR_OUTPUT_SCALE, 10).intValue();
    }

    public static int getRaisrStrength() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_RAISR_STRENGTH, 50).intValue();
    }

    public static int getRaisrHaloProtection() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_RAISR_HALO, 70).intValue();
    }

    public static int getRaisrAliasingSuppression() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_RAISR_ALIASING, 60).intValue();
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

    /**
     * Global pre-shutter RAW capacity, independent of mode, lens and merge count.
     */
    public static int getZslBufferCountValue() {
        return Math.max(1, Math.min(100,
                preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_ZSL_BUFFER_COUNT, 50).intValue()));
    }

    public static String getZslMergeAlgorithm() {
        return preferenceKeys.settingsManager.getString("default_scope", Key.KEY_ZSL_MERGE_ALGORITHM, "legacy");
    }

    public static String getNightMergeAlgorithm() {
        return preferenceKeys.settingsManager.getString("default_scope", Key.KEY_NIGHT_MERGE_ALGORITHM, "legacy");
    }

    public static boolean isHdrPlusMergeEnabled() {
        if (isRawMfsrEnabled() || isVivoHdrEnabled()) return false; // Native base + exposure-aware bracket merge, no second HDR+ denoise.
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
        return 1; // ESD4D produces Bayer RAW; the removed legacy RGB-layout mode is unsupported.
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
        KEY_SHORT_FRAME_COUNT(R.string.pref_short_frame_count_key),
        KEY_LONG_FRAME_COUNT(R.string.pref_long_frame_count_key),
        KEY_SHORT_EXPOSURE_EV(R.string.pref_short_exposure_ev_key),
        KEY_LONG_EXPOSURE_EV(R.string.pref_long_exposure_ev_key),
        KEY_HIGHLIGHT_SUPPRESSION(R.string.pref_highlight_suppression_key),
        KEY_PROCESSING_BACKEND(R.string.pref_processing_backend_key),
        KEY_CONTRAST_SEEKBAR(R.string.pref_contrast_seekbar_key),
        KEY_SHARPNESS_SEEKBAR(R.string.pref_sharpness_seekbar_key),
        KEY_REMOSAIC_ENABLED(R.string.pref_remosaic_enabled_key),
        KEY_REMOSAIC_BLOCK(R.string.pref_remosaic_block_key),
        KEY_REMOSAIC_PROFILE(R.string.pref_remosaic_profile_key),
        KEY_REMOSAIC_BACKEND(R.string.pref_remosaic_backend_key),
        KEY_REMOSAIC_STEERED(R.string.pref_remosaic_steered_key),
        KEY_REMOSAIC_CLAMP(R.string.pref_remosaic_clamp_key),
        KEY_REMOSAIC_FLATFIELD(R.string.pref_remosaic_flatfield_key),
        KEY_REMOSAIC_DUMP(R.string.pref_remosaic_dump_key),
        KEY_REMOSAIC_PHASE_X(R.string.pref_remosaic_phase_x_key),
        KEY_REMOSAIC_PHASE_Y(R.string.pref_remosaic_phase_y_key),
        KEY_SHARP_USM_ENABLED(R.string.pref_sharp_usm_enabled_key),
        KEY_SHARP_DECONV_ENABLED(R.string.pref_sharp_deconv_enabled_key),
        KEY_SHARP_MICRO_ENABLED(R.string.pref_sharp_micro_enabled_key),
        KEY_SHARP_RADIUS(R.string.pref_sharp_radius_key),
        KEY_SHARP_AMOUNT(R.string.pref_sharp_amount_key),
        KEY_SHARP_CONTRAST(R.string.pref_sharp_contrast_key),
        KEY_SHARP_THRESHOLD_BOTTOM_LEFT(R.string.pref_sharp_threshold_bl_key),
        KEY_SHARP_THRESHOLD_TOP_LEFT(R.string.pref_sharp_threshold_tl_key),
        KEY_SHARP_THRESHOLD_TOP_RIGHT(R.string.pref_sharp_threshold_tr_key),
        KEY_SHARP_THRESHOLD_BOTTOM_RIGHT(R.string.pref_sharp_threshold_br_key),
        KEY_SHARP_EDGES_ONLY(R.string.pref_sharp_edges_only_key),
        KEY_SHARP_EDGES_RADIUS(R.string.pref_sharp_edges_radius_key),
        KEY_SHARP_EDGES_TOLERANCE(R.string.pref_sharp_edges_tolerance_key),
        KEY_SHARP_HALO_CONTROL(R.string.pref_sharp_halo_control_key),
        KEY_SHARP_HALO_AMOUNT(R.string.pref_sharp_halo_amount_key),
        KEY_SHARP_DECONV_HALO(R.string.pref_sharp_deconv_halo_key),
        KEY_SHARP_DECONV_HALO_MARGIN(R.string.pref_sharp_deconv_halo_margin_key),
        KEY_SHARP_DECONV_HALO_MACRO(R.string.pref_sharp_deconv_halo_macro_key),
        KEY_SHARP_DECONV_KERNEL_1(R.string.pref_sharp_deconv_kernel_1_key),
        KEY_SHARP_DECONV_RADIUS_1(R.string.pref_sharp_deconv_radius_1_key),
        KEY_SHARP_DECONV_AMOUNT_1(R.string.pref_sharp_deconv_amount_1_key),
        KEY_SHARP_DECONV_ITERATIONS_1(R.string.pref_sharp_deconv_iterations_1_key),
        KEY_SHARP_DECONV_DAMPING_1(R.string.pref_sharp_deconv_damping_1_key),
        KEY_SHARP_DECONV_KERNEL_2(R.string.pref_sharp_deconv_kernel_2_key),
        KEY_SHARP_DECONV_RADIUS_2(R.string.pref_sharp_deconv_radius_2_key),
        KEY_SHARP_DECONV_AMOUNT_2(R.string.pref_sharp_deconv_amount_2_key),
        KEY_SHARP_DECONV_ITERATIONS_2(R.string.pref_sharp_deconv_iterations_2_key),
        KEY_SHARP_DECONV_DAMPING_2(R.string.pref_sharp_deconv_damping_2_key),
        KEY_SHARP_DECONV_KERNEL_3(R.string.pref_sharp_deconv_kernel_3_key),
        KEY_SHARP_DECONV_RADIUS_3(R.string.pref_sharp_deconv_radius_3_key),
        KEY_SHARP_DECONV_AMOUNT_3(R.string.pref_sharp_deconv_amount_3_key),
        KEY_SHARP_DECONV_ITERATIONS_3(R.string.pref_sharp_deconv_iterations_3_key),
        KEY_SHARP_DECONV_DAMPING_3(R.string.pref_sharp_deconv_damping_3_key),
        KEY_SHARP_MICRO_AMOUNT(R.string.pref_sharp_micro_amount_key),
        KEY_SHARP_MICRO_UNIFORMITY(R.string.pref_sharp_micro_uniformity_key),
        KEY_SHARP_MICRO_CONTRAST(R.string.pref_sharp_micro_contrast_key),
        KEY_SHARP_MICRO_MATRIX_3X3(R.string.pref_sharp_micro_matrix_key),
        KEY_FALSE_COLOR_ENABLED(R.string.pref_false_color_enabled_key),
        KEY_FALSE_COLOR_STRENGTH(R.string.pref_false_color_strength_key),
        KEY_DEFRINGE_PURPLE(R.string.pref_defringe_purple_key),
        KEY_DEFRINGE_GREEN(R.string.pref_defringe_green_key),
        KEY_CA_RED(R.string.pref_ca_red_key),
        KEY_CA_BLUE(R.string.pref_ca_blue_key),
        KEY_NR_LUMA_ENABLED(R.string.pref_nr_luma_enabled_key),
        KEY_NR_CHROMA_ENABLED(R.string.pref_nr_chroma_enabled_key),
        KEY_AI_DENOISE_ENABLED(R.string.pref_ai_denoise_enabled_key),
        KEY_AI_DENOISE_STRENGTH(R.string.pref_ai_denoise_strength_key),
        KEY_AI_DENOISE_LUMA(R.string.pref_ai_denoise_luma_key),
        KEY_AI_DENOISE_CHROMA(R.string.pref_ai_denoise_chroma_key),
        KEY_AI_DENOISE_MODEL(R.string.pref_ai_denoise_model_key),
        KEY_RAW_MFSR_ENABLED(R.string.pref_raw_mfsr_enabled_key),
        KEY_LIVE_VIEWFINDER_LOOK(R.string.pref_live_viewfinder_look_key),
        KEY_LIVE_VIEWFINDER_RAW(R.string.pref_live_viewfinder_raw_key),
        KEY_HIGHLIGHT_RECOVERY(R.string.pref_highlight_recovery_key),
        KEY_HIGHLIGHT_RECOVERY_MIN_OK(R.string.pref_highlight_recovery_min_ok_key),
        KEY_HIGHLIGHT_PROTECTION(R.string.pref_highlight_protection_key),
        KEY_HIGHLIGHT_PROTECTION_KNEE(R.string.pref_highlight_protection_knee_key),
        KEY_HIGHLIGHT_PROTECTION_STRENGTH(R.string.pref_highlight_protection_strength_key),
        KEY_VIVO_DOWNSCALE_KERNEL(R.string.pref_vivo_downscale_kernel_key),
        KEY_VIVO_DOWNSCALE_SIZE(R.string.pref_vivo_downscale_size_key),
        KEY_VIVO_UPSCALE_BACKEND(R.string.pref_vivo_upscale_backend_key),
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
        KEY_WIDE169(R.string.pref_wide169_key),
        KEY_ZSL_BUFFER_COUNT(R.string.pref_zsl_buffer_count_key),
        KEY_ZSL_MERGE_ALGORITHM(R.string.pref_zsl_merge_algorithm_key),
        KEY_NIGHT_MERGE_ALGORITHM(R.string.pref_night_merge_algorithm_key),
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

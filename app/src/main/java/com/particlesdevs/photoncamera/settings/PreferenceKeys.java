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
        return Math.max(isVivoHdrEnabled() ? 3 : 1, preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_FRAME_COUNT).intValue());
    }

    public static int getShortFrameCountValue() {
        return Math.max(isVivoHdrEnabled() ? 1 : 0, preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_SHORT_FRAME_COUNT).intValue());
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
        return isSabreEnabled() ? getMultiFrameBlock() > 1
                : !isRawMfsrEnabled() && getBool(Key.KEY_REMOSAIC_ENABLED);
    }

    /** Samples per colour block: 2 quad bayer, 4 tetra squared. */
    public static int getRemosaicBlockSize() {
        return isSabreEnabled() ? getMultiFrameBlock() : sharpInt(Key.KEY_REMOSAIC_BLOCK) == 2 ? 2 : 4;
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
        if(isSabreEnabled()) return "scamera";
        return preferenceKeys.settingsManager.getString("default_scope", Key.KEY_REMOSAIC_BACKEND, "scamera");
    }

    /** Separate six-frame experimental HP9 path, before ordinary RAW fusion. */
    public static boolean isHexQuadCaptureEnabled() {
        return isRemosaicEnabled() && "hp9_hexquad".equals(getRemosaicBackend());
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
        return sharpFloat(Key.KEY_SHARP_AMOUNT);
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
        return sharpFloat(Key.KEY_SHARP_MICRO_AMOUNT);
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

    /** Autonomous HDR is exclusive with the native remosaic engines. Stored choices are retained. */
    public static boolean isVivoHdrEnabled() {
        return isGcamStageEnabled("pref_vivo_hdr_enabled") && !isRawMfsrEnabled()
                && (!isRemosaicEnabled() || "scamera".equals(getRemosaicBackend()));
    }
    public static float vivoHdrValue(String key, float fallback) {
        return gcamValue("pref_vivo_hdr_" + key, fallback, 0f, 2f);
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

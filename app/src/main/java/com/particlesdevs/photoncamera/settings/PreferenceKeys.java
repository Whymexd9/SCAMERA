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


/* loaded from: classes8.dex */
public class PreferenceKeys {
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
























    private static String getAcesString(String str, String str2) {
        return SettingsNumericRules.normalized(str, preferenceKeys.settingsManager.getString("default_scope", str, str2), str2);
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
            // Only the per-module settings (ModuleProfiles.isLocal: not the shared ones of ModuleProfiles.GLOBAL_KEYS).
            map.keySet().removeIf(key -> !ModuleProfiles.isLocal(key) || lambda$addIds$1(key));
            String json = GSON.toJson(map);
            for (String cameraId : ids) {
                settingsManager.setInitial(Key.PER_LENS_FILE_NAME.mValue, PER_LENS_KEY_PREFIX + cameraId, json);
            }
        }
    }

    static /* synthetic */ boolean lambda$addIds$1(String key) {
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

    public static boolean isRemosaicOn() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", Key.KEY_REMOSAIC);
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
    /**
     * P80 «Быстрый JPEG»: Android's encoder (libjpeg-turbo, 4:2:0) instead of jpegli (4:4:4) for the saved JPEGs: ~2x faster to
     * encode, colour detail at half resolution and a slightly larger file. Off by default (jpegli).
     */
    public static boolean getJpegFastEncoder() {
        try { return preferenceKeys.settingsManager.getBoolean("default_scope", "pref_jpeg_fast_encoder", false); }
        catch (RuntimeException e) { return false; }
    }
    /** P24: quality of every saved JPEG (plain, Ultra HDR base image and gain map), 70-100, default 98 as before. */
    public static int getJpegQuality() {
        return (int) Math.round(SettingsNumericRules.value("pref_jpeg_quality",
                preferenceKeys.settingsManager.getString("default_scope", "pref_jpeg_quality", "98"), 98));
    }
    /**
     * «Формат фото»: the codec of the processed photo; HEIC below Android 9 and AVIF below Android 12 or without its encoder
     * library are written as JPEG (PhotoFormat.effective).
     */
    public static com.particlesdevs.photoncamera.processing.PhotoFormat getPhotoFormat() {
        return com.particlesdevs.photoncamera.processing.PhotoFormat.effective(getChosenPhotoFormat(), android.os.Build.VERSION.SDK_INT,
                com.particlesdevs.photoncamera.processing.avif.AvifEncoder.available());
    }
    /** The stored «Формат фото» value as chosen (no API fallback). */
    public static com.particlesdevs.photoncamera.processing.PhotoFormat getChosenPhotoFormat() {
        return com.particlesdevs.photoncamera.processing.PhotoFormat.parse(preferenceKeys.settingsManager.getString("default_scope",
                com.particlesdevs.photoncamera.processing.PhotoFormat.KEY, "jpeg"));
    }
    /** Stores «Формат фото» (the codec) as the settings screen stores it (the list value). */
    public static void setChosenPhotoFormat(com.particlesdevs.photoncamera.processing.PhotoFormat format) {
        preferenceKeys.settingsManager.set("default_scope", com.particlesdevs.photoncamera.processing.PhotoFormat.KEY, format.value);
    }
    /** HEIC quality 1-100 (default 90). */
    public static int getHeicQuality() {
        return (int) Math.round(SettingsNumericRules.value(com.particlesdevs.photoncamera.processing.PhotoFormat.KEY_HEIC_QUALITY,
                preferenceKeys.settingsManager.getString("default_scope", com.particlesdevs.photoncamera.processing.PhotoFormat.KEY_HEIC_QUALITY, "90"), 90));
    }
    /** «HEIC 10 бит» as stored (default off); whether a shot uses it: processing.heif.Heic10Support.wanted. */
    public static boolean isHeic10Bit() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", com.particlesdevs.photoncamera.processing.PhotoFormat.KEY_HEIC_10BIT, false);
    }
    /** WebP quality 1-100 (default 90); ignored by the lossless WebP. */
    public static int getWebpQuality() {
        return (int) Math.round(SettingsNumericRules.value(com.particlesdevs.photoncamera.processing.PhotoFormat.KEY_WEBP_QUALITY,
                preferenceKeys.settingsManager.getString("default_scope", com.particlesdevs.photoncamera.processing.PhotoFormat.KEY_WEBP_QUALITY, "90"), 90));
    }
    public static boolean isWebpLossless() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", com.particlesdevs.photoncamera.processing.PhotoFormat.KEY_WEBP_LOSSLESS, false);
    }
    /** AVIF quality 1-100 (default 90); not used by the lossless AVIF. */
    public static int getAvifQuality() {
        return (int) Math.round(SettingsNumericRules.value(com.particlesdevs.photoncamera.processing.PhotoFormat.KEY_AVIF_QUALITY,
                preferenceKeys.settingsManager.getString("default_scope", com.particlesdevs.photoncamera.processing.PhotoFormat.KEY_AVIF_QUALITY, "90"), 90));
    }
    /** AVIF «Без потерь»: exact pixels (identity matrix, 4:4:4, the photo's own bit depth). */
    public static boolean isAvifLossless() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", com.particlesdevs.photoncamera.processing.PhotoFormat.KEY_AVIF_LOSSLESS, false);
    }
    /** AVIF «Глубина цвета»: 8, 10 (default) or 12 bit. */
    public static int getAvifDepth() {
        int depth = (int) Math.round(PreferenceNumber.read(preferenceKeys.settingsManager.getString("default_scope",
                com.particlesdevs.photoncamera.processing.PhotoFormat.KEY_AVIF_DEPTH, "10"), 10));
        return depth == 8 || depth == 12 ? depth : com.particlesdevs.photoncamera.processing.PhotoFormat.AVIF_DEFAULT_DEPTH;
    }
    /** AVIF «Цветовая субдискретизация»: 4:4:4 (default, "444") or 4:2:0 ("420"). */
    public static boolean isAvifYuv444() {
        return !"420".equals(preferenceKeys.settingsManager.getString("default_scope",
                com.particlesdevs.photoncamera.processing.PhotoFormat.KEY_AVIF_CHROMA, "444").trim());
    }
    /** AVIF «Скорость кодирования»: libavif speed 0-10 (default 6). */
    public static int getAvifSpeed() {
        return (int) Math.round(SettingsNumericRules.value(com.particlesdevs.photoncamera.processing.PhotoFormat.KEY_AVIF_SPEED,
                preferenceKeys.settingsManager.getString("default_scope", com.particlesdevs.photoncamera.processing.PhotoFormat.KEY_AVIF_SPEED, "6"),
                com.particlesdevs.photoncamera.processing.PhotoFormat.AVIF_DEFAULT_SPEED));
    }
    /** The AVIF settings of a shot; the encoder uses every core. */
    public static com.particlesdevs.photoncamera.processing.avif.AvifEncoder.Options getAvifOptions() {
        return new com.particlesdevs.photoncamera.processing.avif.AvifEncoder.Options(getAvifQuality(), isAvifLossless(), getAvifDepth(),
                isAvifYuv444(), getAvifSpeed(), Runtime.getRuntime().availableProcessors());
    }
    /** P46 «Цветовое пространство» as stored: sRGB (default) or Display P3; the pipeline renders and every file is tagged in it. */
    public static com.particlesdevs.photoncamera.processing.color.OutputColour.Space getOutputColourSpace() {
        return com.particlesdevs.photoncamera.processing.color.OutputColour.Space.parse(preferenceKeys.settingsManager.getString("default_scope",
                com.particlesdevs.photoncamera.processing.PhotoFormat.KEY_COLOR_SPACE, "srgb"));
    }
    /** P46 «HDR в HEIC / AVIF» as stored (default off); whether a shot uses it: processing.color.HdrOutput.wanted. */
    public static boolean isHdrOutputOn() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", com.particlesdevs.photoncamera.processing.PhotoFormat.KEY_HDR, false);
    }
    /** «Также сохранять JPEG»: a JPEG next to the HEIC / WebP / AVIF photo (it carries Ultra HDR when that is on). */
    public static boolean isAlsoSaveJpeg() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", com.particlesdevs.photoncamera.processing.PhotoFormat.KEY_ALSO_JPEG, false);
    }
    /** Ultra HDR is computed only when it ends up in a file: the setting is on and the shot writes a JPEG. */
    public static boolean isUltraHdrActive() {
        return com.particlesdevs.photoncamera.processing.PhotoFormat.ultraHdrApplies(isUltraHdrOn(), getPhotoFormat(), isAlsoSaveJpeg());
    }
    public static float getWatermarkOpacity() {
        return (float) SettingsNumericRules.value("pref_watermark_opacity",
                preferenceKeys.settingsManager.getString("default_scope", "pref_watermark_opacity", "100"), 100) / 100f;
    }

    public static boolean isPerLensSettingsOn() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", Key.KEY_SAVE_PER_LENS_SETTINGS);
    }

    public static int isSaveRaw() {
        return preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_SAVE_RAW).intValue();
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

    /** «Звук таймера» (P16): the countdown sound of the self-timer, independent of the shutter sound. */
    public static boolean isTimerSoundOn() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", Key.KEY_TIMER_SOUND, true);
    }









    private static float sharpFloat(Key key) {
        return preferenceKeys.settingsManager.getFloat("default_scope", key).floatValue();
    }

    private static int sharpInt(Key key) {
        return preferenceKeys.settingsManager.getInteger("default_scope", key).intValue();
    }


    /** Samples per colour block: 2 quad bayer, 4 tetra squared. */
    public static int getRemosaicBlockSize() {
        if (isScamMosaic()) return scamMosaicBlock();
        return sharpInt(Key.KEY_REMOSAIC_BLOCK) == 2 ? 2 : 4;
    }

    /**
     * SCAM HDR on a Quad / Tetra stream (the ISZ modules): off (plain Bayer only, as before), scamera (GPU remosaic of
     * every frame), detail (Tetra Detail v2 on every frame), mfr (Multi-frame Remosaic of the N frames), neural
     * (the NPU quad / HexQuad models on the N frames); the short and long frames always take the GPU remosaic.
     */
    public static String scamMosaicMode() {
        String mode = preferenceKeys.settingsManager.getString("default_scope", "pref_scamhdr_mosaic", "off");
        return mode == null ? "off" : mode;
    }

    /** The Sabre kernel over the frames' own mosaic samples (sabre / neural_sabre): the donors of the merge are the raw Quad / Tetra sites. */
    public static boolean isScamMosaicSabre() {
        String mode = scamMosaicMode();
        return "sabre".equals(mode) || "neural_sabre".equals(mode);
    }

    /** How the plain-bayer burst for the network is built: scamera / detail / mfr / neural (sabre = scamera, neural_sabre = neural). */
    public static String scamMosaicBase() {
        String mode = scamMosaicMode();
        return "sabre".equals(mode) ? "scamera" : "neural_sabre".equals(mode) ? "neural" : mode;
    }

    /** Raw preferences only: this is consulted by isRemosaicEnabled() and isScamHdrEnabled(). */
    public static boolean isScamMosaic() {
        return isScamHdrRoute() && !"off".equals(scamMosaicMode());
    }

    /** Colour block of the module's mosaic: from its forced sensor mode (7 = Tetra 4x4, 5 = Quad 2x2), else the remosaic block. */
    public static int scamMosaicBlock() {
        int forced = Math.round(scamInternalValue("mosaic_block", 0f)); // SCAM HDR only: the hybrid merges plain Bayer
        if (forced == 2 || forced == 4) return forced;
        int mode = ModuleRegistry.sensorMode(ModuleRegistry.active());
        if (mode == 7) return 4;
        if (mode == 5) return 2;
        return sharpInt(Key.KEY_REMOSAIC_BLOCK) == 2 ? 2 : 4;
    }

    /** A forced colour block or an ISZ sensor mode (5 Quad, 7 Tetra): the module streams a mosaic by its own settings. */
    public static boolean scamMosaicDeclared() {
        int forced = Math.round(scamInternalValue("mosaic_block", 0f));
        int mode = ModuleRegistry.sensorMode(ModuleRegistry.active());
        return forced == 2 || forced == 4 || mode == 5 || mode == 7;
    }

    /** Colour-block side of the RAW stream for statistics and the raw viewfinder: 1 for plain bayer. */
    public static int mosaicBlock() {
        return isScamMosaic() ? getRemosaicBlockSize() : 1;
    }

    /** Interpolate green along edges instead of across them. */
    public static boolean isRemosaicSteered() {
        return getBool(Key.KEY_REMOSAIC_STEERED);
    }

    /** Bound interpolated colour differences by the measured ones nearby. */
    public static boolean isRemosaicClampDiffs() {
        return getBool(Key.KEY_REMOSAIC_CLAMP);
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
        return isScamMosaic() && "detail".equals(scamMosaicMode()) && scamMosaicBlock() == 4 ? "tetra_detail" : "scamera";
    }






    /** Hybrid reconstruction weights; not exposed parameters of the closed neural model. */
    public static float getHexQuadLuma() {
        return RawTherapeeSettings.number("hexquad_luma",50,0,100);
    }

    public static float getHexQuadChroma() {
        return RawTherapeeSettings.number("hexquad_chroma",100,0,100);
    }

    public static int getHexQuadModelScale() {
        return "1".equals(preferenceKeys.settingsManager.getString("default_scope","hexquad_model","2"))?1:2;
    }

    public static boolean isHexQuadAutoIso() {
        return preferenceKeys.settingsManager.getBoolean("default_scope","hexquad_auto_iso",false);
    }

    public static HexQuadOptions getHexQuadOptions(int iso) {
        return new HexQuadOptions(iso,getHexQuadModelScale(),
                false, // SCAM HDR keeps the network output at the input size
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

    /** SCAM: flush the HAL request queue before the bracket (shutter lag ~0.4 s -> ~0.15 s). */
    public static boolean isScamFastCapture() {
        if (isHybridShot()) return hybridSwitch("fast_capture", true);
        return preferenceKeys.settingsManager.getBoolean("default_scope", "pref_scamold_fast_capture", true);
    }

















    /**
     * The scam-style processing route (own merge, SCAMERA tone, no second HDR+ denoise): the SCAM Hybrid (its own switch,
     * section «SCAM-гибрид») or the autonomous HDR / SCAM HDR switches. Exclusive with the native remosaic engines and
     * RAW MFSR; stored choices are retained.
     */
    public static boolean isScamHdrEnabled() {
        return isScamHybridEnabled() || isAutonomousHdrSwitchOn();
    }
    /** The SCAM HDR route is selected and the RAW path is one SCAM HDR can merge. */
    private static boolean isAutonomousHdrSwitchOn() {
        return isScamHdrRoute();
    }

    /**
     * SCAM HDR can run here: its SCAM network is a Hexagon v79 context, so only on the Snapdragon 8 Elite (SM8750). Every
     * other phone is hybrid-only, whatever the stored route says (owner, 2026-10-07).
     */
    public static boolean isScamHdrSupported() {
        return ScamHybridKeys.scamNetSoc();
    }

    /**
     * The merge route (pref_merge_route): "hybrid" (SCAM Hybrid, the default on every phone) or "scamhdr" (the SCAM HDR
     * network), the latter only where {@link #isScamHdrSupported()}. scam_dev.txt "hybrid 1/0" picks the route for A/B
     * tests on such a phone.
     */
    public static String mergeRoute() {
        if (!isScamHdrSupported()) return ScamHybridKeys.ROUTE_HYBRID;
        Float override = scamDevValue("hybrid");
        if (override != null) return override > 0f ? ScamHybridKeys.ROUTE_HYBRID : ScamHybridKeys.ROUTE_SCAM_HDR;
        String route = preferenceKeys.settingsManager.getString("default_scope", ScamHybridKeys.ROUTE, ScamHybridKeys.ROUTE_HYBRID);
        return ScamHybridKeys.ROUTE_SCAM_HDR.equals(route) ? ScamHybridKeys.ROUTE_SCAM_HDR : ScamHybridKeys.ROUTE_HYBRID;
    }
    public static boolean isScamHdrRoute() {
        return ScamHybridKeys.ROUTE_SCAM_HDR.equals(mergeRoute());
    }
    public static boolean isScamDespeckleEnabled() {
        if (isHybridShot()) return hybridSwitch("despeckle", true);
        return preferenceKeys.settingsManager.getBoolean("default_scope", "pref_scamhdr_post_despeckle", true);
    }
    public static boolean isScamDiagnosticsEnabled() {
        if (isHybridShot()) return hybridSwitch("diagnostics", false);
        return preferenceKeys.settingsManager.getBoolean("default_scope", "pref_scamhdr_diagnostics", false);
    }

    /**
     * Optional root features (off by default; everything works without root):
     * the vivo stock-AE observer for the SCAM bracket, and su as the fallback
     * launcher when the worker is not installed as a native library.
     */
    public static boolean isRootEnabled() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", "pref_root_enabled", false);
    }
    /**
     * true: the vivo stock AE solver through the root observer («Стоковый AE vivo»: Root on, vivo X200 Ultra only); false: the
     * SCAMERA planner (no root, any device; the default since 8 October 2026).
     */
    public static boolean useStockBracketPlanner() {
        // The hybrid's N frames are the ZSL ring at the preview exposure: always the SCAMERA plan of that exposure.
        if (isHybridShot()) return false;
        return isRootEnabled()
                && "stock".equals(preferenceKeys.settingsManager.getString("default_scope", "pref_scamhdr_planner", "scamera"))
                && com.particlesdevs.photoncamera.capture.ScamStockAe.supportedDevice();
    }
    /** SCAM: build L from the ZSL N frames instead of capturing it after the shutter. */
    public static boolean isScamZslLong() {
        if (isHybridShot()) return false; // the hybrid takes no L frame (Shasta brackets after the press instead)
        return preferenceKeys.settingsManager.getBoolean("default_scope", "pref_scamold_zsl_long", true);
    }
    public static boolean isScamPlannerAdaptive() {
        if (isHybridShot()) return hybridSwitch("planner_adaptive", true);
        return preferenceKeys.settingsManager.getBoolean("default_scope", "pref_scamhdr_planner_adaptive", true);
    }
    /** SCAM noise profile source: auto | imx06c | camera2 | settings. */
    public static String getScamNoiseSource() {
        if (isHybridShot()) return hybridString("noise_source", "auto");
        return preferenceKeys.settingsManager.getString("default_scope", "pref_scamhdr_noise_source", "auto");
    }
    /** RAW lens shading map: auto | apply | skip (HAL already corrected RAW_SENSOR). */
    public static String getRawLscMode() {
        return preferenceKeys.settingsManager.getString("default_scope", "pref_raw_lsc_mode", "auto");
    }
    /** Lower the reported RAW black level to the frame's measured dark floor when it is higher. */
    public static boolean isRawBlackFromData() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", "pref_raw_black_from_data", true);
    }
    /** SCAM motion (CRE) source: auto (vendor, else APK copy) | bundled (always APK copy). */
    public static String getScamCreSource() {
        if (isHybridShot()) return hybridString("cre_source", "auto");
        return preferenceKeys.settingsManager.getString("default_scope", "pref_scamhdr_cre_source", "auto");
    }
    /** ISO level 1..5 of the SCAM normal reference, as in the settings screen. */
    public static int scamIsoLevel(int iso) {
        return iso <= 200 ? 1 : iso <= 800 ? 2 : iso <= 3200 ? 3 : iso <= 12800 ? 4 : 5;
    }
    /** Luma/chroma strength inside SCAM (0..2, 1 = model as trained), with the ISO-level multiplier. */
    public static float getScamLuma(int iso) {
        return Math.max(0f, Math.min(2f, routeInternalValue("luma", 1f) * routeInternalValue("luma_iso" + scamIsoLevel(iso), 1f)));
    }
    public static float getScamChroma(int iso) {
        return Math.max(0f, Math.min(2f, routeInternalValue("chroma", 1f) * routeInternalValue("chroma_iso" + scamIsoLevel(iso), 1f)));
    }
    /** The SoC the SCAM HDR network was built for (Hexagon v79): SCAM HDR (SCAM) runs there; the SCAM Hybrid runs anywhere. */
    public static boolean isScamNetSoc() {
        return ScamHybridKeys.scamNetSoc();
    }

    // ---- SCAM Hybrid: a route of its own (section «SCAM-гибрид», scam_hybrid_screen), separate from SCAM HDR ----

    /**
     * Prefix of every SCAM Hybrid preference: pref_scam_hybrid_&lt;key&gt;. A new hybrid setting gets a key with this prefix in
     * res/xml/preferences.xml (category of scam_hybrid_screen), numeric bounds in SettingsNumericRules, and is read with
     * {@link #hybridValue}, {@link #hybridSwitch}, {@link #hybridString} or {@link #hybridList}.
     */
    public static final String HYBRID_PREFIX = ScamHybridKeys.PREFIX;
    /** The merge route selector (hybrid | scamhdr), see {@link #mergeRoute()}. */
    public static final String ROUTE_KEY = ScamHybridKeys.ROUTE;
    /** Hybrid defaults that differ from the fallback the shared nodes pass; any other key keeps the caller's fallback. */
    private static final Map<String, Float> HYBRID_DEFAULTS = new HashMap<>();
    static {
        // N frames from the ZSL ring: owner 2026-10-07 20 -> 30, owner 2026-10-10 back to 20 (P68; SettingsMigration moves a
        // stored former default); SCAM HDR SCAM: 4.
        HYBRID_DEFAULTS.put("zsl_frames", 20f);
        // P68 (owner, 10 October 2026), every phone: Sabre 6.1 kernel always, Mochi always, Bento always with 3 ultrashort frames,
        // Shasta always with 3 frames. The worker keeps its own defaults (replays unchanged); the app writes these keys when unset.
        HYBRID_DEFAULTS.put("sabre61", 1f);
        HYBRID_DEFAULTS.put("mochi", 2f);
        HYBRID_DEFAULTS.put("bento", 2f);
        HYBRID_DEFAULTS.put("bento_frames", 3f);
        HYBRID_DEFAULTS.put("shasta_mode", 2f);
        HYBRID_DEFAULTS.put("shasta_frames", 3f);
        HYBRID_DEFAULTS.put("shasta_motion_max", 4f);
        HYBRID_DEFAULTS.put("soft_tone", 0f);    // AgX + Exposure Fusion with the Bento headroom
        HYBRID_DEFAULTS.put("bento_factor", 8f); // SCAM ultrashort_tet_factor
    }

    /** The hybrid route is selected (pref_merge_route, see {@link #mergeRoute()}). */
    public static boolean isScamHybridSwitchOn() {
        return ScamHybridKeys.ROUTE_HYBRID.equals(mergeRoute());
    }
    /**
     * The SCAM Hybrid (Sabre 6.1 kernel x SCAM 9.6 rejection x Bento x Shasta, any GLES 3.1 GPU) takes the shot: its route is
     * selected and the RAW path is plain Bayer or the SCAMERA remosaic without RAW MFSR.
     */
    public static boolean isScamHybridEnabled() {
        return isScamHybridSwitchOn();
    }
    /** The SCAM HDR route is selected on a RAW path it can merge. */
    public static boolean isScamHdrSwitchOn() {
        return isAutonomousHdrSwitchOn();
    }
    /** SCAM HDR (the SCAM HDR network) takes the shot: its switches are on and the hybrid does not take it. */
    public static boolean isScamHdrScamEnabled() {
        return isScamHdrSwitchOn() && !isScamHybridEnabled();
    }
    /**
     * The SCAM capture route is used (ZSL RAW ring, SCAMERA plan, worker, SCAMERA tone), by the SCAM Hybrid or by SCAM HDR;
     * {@link #isScamHybridEnabled()} and {@link #isScamHdrScamEnabled()} tell them apart.
     */
    public static boolean isScamEnabled() {
        return isScamHybridEnabled() || isScamHdrSwitchOn();
    }

    private static final int SHOT_SCAM = 1, SHOT_HYBRID = 2;
    /**
     * Per-shot settings profile of the thread that processes the shot: HdrxProcessor sets it around its whole processing
     * (merge, post pipeline, saving) and clears it afterwards. The nodes shared by SCAM HDR and the hybrid then read the
     * knobs of the route that merged the shot, even when the switches changed between the press and the processing.
     * Thread-confined on purpose: the camera thread plans and captures the next shot (and the viewfinder runs) while the
     * SCAM processing thread still works on the previous one, and those must keep reading the live route.
     */
    private static final ThreadLocal<Integer> shotProfile = new ThreadLocal<>();
    public static void beginShotProfile(boolean hybrid) { shotProfile.set(hybrid ? SHOT_HYBRID : SHOT_SCAM); }
    public static void endShotProfile() { shotProfile.remove(); }
    /**
     * The SCAM Hybrid's settings are in force: on a thread processing a shot, whether the hybrid merged it; on any other
     * thread (viewfinder, capture and plan of the next shot) whether the hybrid takes the next shot. Then every SCAM HDR
     * getter of this class (routeInternalValue/Switch, scamHdrValue, fusion, despeckle, noise and CRE source, ...) reads the
     * hybrid's copy pref_scam_hybrid_&lt;key&gt; instead of the SCAM HDR key.
     */
    public static boolean isHybridShot() {
        final Integer profile = shotProfile.get();
        return profile != null ? profile == SHOT_HYBRID : isScamHybridEnabled();
    }
    /** A hybrid shot is being post-processed on this thread (strict: false outside HdrxProcessor). */
    public static boolean isHybridShotProcessing() {
        final Integer profile = shotProfile.get();
        return profile != null && profile == SHOT_HYBRID;
    }
    /** @deprecated the hybrid is not a SCAM HDR engine any more: {@link #isHybridShot()} (per shot) or {@link #isScamHybridEnabled()}. */
    @Deprecated
    public static boolean isScamHybridShot() { return isHybridShot(); }

    /**
     * Output of the hybrid merge (pref_scam_hybrid_output): "sensor" (1x grid, default), "12"/"16"/"20" = Sabre 6.1 2x
     * grid resized to that many megapixels (4:3 of the sensor; the sensor size itself when within 10 %), "2x" = the native
     * 2x grid. scam_dev.txt: "hybrid_output 0|12|16|20|2".
     */
    public static String hybridOutputMode() {
        Float dev = scamDevValue("hybrid_output");
        if (dev != null) { int v = Math.round(dev); return v == 2 ? "2x" : v == 12 || v == 16 || v == 20 ? String.valueOf(v) : "sensor"; }
        return hybridString("output", "sensor");
    }
    /**
     * Downsampler of the final bitmap after the 2x pipeline (pref_scam_hybrid_downsampler): "lanczos" (3 lobes),
     * "bicubic" (Catmull-Rom), "area" (box average), "bilinear". scam_dev.txt: "hybrid_downsampler 0..3".
     */
    public static String hybridDownsampler() {
        Float dev = scamDevValue("hybrid_downsampler");
        if (dev != null) { int v = Math.round(dev); return v == 1 ? "bicubic" : v == 2 ? "area" : v == 3 ? "bilinear" : "lanczos"; }
        return hybridString("downsampler", "lanczos");
    }
    private static final String[] HYBRID_OUTPUT_VALUES = {"sensor", "12", "16", "20", "2x"};
    private static final String[] HYBRID_DOWNSAMPLER_VALUES = {"lanczos", "bicubic", "area", "bilinear"};
    /** Stores pref_scam_hybrid_output by its index in {sensor, 12, 16, 20, 2x} (quick-settings chips). */
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
    /** "key value" lines for the worker's hybrid_tuning.txt, from the pref_scam_hybrid_* preferences (scam_dev.txt "hybrid_<key>"). */
    public static String hybridTuningText() {
        StringBuilder out = new StringBuilder();
        String[][] keys = {
            {"bento", "bento"},
            {"cdm", "cdm"},
            {"kernelScale", "kernel"}, {"weightCap", "weight_cap"},
            {"fwe", "fwe"}, {"dilateScale", "dilate"},
            {"shastaSharpness", "shasta_sharpness"}, {"bentoUsWeight", "bento_weight"},
            {"shastaMaxRatio", "shasta_max_ratio"}, {"filterVariance", "filter_variance"},
            {"bentoUsSigma", "bento_sigma"}, {"bentoFrames", "bento_frames"}, {"bentoChromaSigma", "bento_chroma_sigma"},
            {"dilateFloor", "dilate_floor"}, {"widenBelow", "widen_below"}, {"chromaDiff", "chroma_diff"},
            {"bentoValidate", "bento_validate"}, {"bentoMotionMax", "bento_motion_max"},
            // P68: motion fallback of forced Shasta frames, % of the blocks (the Shasta mode is written below)
            {"shastaMotionMax", "shasta_motion_max"},
            {"rawNoise", "tensor_noise"}, {"snrScale", "snr_scale"},
            {"boost", "boost_value"}, {"varianceThreshold", "boost_threshold"}, {"motionThreshold", "motion_threshold"},
            {"lutHiSigma", "lut_sigma"},
            // Round 5 (research/hybrid5/impl_worker.md): Sabre 6.1 kernel 0 off / 1 always / 2 auto (night: 6.1 SNR key <= s61MaxKey),
            // highlights and outliers (hot*), SCAM 9.6 Bento fallback checks. Absent keys keep the worker defaults.
            {"sabre61", "sabre61"}, {"s61MaxKey", "s61_max_key"}, {"s61Mode", "s61_mode"},
            // F6 tile-local alignment (research/hybrid5/f6_local_align.md): 0 off / 1 bilinear field / 2 constant per tile;
            // the 6.1 auto rule in daylight needs F6 and this much RMS donor motion; daylight 6.1 noise multipliers.
            {"localAlign", "local_align"}, {"s61MinMotion", "s61_min_motion"},
            {"s61DayTensorNoise", "s61_day_tensor_noise"}, {"s61DayGdNoise", "s61_day_gd_noise"},
            {"s61TensorNoise", "s61_tensor_noise"}, {"s61GdNoise", "s61_gd_noise"},
            {"hotSigma", "hot_sigma"}, {"hotBaseSigma", "hot_base_sigma"}, {"hotFrames", "hot_frames"},
            {"hotCross", "hot_cross"}, {"hotMaxLevel", "hot_max_level"}, {"hotMaxKey", "hot_max_key"},
            {"bentoInvalid", "bento_invalid"},
            // P14 / P22: frames of a Quad / Tetra burst merged (4 / 16 sub-frames each; worker default 30 since 7 October 2026, was 24;
            // SettingsMigration moves a stored 24 once), the kernel across
            // edges of the sub-frame merge (worker default 0.6), shared motion of a frame's sub-frames (worker default 1)
            {"mosaicFrames", "mosaic_frames"}, {"mosaicBlock", "mosaic_block"},
            {"mosaicEdgeScale", "mosaic_edge_scale"}, {"mosaicShare", "mosaic_share"},
            // P29 native mosaic merge (research/p29): mosaicPath 1 = native (worker default since P34; Tetra too since P35, mosaicTetra
            // 1 = T1, window 2 x mosaicWindow sensor px), 0 = the sub-frame split above; the keys below act only with 1 (worker defaults: window 3 full, kernel
            // scale 0.7, edge scale 0.6, flat-area kernel x2.4, eigenvalue clamp 2, ks 1 / 0.85, no fill, fill support 0.25; at the
            // 6.1 night key kernel scale 1 / edge scale 0.6, dev keys only; the full-window switch is written below).
            // The XML default of every row is stored when the screen is first opened (HybridSettingsTest): the stored former defaults
            // of a P29 build ("0" = the split, kernel scale 1) move to the native merge in SettingsMigration's defaults revision 6, the
            // stored former "Tetra path" default "0" to T1 in revision 7 (P35).
            {"mosaicPath", "mosaic_path"}, {"mosaicWindow", "mosaic_window"}, {"mosaicKernelScale", "mosaic_kernel_scale"},
            {"mosaicNativeEdgeScale", "mosaic_native_edge_scale"}, {"mosaicKernelG", "mosaic_kernel_g"},
            {"mosaicKernelRB", "mosaic_kernel_rb"}, {"mosaicChromaFill", "mosaic_chroma_fill"},
            {"mosaicFillSupport", "mosaic_fill_support"}, {"mosaicTetra", "mosaic_tetra"},
            {"mosaicNativeFlatScale", "mosaic_native_flat_scale"}, {"mosaicNativeClamp", "mosaic_native_clamp"},
            {"mosaicNativeNightKernelScale", "mosaic_native_night_kernel_scale"}, {"mosaicNativeNightEdgeScale", "mosaic_native_night_edge_scale"},
            // Tetra's own night point (worker defaults, dev keys only): kernel / edge scale, multiplier on the flat-area kernel
            {"mosaicTetraNightKernelScale", "mosaic_tetra_night_kernel_scale"}, {"mosaicTetraNightEdgeScale", "mosaic_tetra_night_edge_scale"},
            {"mosaicTetraNightFlatScale", "mosaic_tetra_night_flat_scale"},
            // P73: Tetra T1 kernel scale by day (worker default 0.35, the Quad kernel in sensor px; dev key only)
            {"mosaicTetraKernelScale", "mosaic_tetra_kernel_scale"},
            // P74 «Опыты JSR» (the switches jsr_phase / jsr_lca / jsr_auto2x are written below; all off by default)
            {"jsrPhaseStrength", "jsr_phase_strength"}, {"jsrPhaseMinSigma", "jsr_phase_min_sigma"},
            {"jsrAuto2xMaxGap", "jsr_auto2x_gap"}, {"jsrPoly", "jsr_poly"}, {"jsrPolyDegree", "jsr_poly_degree"},
            {"jsrPolyClamp", "jsr_poly_clamp"},
            // P28 RAW CA as RawTherapee's CA_correct_RT (worker default 0 = off): mode 1 = the base frame's field on the merged RGB,
            // 2 = every frame corrected on the GPU before the merge; RT's auto passes, manual red / blue (switches below)
            {"rawCa", "rawca_mode"}, {"rawCaPasses", "rawca_passes"}, {"rawCaRed", "rawca_red"}, {"rawCaBlue", "rawca_blue"},
            // P27: the measured exposure ratio instead of a metadata ratio the data reliably disagrees with (worker default 1 since
            // 7 October 2026; 0 = report only, scam_dev.txt "hybrid_gain_measured 0")
            {"gainMeasured", "gain_measured"},
            // P71: the noise model checked against the burst (worker default 1; scam_dev.txt "hybrid_noise_measured 0" = off)
            {"noiseMeasured", "noise_measured"},
            // P62 Mochi (GCam 11 PhotometricMerge): 0 off / 1 auto (GCam rule) / 2 force
            {"mochi", "mochi"},
            // Shot speed (W1.0): per-pass GPU times of the merge (a glFinish per pass) and the F6 threads, from scam_dev.txt
            // only ("hybrid_profile 1", "hybrid_la_threads 8"; no preference behind them). P33 W2.1: "hybrid_la_stream 0" merges with
            // the whole F6 field first (A/B), "hybrid_la_scam N" lowers the priority of the banded F6 threads.
            {"profile", "profile"}, {"laThreads", "la_threads"}, {"laStream", "la_stream"}, {"laScam", "la_scam"},
        };
        for (String[] k : keys) {
            Float dev = scamDevValue("hybrid_" + k[1]);
            String v = dev != null ? dev.toString() : hybridString(k[1], "");
            // P68: a key with an app default (HYBRID_DEFAULTS) is written also when unset; any other keeps the worker default
            if ((v == null || v.isEmpty()) && HYBRID_DEFAULTS.containsKey(k[1])) v = HYBRID_DEFAULTS.get(k[1]).toString();
            if (v == null || v.isEmpty()) continue;
            try { out.append(k[0]).append(' ').append(Float.parseFloat(v.trim())).append('\n'); } catch (NumberFormatException ignored) {}
        }
        // P69 had the per-pass GPU times on by default on the Redmi Note 11 Pro; P79: off again (the glFinish after every pass made
        // the merge 30-60 % slower on the OPPO replays, its output unchanged). The switch «Замер времени проходов GPU»
        // (pref_scam_hybrid_profile) or scam_dev.txt "hybrid_profile 1" turns them on (the dev value is written by the loop above).
        if (scamDevValue("hybrid_profile") == null && hybridSwitch("profile", false)) out.append("profile 1\n");
        // P79 (owner allowed up to 10 % quality for speed on the Redmi Note 11 Pro only): its donors take the base frame's 6.1 kernel
        // covariance (s61Mode 6, no per-frame covariance pass over 36 RAW sites a cell; Mali-G57 cells pass 1.1-2.6 s a shot). OPPO
        // replays: synthetic PSNR 46.24 -> 46.30 / 42.24 -> 42.24 dB, real handheld x7u detail energy -0.2 %.
        if (scamDevValue("hybrid_s61_mode") == null && hybridString("s61_mode", "").isEmpty() && DeviceDefaults.redmiNote11Pro())
            out.append("s61Mode 6\n");
        // P74 «Опыты JSR»: switches, written only when on (the worker keeps them off)
        if (hybridSwitch("jsr_phase", false)) out.append("jsrPhase 1\n");
        if (hybridSwitch("jsr_lca", false)) out.append("jsrLca 1\n");
        if (hybridSwitch("jsr_auto2x", false)) out.append("jsrAuto2x 1\n");
        final int shasta = getHybridShastaMode();
        if (shasta == 0) out.append("shastaEnable 0\n");
        else if (shasta == 2) out.append("shastaForce 1\n");
        // Rejection boost where the local motion varies (GCam 11 Z channel of the F6 field); a switch, not a number.
        Float boostDev = scamDevValue("hybrid_motion_boost");
        out.append("boostEnable ").append(boostDev != null ? boostDev : hybridSwitch("motion_boost", true) ? 1f : 0f).append('\n');
        // Switches of the worker's round-5 fixes (on by default there): written only when turned off.
        if (!hybridSwitch("cell_clip", true)) out.append("cellClip 0\n");
        if (!hybridSwitch("chroma_diff_clamp", true)) out.append("chromaDiffClamp 0\n");
        if (!hybridSwitch("bento_scam", true)) out.append("bentoScam 0\n");
        if (!hybridSwitch("rawca_auto", true)) out.append("rawCaAuto 0\n");
        if (!hybridSwitch("rawca_avoid_shift", true)) out.append("rawCaAvoidShift 0\n");
        if (!hybridSwitch("mosaic_window_full", true)) out.append("mosaicWindowFull 0\n");
        return out.toString();
    }
    /** The hybrid's N frames from the ZSL ring (pref_scam_hybrid_zsl_frames, 4..44, default 20). */
    public static int getHybridZslFrames() {
        return Math.round(hybridValue("zsl_frames", 20f));
    }
    /** P62 Mochi: GCam 11 photometric merge of bracketed frames on GPU (pref_scam_hybrid_mochi: 0 off, 1 auto, 2 force; default 2, P68). */
    public static int getHybridMochi() {
        return Math.round(hybridValue("mochi", 2f));
    }
    /**
     * P68 Shasta: 0 off (switch pref_scam_hybrid_shasta), else pref_scam_hybrid_shasta_mode 1 auto (the worker's sharpness gate and
     * ratio limit) or 2 always (default: the frames skip both and fall back only on motion, pref_scam_hybrid_shasta_motion_max).
     */
    public static int getHybridShastaMode() {
        if (!hybridSwitch("shasta", true)) return 0;
        return Math.max(1, Math.min(2, Math.round(hybridValue("shasta_mode", 2f))));
    }
    /**
     * SCAM Hybrid tone: the ArkCam 1.23 / SCAM 9.6 photo tone (ArkStats -> ArkFusion -> ArkCombine: Smart-HDR AE, exposure
     * fusion on a guided filter, OKLab grading, AgX Custom, detail of the Sabre merge) instead of the SCAMERA exposure,
     * Exposure Fusion and AgX render. pref_scam_hybrid_ark_tone, on by default; scam_dev.txt "hybrid_ark_tone 0/1".
     * Its knobs are pref_scam_hybrid_ark_&lt;key&gt; (read with {@link #hybridValue}, defaults = ArkCam 2.85 X8U).
     */
    /**
     * Master switch of every noise reduction of the hybrid (pref_scam_hybrid_denoise, on by default; scam_dev.txt
     * "hybrid_denoise 0/1"): off skips the denoise after the merge (GCam/SCAM finish or NLM, despeckle), the Bento
     * highlight denoise of the ARK fusion and the chroma denoise of lifted shadows. The merge itself is untouched.
     */
    public static boolean isHybridDenoiseEnabled() {
        return hybridSwitch("denoise", true);
    }
    /**
     * Sharpening of the ARK render, shared by both routes: "ark" (ArkCam's own luma sharpening before the detail delta,
     * ArkLumaSharpen), "rt" (RawTherapee with its own settings), "scam" (ScamSharpen) or "off" after the tone.
     * pref_scam_hybrid_sharp_mode (scam_dev.txt "hybrid_sharp_mode 0|1|2|3" = rt, scam, off, ark), default ark.
     */
    public static String scamSharpenMode() {
        Float dev = scamDevValue("hybrid_sharp_mode");
        if (dev != null) return dev >= 2.5f ? "ark" : dev >= 1.5f ? "off" : dev >= 0.5f ? "scam" : "rt";
        String v = hybridString("sharp_mode", "ark");
        return "rt".equals(v) || "scam".equals(v) || "off".equals(v) ? v : "ark";
    }
    /** RawTherapee USM / microcontrast amount multiplier on a hybrid shot (pref_scam_hybrid_sharp_strength), 1 elsewhere. */
    private static float hybridSharpStrength() {
        return isHybridShotProcessing() ? Math.max(0f, Math.min(2f, hybridValue("sharp_strength", 1f))) : 1f;
    }
    /** Weight of the other burst frames in the SCAM reference, 0..1 (1 = all frames). */
    public static float getScamMerge() {
        return Math.max(0f, Math.min(1f, routeInternalValue("merge", 100f) / 100f));
    }
    /** Extra EV for the SCAM long frame over the stock plan (0 = stock). */
    public static float getScamLongBoostEv() {
        return routeInternalValue("long_boost_ev", 1.1f);
    }
    /** Normal-exposure N frames taken from the ZSL ring: SCAM HDR 4..50; on a hybrid shot the hybrid's own count. */
    public static int getScamZslFrames() {
        return Math.round(routeInternalValue("zsl_frames", 4f));
    }



    private static long scamDevStamp = -1, scamDevChecked;
    private static java.util.Map<String, Float> scamDevValues = java.util.Collections.emptyMap();
    /**
     * Developer overrides for the SCAM internal values: "key value" lines in scam_dev.txt of the app's external files
     * dir (key without the pref_scamhdr_ prefix) replace the preference for the next shot. Re-read when the file
     * changes, at most every 2 s.
     */
    /** Developer switch from scam_dev.txt ("key 0" = off); {@code fallback} without the line. */
    public static boolean scamDevSwitch(String key, boolean fallback) {
        Float v = scamDevValue(key);
        return v == null ? fallback : v != 0f;
    }

    /** True when scam_dev.txt sets this line (an A/B override that wins over the stored setting). */
    public static boolean scamDevOverrides(String key) {
        return scamDevValue(key) != null;
    }

    /** The number of a scam_dev.txt line ("key value"); {@code fallback} without the line. */
    public static float scamDevNumber(String key, float fallback) {
        Float v = scamDevValue(key);
        return v == null ? fallback : v;
    }

    private static Float scamDevValue(String key) {
        long now = System.nanoTime();
        if (scamDevStamp == -1 || now - scamDevChecked > 2_000_000_000L) {
            scamDevChecked = now;
            java.util.Map<String, Float> values = java.util.Collections.emptyMap();
            long stamp = 0;
            try {
                java.io.File dir = com.particlesdevs.photoncamera.app.PhotonCamera.getAppContext().getExternalFilesDir(null);
                java.io.File file = dir == null ? null : new java.io.File(dir, "scam_dev.txt");
                if (file != null && file.isFile()) {
                    stamp = file.lastModified();
                    if (stamp == scamDevStamp) return scamDevValues.get(key);
                    values = new java.util.HashMap<>();
                    for (String line : new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8).split("\r?\n")) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length == 2) try { values.put(parts[0], Float.parseFloat(parts[1])); } catch (NumberFormatException ignored) { }
                    }
                }
            } catch (java.io.IOException | RuntimeException ignored) { }
            scamDevStamp = stamp;
            scamDevValues = values;
        }
        return scamDevValues.get(key);
    }

    /**
     * SCAM Hybrid number pref_scam_hybrid_&lt;key&gt;, clamped by its SettingsNumericRules bounds; scam_dev.txt
     * "hybrid_&lt;key&gt; v" overrides it; unset or invalid: the hybrid default of the key, else {@code fallback}.
     * Independent of the shot profile and of SCAM HDR.
     */
    public static float hybridValue(String key, float fallback) {
        Float override = scamDevValue("hybrid_" + key);
        if (override != null) return override;
        return hybridStored(key, fallback);
    }
    /** SCAM Hybrid switch pref_scam_hybrid_&lt;key&gt; (Boolean or "0"/"1"); scam_dev.txt "hybrid_&lt;key&gt; 0/1" overrides it. */
    public static boolean hybridSwitch(String key, boolean fallback) {
        Float override = scamDevValue("hybrid_" + key);
        if (override != null) return override > 0f;
        return hybridStoredSwitch(key, fallback);
    }
    /**
     * scam_dev.txt overrides of hybrid list settings for A/B without the menu: "hybrid_&lt;key&gt; i" picks the i-th entry value
     * (order of arrays.xml), e.g. "hybrid_dn_engine 1" = nlm. Other list keys have their own getters (output, downsampler,
     * sharp_mode) or none.
     */
    private static final Map<String, String[]> HYBRID_DEV_LISTS = new HashMap<>();
    static {
        HYBRID_DEV_LISTS.put("dn_engine", new String[]{"gcam", "nlm"});
        HYBRID_DEV_LISTS.put("dn_strength_map", new String[]{"auto", "frames", "uniform"});
    }
    /** SCAM Hybrid text value pref_scam_hybrid_&lt;key&gt; (a ListPreference entry value), {@code fallback} when unset. */
    public static String hybridString(String key, String fallback) {
        final String[] devList = HYBRID_DEV_LISTS.get(key);
        if (devList != null) {
            Float dev = scamDevValue("hybrid_" + key);
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
     * SCAM Hybrid number list pref_scam_hybrid_&lt;key&gt; = "a,b,c,..." (e.g. a value per noise level), each value clamped by
     * {@link SettingsNumericRules#listBounds}; {@code fallback} when unset, of another length or not a number.
     */
    public static float[] hybridList(String key, float[] fallback) {
        final String full = HYBRID_PREFIX + key;
        String v;
        try { v = preferenceKeys.settingsManager.getString("default_scope", full, ""); }
        catch (RuntimeException error) { v = ""; }
        return SettingsNumericRules.listValue(full, v, fallback);
    }
    /** Stores pref_scam_hybrid_&lt;key&gt;: a Boolean as a switch, anything else as text (quick-settings chips). */
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
    /** scam_dev.txt override of a shared knob on a hybrid shot: "hybrid_&lt;key&gt;", else the plain "&lt;key&gt;" line. */
    private static Float hybridDevValue(String key) {
        Float override = scamDevValue("hybrid_" + key);
        return override != null ? override : scamDevValue(key);
    }
    /**
     * The key a SCAM HDR preference is read from for the current shot: on a hybrid shot its hybrid copy (see
     * {@link #hybridCopyKey}), otherwise the key itself. For nodes that read pref_scamold_* / pref_agx_scam_* directly.
     */
    public static String profileKey(String key) {
        return key != null && isHybridShot() ? hybridCopyKey(key) : key;
    }
    /**
     * The hybrid's copy of a SCAM HDR key: pref_scamhdr_hybrid_&lt;k&gt;, pref_scamhdr_&lt;k&gt;, pref_scamold_&lt;k&gt; -&gt;
     * pref_scam_hybrid_&lt;k&gt;; pref_agx_scam_&lt;k&gt; -&gt; pref_scam_hybrid_agx_&lt;k&gt;; pref_scamroute_&lt;k&gt; -&gt;
     * pref_scam_hybrid_hdr_&lt;k&gt;; any other key is returned unchanged. The settings migration uses the same mapping.
     */
    public static String hybridCopyKey(String key) {
        return ScamHybridKeys.copyKey(key);
    }
    /** A number of a SCAM HDR key read directly (RawTherapeeSettings style), from the hybrid's copy on a hybrid shot. */
    public static float profileNumber(String key, float fallback, float lo, float hi) {
        return RawTherapeeSettings.number(profileKey(key), fallback, lo, hi);
    }

    /**
     * SCAM HDR internal value pref_scamhdr_&lt;key&gt;, clamped by SettingsNumericRules (scam_dev.txt "key v" overrides it).
     * On a hybrid shot the same call reads the hybrid's copy pref_scam_hybrid_&lt;key&gt; (scam_dev.txt "hybrid_&lt;key&gt;",
     * then "key") and never the SCAM HDR key; keys starting with "hybrid_" always name a hybrid setting.
     */
    public static float routeInternalValue(String key, float fallback) {
        if (key.startsWith("hybrid_")) return hybridValue(key.substring("hybrid_".length()), fallback);
        if (isHybridShot()) {
            Float override = hybridDevValue(key);
            return override != null ? override : hybridStored(key, fallback);
        }
        return scamInternalValue(key, fallback);
    }
    private static float scamInternalValue(String key, float fallback) {
        String fullKey="pref_scamhdr_"+key;
        Float override = scamDevValue(key);
        if (override != null) return override;
        try {
            return (float)SettingsNumericRules.value(fullKey,
                    preferenceKeys.settingsManager.getString("default_scope",fullKey,String.valueOf(fallback)),fallback);
        } catch(RuntimeException error) { return fallback; }
    }

    /** RAW stream format: auto | raw16 (RAW_SENSOR) | raw10 | raw12 — the camera must offer it, else auto order. */
    public static String getRawStreamFormat() {
        return preferenceKeys.settingsManager.getString("default_scope", "pref_raw_stream_format", "auto");
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
     * Signal-to-noise ratio the merged frame is aimed at. Denoise strength is
     * scaled by how far this shot falls short of it, so a clean frame is denoised
     * less than a noisy one without touching the sliders. GCam's dumps show a
     * merged estimate around 110 on a well-lit shot.
     */


    private static final String[] SNR_BANDS = {"verylow", "low", "med", "high", "veryhigh"};
    // Defaults fall from heavy at low SNR to light at high SNR. Chroma stays
    // higher than luma throughout: colour noise survives frame averaging better
    // and is the more objectionable of the two.
    private static final float[] DEF_LOW    = {1.60f, 1.30f, 1.00f, 0.80f, 0.60f};
    private static final float[] DEF_HIGH   = {0.70f, 0.85f, 1.00f, 1.10f, 1.20f};
    private static final float[] DEF_CHROMA = {2.00f, 1.60f, 1.20f, 0.90f, 0.70f};







    /** Develop the preview RAW stream as the viewfinder instead of showing the ISP image. */
    /** P24: DNGs with lossless JPEG (LJ92, compression 7) inside; off by default until the owner's readers are checked. */
    public static boolean isDngLossless() {
        return preferenceKeys.settingsManager.getBoolean("default_scope", "pref_dng_lossless", false);
    }

    /** P42: face detection mode (FaceDetectModes values). */
    public static String getFaceDetectMode() {
        return com.particlesdevs.photoncamera.control.subject.FaceDetectModes.normalize(preferenceKeys.settingsManager.getString(
                "default_scope", com.particlesdevs.photoncamera.control.subject.FaceDetectModes.KEY,
                com.particlesdevs.photoncamera.control.subject.FaceDetectModes.DEFAULT));
    }

    /** P42: tracking autofocus gesture (SubjectPolicy TRACK_* values). */
    public static String getTrackingAfMode() {
        return com.particlesdevs.photoncamera.control.subject.SubjectPolicy.normalizeTracking(preferenceKeys.settingsManager.getString(
                "default_scope", com.particlesdevs.photoncamera.control.subject.SubjectPolicy.TRACKING_KEY,
                com.particlesdevs.photoncamera.control.subject.SubjectPolicy.TRACK_DEFAULT));
    }

    public static boolean isLiveViewfinderRawEnabled() {
        return preferenceKeys.settingsManager.getBoolean(
                "default_scope", Key.KEY_LIVE_VIEWFINDER_RAW, false);
    }

    /** Retired ISP tone approximation. Old backups must not enable a second preview path. */
    @Deprecated
    public static boolean isLiveViewfinderLookEnabled() {
        return false;
    }































    /**
     * Global pre-shutter RAW capacity, independent of mode, lens and merge count.
     */
    public static int getZslBufferCountValue() {
        return Math.max(1, Math.min(100,
                preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_ZSL_BUFFER_COUNT, 50).intValue()));
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

    /** «Фильтр Байера»: -1 auto (the camera's CFA) or a forced 2x2 order 0..3; anything else (the removed MONO / QUAD) is auto. */
    public static int getCFAValue() {
        int v = preferenceKeys.settingsManager.getInteger("default_scope", Key.KEY_CFA).intValue();
        return v >= 0 && v <= 3 ? v : -1;
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





    public enum Key {
        KEY_PREF_VERSION(R.string._pref_version),
        KEY_SAVE_PER_LENS_SETTINGS(R.string.pref_save_per_lens_settings),
        KEY_SHOW_WATERMARK(R.string.pref_show_watermark_key),
        KEY_SHOW_ROUND_EDGE(R.string.pref_show_roundedge_key),
        KEY_SHOW_GRID(R.string.pref_show_grid_key),
        KEY_CAMERA_SOUNDS(R.string.pref_camera_sounds_key),
        KEY_TIMER_SOUND(R.string.pref_timer_sound_key),
        KEY_REMOSAIC_BLOCK(R.string.pref_remosaic_block_key),
        KEY_REMOSAIC_PROFILE(R.string.pref_remosaic_profile_key),
        KEY_REMOSAIC_STEERED(R.string.pref_remosaic_steered_key),
        KEY_REMOSAIC_CLAMP(R.string.pref_remosaic_clamp_key),
        KEY_REMOSAIC_FLATFIELD(R.string.pref_remosaic_flatfield_key),
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
        KEY_LIVE_VIEWFINDER_RAW(R.string.pref_live_viewfinder_raw_key),
        KEY_WIDE169(R.string.pref_wide169_key),
        KEY_ZSL_BUFFER_COUNT(R.string.pref_zsl_buffer_count_key),
        KEY_COLOR_METHOD(R.string.pref_color_method_key),
        KEY_FOCUS_PEAK(R.string.pref_peak_method_key),
        KEY_THEME(R.string.pref_theme_key),
        KEY_THEME_ACCENT(R.string.pref_theme_accent_key),
        KEY_SHOW_GRADIENT(R.string.pref_show_gradient_key),
        KEY_HIDE_GALLERY_ICON(R.string.pref_hide_gallery_icon_key),
        KEY_AF_MODE(R.string.pref_af_mode_key),
        KEY_AE_MODE(R.string.pref_ae_mode_key),
        KEY_AE_METERING_STD(R.string.pref_ae_metering_std_key),
        KEY_BRACKETING_MODE(R.string.pref_bracketing_key),
        KEY_COUNTDOWN_TIMER(R.string.pref_countdown_timer_key),
        KEY_VIDEO_RESOLUTION(R.string.pref_video_resolution_key),
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
        PER_LENS_FILE_NAME(R.string._per_lens),
        FOLDERS_LIST(R.string.pref_folders_list);

        public final String mValue;

        Key(int stringId) {
            this.mValue = PhotonCamera.getStringStatic(stringId);
        }
    }
}

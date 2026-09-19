package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.settings.*;
import com.particlesdevs.photoncamera.processing.opengl.postpipeline.Initial;
import java.lang.reflect.Field;
import com.particlesdevs.photoncamera.settings.ScameraPreferences;

/** Uses current module preferences, refreshed on the GL thread when they change. */
final class PreviewPhotoLook {
    private static void define(StringBuilder s,String key,Object value){
        String v=value instanceof Boolean?((Boolean)value?"1":"0"):String.valueOf(value);
        s.append("#define ").append(key).append(' ').append(v).append('\n');
    }
    private static float value(Initial initial,String name){
        try{Field f=Initial.class.getDeclaredField(name);f.setAccessible(true);return ((Number)f.get(initial)).floatValue();}
        catch(ReflectiveOperationException e){throw new IllegalStateException(name,e);}
    }
    static String defines(){
        Initial initial=new Initial();TunableInjector.inject(initial);
        StringBuilder s=new StringBuilder("#define PHOTO_LOOK_ENABLED 1\n");
        define(s,"ACES_ENABLED",PreferenceKeys.isAcesEnabled());
        define(s,"ACES_OUTPUT_P3",PreferenceKeys.getAcesOutput() != 0);
        define(s,"ACES_EXPOSURE",PreferenceKeys.getAcesExposure());
        define(s,"ACES_PEAK",PreferenceKeys.getAcesPeak());
        define(s,"ACES_GAMUT",PreferenceKeys.getAcesGamut());
        define(s,"ACES_HIGHLIGHT_DESAT",PreferenceKeys.getAcesHighlightDesat());
        define(s,"ACES_SURROUND",PreferenceKeys.getAcesSurround());
        define(s,"ACES_TONE_CURVE",Integer.toString(PreferenceKeys.getAcesToneCurve()));
        define(s,"ACES_GAMMA_CURVE",Integer.toString(PreferenceKeys.getAcesGammaCurve()));
        define(s,"ACES_TONE_MIX",PreferenceKeys.getAcesToneMix());
        define(s,"ACES_MID_GRAY",PreferenceKeys.getAcesMidGray());
        define(s,"ACES_TONE_CONTRAST",PreferenceKeys.getAcesToneContrast());
        define(s,"ACES_TOE",PreferenceKeys.getAcesToe());
        define(s,"ACES_SHOULDER",PreferenceKeys.getAcesShoulder());
        define(s,"ACES_HUE_PROTECTION",PreferenceKeys.getAcesHueProtection());
        define(s,"ACES_CUSTOM_GAMMA",PreferenceKeys.getAcesCustomGamma());
        define(s,"DARKTABLE_ENABLED",ScameraPreferences.darktableEnabled());
        define(s,"DT_EXPOSURE",ScameraPreferences.darktableExposure());
        define(s,"DT_FILMIC_CONTRAST",ScameraPreferences.darktableFilmicContrast());
        define(s,"DT_SHADOWS",ScameraPreferences.darktableShadows());
        define(s,"DT_HIGHLIGHTS",ScameraPreferences.darktableHighlights());
        define(s,"DT_LOCAL_CONTRAST",ScameraPreferences.darktableLocalContrast());
        define(s,"DT_COLORFULNESS",ScameraPreferences.darktableColorfulness());
        define(s,"DT_HIGHLIGHT_RECON",ScameraPreferences.darktableHighlightReconstruction());
        define(s,"DT_DIFFUSE_SHARPEN",ScameraPreferences.darktableDiffuseSharpen());
        define(s,"DT_PROFILED_DENOISE",ScameraPreferences.darktableProfiledDenoise());
        define(s,"DT_TONE_SHADOWS",ScameraPreferences.darktableToneShadows());
        define(s,"DT_TONE_MIDTONES",ScameraPreferences.darktableToneMidtones());
        define(s,"DT_TONE_HIGHLIGHTS",ScameraPreferences.darktableToneHighlights());
        define(s,"DT_BALANCE_SHADOWS",ScameraPreferences.darktableBalanceShadows());
        define(s,"DT_BALANCE_MIDTONES",ScameraPreferences.darktableBalanceMidtones());
        define(s,"DT_BALANCE_HIGHLIGHTS",ScameraPreferences.darktableBalanceHighlights());
        define(s,"DT_COLOR_RECON",ScameraPreferences.darktableColorReconstruction());
        define(s,"DT_CALIB_TEMP",ScameraPreferences.darktableCalibrationTemperature());
        define(s,"DT_CALIB_TINT",ScameraPreferences.darktableCalibrationTint());
        define(s,"DT_COLOR_RED",ScameraPreferences.darktableColorRed());
        define(s,"DT_COLOR_GREEN",ScameraPreferences.darktableColorGreen());
        define(s,"DT_COLOR_BLUE",ScameraPreferences.darktableColorBlue());
        define(s,"DT_WIDE_CA",ScameraPreferences.darktableWideCa());
        define(s,"DT_VIGNETTE",ScameraPreferences.darktableVignette());
        define(s,"DT_HAZE",ScameraPreferences.darktableHazeRemoval());
        define(s,"DT_TEXTURE",ScameraPreferences.darktableTexture());
        define(s,"LTMMIX",value(initial,"ltmMix"));
        define(s,"PHOTO_GAMMA",value(initial,"gammaKoefficientGenerator"));
        define(s,"PHOTO_HIGH_SATURATION",value(initial,"highersatmpy"));
        define(s,"SATURATIONRED",value(initial,"saturationRed"));
        define(s,"PHOTO_TONE_MIX",value(initial,"toneMix"));
        define(s,"GAMMAX1",value(initial,"gammax1"));define(s,"GAMMAX2",value(initial,"gammax2"));define(s,"GAMMAX3",value(initial,"gammax3"));
        define(s,"TONEMAPX1",value(initial,"tonemapx1"));define(s,"TONEMAPX2",value(initial,"tonemapx2"));define(s,"TONEMAPX3",value(initial,"tonemapx3"));
        define(s,"CONTRAST",(float)PhotonCamera.getSettings().contrastMpy);
        define(s,"SHADOWS",(float)PhotonCamera.getSettings().shadows);
        define(s,"PHOTO_SATURATION",(float)PhotonCamera.getSettings().saturation);
        return s.toString();
    }
    static String shader(String defines){
        String look=PhotonCamera.getAssetLoader().getString("shaders/preview/photo_look.glsl")
            .replace("/*PHOTO_HSV*/",PhotonCamera.getAssetLoader().getString("shaders/utils/import_photohsv.glsl"))
            .replace("/*PHOTO_SATURATION*/",PhotonCamera.getAssetLoader().getString("shaders/utils/import_photosaturation.glsl"))
            .replace("/*PHOTO_ACES*/",PhotonCamera.getAssetLoader().getString("shaders/utils/import_photoaces.glsl"))
            .replace("/*PHOTO_DARKTABLE*/",PhotonCamera.getAssetLoader().getString("shaders/utils/import_photodarktable.glsl").replace("vec2(INSIZE)","photoViewport"));
        return PhotonCamera.getAssetLoader().getString("shaders/preview/rawdevelop_fs.glsl")
            .replace("#version 300 es","#version 300 es\n"+defines).replace("/*PHOTO_LOOK*/",look);
    }
}

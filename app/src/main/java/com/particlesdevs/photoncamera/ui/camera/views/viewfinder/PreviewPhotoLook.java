package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;
import com.particlesdevs.photoncamera.app.PhotonCamera;

/**
 * Look of the live RAW viewfinder: the former PhotonCamera gamma and tone curve at their fixed defaults (the ACES,
 * darktable and Initial tuning that used to feed it went with the legacy photo pipeline).
 */
final class PreviewPhotoLook {
    private static final String DEFINES = "#define PHOTO_LOOK_ENABLED 1\n"
            + "#define LTMMIX 0.0\n"
            + "#define PHOTO_GAMMA 2.2\n"
            + "#define PHOTO_HIGH_SATURATION 1.0\n"
            + "#define SATURATIONRED 1.0\n"
            + "#define PHOTO_TONE_MIX 0.5\n"
            + "#define GAMMAX1 7.1896\n#define GAMMAX2 -50.8195\n#define GAMMAX3 129.3564\n"
            + "#define TONEMAPX1 -0.15\n#define TONEMAPX2 2.55\n#define TONEMAPX3 -1.6\n"
            + "#define CONTRAST 0.0\n"
            + "#define SHADOWS 0.0\n"
            + "#define PHOTO_SATURATION 1.0\n";
    static String defines(){
        return DEFINES;
    }
    static String shader(String defines){
        String look=PhotonCamera.getAssetLoader().getString("shaders/preview/photo_look.glsl")
            .replace("/*PHOTO_HSV*/",PhotonCamera.getAssetLoader().getString("shaders/utils/import_photohsv.glsl"))
            .replace("/*PHOTO_SATURATION*/",PhotonCamera.getAssetLoader().getString("shaders/utils/import_photosaturation.glsl"));
        return PhotonCamera.getAssetLoader().getString("shaders/preview/rawdevelop_fs.glsl")
            .replace("#version 300 es","#version 300 es\n"+defines).replace("/*PHOTO_LOOK*/",look);
    }
}

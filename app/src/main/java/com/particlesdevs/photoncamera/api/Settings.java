package com.particlesdevs.photoncamera.api;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Allocator;

public class Settings {
    private final String TAG = "Settings";
    //Preferences

    public int frameCount;
    public boolean watermark;
    public boolean aspect169;
    public boolean binning;
    public boolean DebugData;
    public boolean roundEdge;
    /** Ultra HDR in effect: the switch is on and the shot writes a JPEG (JPEG format or «Также сохранять JPEG»). */
    public boolean ultraHdr;
    /** P46 «HDR в HEIC / AVIF» in effect for this shot (processing.color.HdrOutput.wanted): the HEIC / AVIF is HLG. */
    public boolean hdrOutput;
    /** P46 «Цветовое пространство»: the primaries the post pipeline renders in and the files declare. */
    public com.particlesdevs.photoncamera.processing.color.OutputColour.Space colourSpace =
            com.particlesdevs.photoncamera.processing.color.OutputColour.Space.SRGB;
    public int contrastConst = 0;//TODO
    public double mergeStrength;
    public int rawSaver;
    public boolean QuadBayer;
    public int cfaPattern;
    public int theme;
    public boolean remosaic;//TODO
    public boolean eisPhoto;
    public int fpsMode;
    public int alignAlgorithm;

    public int colorMethod;
    public int focusPeak;
    public String mCameraID;
    public float[] toneMap;
    public float[] gamma;

    //Camera direct related
    public CameraMode selectedMode;

    public void loadCache() {
        frameCount = 4; // both routes take at least four N frames (the legacy frame-count setting is gone)
        watermark = PreferenceKeys.isShowWatermarkOn();
        aspect169 = PreferenceKeys.getBool(PreferenceKeys.Key.KEY_WIDE169);
        binning = false; // both routes merge the full-resolution RAW (software binning was a legacy option)
        Allocator.binning = binning;
        DebugData = PreferenceKeys.isFullDebugOn();
        roundEdge = PreferenceKeys.isRoundEdgeOn();
//        contrastConst = get(contrastConst, "ContrastConst");///////TODO
        mergeStrength = 1f;
        cfaPattern = PreferenceKeys.getCFAValue();
        rawSaver = PreferenceKeys.isSaveRaw();
        remosaic = PreferenceKeys.isRemosaicOn();
        eisPhoto = PreferenceKeys.isEisPhotoOn();
        QuadBayer = PreferenceKeys.isQuadBayerOn();
        fpsMode = PreferenceKeys.getFpsMode();
        // The gain-map pass (linear snapshot, no deferred GL teardown, no in-pipeline resize) runs only when a JPEG carries it.
        ultraHdr = PreferenceKeys.isUltraHdrActive();
        hdrOutput = com.particlesdevs.photoncamera.processing.color.HdrOutput.wanted();
        colourSpace = PreferenceKeys.getOutputColourSpace();
        alignAlgorithm = PreferenceKeys.getAlignMethodValue();
        colorMethod = PreferenceKeys.getColorMethodValue();
        focusPeak = PreferenceKeys.getFocusPeakValue();
        selectedMode = CameraMode.valueOf(PreferenceKeys.getCameraModeOrdinal());
        toneMap = parseToneMapArray();
        gamma = parseGammaArray();
        mCameraID = PreferenceKeys.getCameraID();
        theme = PreferenceKeys.getThemeValue();
    }

    /**
     * Whether the shot runs the Ultra HDR gain-map pass (linear snapshot, no deferred GL teardown, no in-pipeline resize):
     * for an Ultra HDR JPEG or for the HDR HEIC / AVIF (P46). Without «HDR в HEIC / AVIF» this is {@link #ultraHdr}.
     */
    public boolean gainMapPass() {
        return ultraHdr || hdrOutput;
    }

    public void saveID() {
        PreferenceKeys.setCameraID(mCameraID);
    }

    float[] parseToneMapArray() {
        String savedArrayAsString = PreferenceKeys.getToneMap();
        if (savedArrayAsString == null)
            return new float[0];
        String[] array = savedArrayAsString.replace("[", "").replace("]", "").split(",");
        float[] finalArray = new float[array.length];
        for (int i = 0; i < array.length; i++) {
            finalArray[i] = Float.parseFloat(array[i].trim());
        }
        return finalArray;
    }

    float[] parseGammaArray() {
        String savedArrayAsString = PreferenceKeys.getPref(PreferenceKeys.Key.GAMMA);
        if (savedArrayAsString == null)
            return new float[0];
        String[] array = savedArrayAsString.replace("[", "").replace("]", "").split(",");
        float[] finalArray = new float[array.length];
        for (int i = 0; i < array.length; i++) {
            finalArray[i] = Float.parseFloat(array[i].trim());
        }
        return finalArray;
    }

}

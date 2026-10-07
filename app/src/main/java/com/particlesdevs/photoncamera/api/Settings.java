package com.particlesdevs.photoncamera.api;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Allocator;

import static android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE_OFF;

public class Settings {
    private final String TAG = "Settings";
    //Preferences

    public int frameCount;
    public int lumenCount;
    public int chromaCount;
    public boolean enhancedProcess;
    public boolean watermark;
    public boolean aspect169;
    public boolean binning;
    public boolean DebugData;
    public boolean roundEdge;
    public boolean align;
    public boolean hdrx;
    public boolean hdrxNR;
    /** Ultra HDR in effect: the switch is on and the shot writes a JPEG (JPEG format or «Также сохранять JPEG»). */
    public boolean ultraHdr;
    public double sharpness;
    public int contrastConst = 0;//TODO
    public double mergeStrength;
    public double gain;
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
    public int previewFormat;
    public String mCameraID;
    public float[] toneMap;
    public float[] gamma;

    //Camera direct related
    public int noiseReduction = NOISE_REDUCTION_MODE_OFF;
    public CameraMode selectedMode;

    public void loadCache() {
        noiseReduction = PreferenceKeys.isSystemNrOn();
        frameCount = 4; // both routes take at least four N frames (the legacy frame-count setting is gone)
        align = PreferenceKeys.isDisableAligningOn();
        lumenCount = PreferenceKeys.getLumaNrValue();
        chromaCount = PreferenceKeys.getChromaNrValue();
        enhancedProcess = PreferenceKeys.isEnhancedProcessionOn();
        watermark = PreferenceKeys.isShowWatermarkOn();
        aspect169 = PreferenceKeys.getBool(PreferenceKeys.Key.KEY_WIDE169);
        binning = false; // both routes merge the full-resolution RAW (software binning was a legacy option)
        Allocator.binning = binning;
        DebugData = PreferenceKeys.isFullDebugOn();
        roundEdge = PreferenceKeys.isRoundEdgeOn();
        sharpness = PreferenceKeys.getSharpnessValue();
//        contrastConst = get(contrastConst, "ContrastConst");///////TODO
        mergeStrength = 1f;
        gain = PreferenceKeys.getGainValue();
        hdrx = PreferenceKeys.isHdrxNrOn();
        cfaPattern = PreferenceKeys.getCFAValue();
        rawSaver = PreferenceKeys.isSaveRaw();
        remosaic = PreferenceKeys.isRemosaicOn();
        eisPhoto = PreferenceKeys.isEisPhotoOn();
        QuadBayer = PreferenceKeys.isQuadBayerOn();
        fpsMode = PreferenceKeys.getFpsMode();
        hdrxNR = PreferenceKeys.isHdrxNrOn();
        // The gain-map pass (linear snapshot, no deferred GL teardown, no in-pipeline resize) runs only when a JPEG carries it.
        ultraHdr = PreferenceKeys.isUltraHdrActive();
        alignAlgorithm = PreferenceKeys.getAlignMethodValue();
        colorMethod = PreferenceKeys.getColorMethodValue();
        focusPeak = PreferenceKeys.getFocusPeakValue();
        previewFormat = PreferenceKeys.getPreviewFormatValue();
        selectedMode = CameraMode.valueOf(PreferenceKeys.getCameraModeOrdinal());
        toneMap = parseToneMapArray();
        gamma = parseGammaArray();
        mCameraID = PreferenceKeys.getCameraID();
        theme = PreferenceKeys.getThemeValue();
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

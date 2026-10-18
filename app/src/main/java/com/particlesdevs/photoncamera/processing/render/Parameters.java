package com.particlesdevs.photoncamera.processing.render;

import android.annotation.SuppressLint;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.LensShadingMap;
import android.os.Build;

import com.particlesdevs.photoncamera.settings.annotations.SensorConfig;
import com.particlesdevs.photoncamera.settings.annotations.Tunable;
import com.particlesdevs.photoncamera.util.Log;
import android.util.Rational;
import android.util.SizeF;

import androidx.annotation.NonNull;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.processing.parameters.ExposureIndex;
import com.particlesdevs.photoncamera.processing.parameters.FrameNumberSelector;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.util.Allocator;

import java.util.Arrays;


public class Parameters {
    /** Frozen for the submitted RAW processing job. */
    public boolean scamHdrMode = false;
    public float scamHdrRawScale = 1f;
    /** Pixel scale of the merged RGB against the sensor grid (SCAM HDR hybrid on the Sabre 2x grid: 0.98..2). */
    public float outputScale = 1f;
    /** SCAM HDR hybrid 2x grid: final image size (sensor orientation) produced by the GPU resize node at the end of the pipeline; null = no resize. */
    public android.graphics.Point hybridFinalSize;
    /** Processor-owned linear sensor RGB; null unless SCAM completed a real burst. */
    public java.nio.ByteBuffer scamRgb;
    /** scamRgb is an Allocator buffer the pipeline may free as soon as it is uploaded (replaced by a decimated copy). */
    public boolean scamRgbOwned;
    /**
     * P190: frames of a FULLRES series the merge keeps whole (an explicit «Кадров в снимке 50 / 200 Мп», every 200 MP shot), 0 for
     * any other shot; the frames the shot asked for (0: not FULLRES).
     */
    public int fullResSeries, fullResRequested;
    private static final String TAG = "Parameters";
    private int analogIso;
    public int iso;
    public int multiFrameCount; // Actual equal-exposure RAWs merged by the native backend.
    public double exposureTime = 1.0/30.0; // Default to 1/30s if not available
    public byte cfaPattern;
    /** Physical 2x2 CFA reported by Camera2 (cfaPattern after a remosaic is the order it wrote). */
    public byte baseCfaPattern;
    // Per-capture policy, not a live settings lookup during asynchronous processing.
    /** Per-shot display correction; never changes sensor exposure, VST or Bayer DNG. */
    public Point rawSize;
    /** P27: linear factor the RAW frames were binned by (RawBin) before the merge; 1 = as captured. Set before FillConstParameters. */
    public int rawBinning = 1;
    /**
     * P162: the part of the frames RawCrop kept (in the frame as captured, {@link #rawCropFull} in size), null when they are
     * whole: the lens shading map and the hot pixels follow it. Set before FillDynamicParameters.
     */
    public com.particlesdevs.photoncamera.processing.RawCrop.Area rawCrop;
    public Point rawCropFull;
    /** P165: BaselineExposure of the DNG (EV; 0 = none): set for the merged DNG written over its headroom. */
    public float dngBaselineExposure;
    public boolean usedDynamic = false;
    public float[] blackLevel = new float[4];
    public float[] whitePoint = new float[3];
    /** Correlated colour temperature of the scene light (K): the DNG estimate, or the neutral-based one on the OPPO HAL. */
    public float sceneCct = 6504f;
    public int whiteLevel = 1023;
    /** P127: bit depth of the shot's RAW stream (RawLevels) and where its black / white came from. */
    public int levelBits;
    public String levelSource = "";
    /** P135: SNR of the finish input the denoise measured (ScamDenoise), NaN until it ran: the sharpening backs off by it. */
    public float finishSnr = Float.NaN;
    public static int mergeWhiteLevel = 65535;
    public int realWL = -1;
    public boolean hasGainMap;
    /** The OPPO PHY110 tuned ISP matrix (bright-scene group) rendered the shot instead of the DNG matrices. */
    public boolean oppoTunedCcm;
    public Point mapSize;
    public Rect sensorPix;
    public float[] gainMap;
    public float[] proPhotoToSRGB = new float[9];
    public float[] sensorToProPhoto = new float[9];
    /** 0..1 user control for retaining short-frame highlight headroom in SDR tone mapping. */
    public float highlightSuppressionStrength = 1.0f;
    public Point[] hotPixels;
    public float focalLength;
    public float aperture;
    public int cameraRotation;
    public NoiseModeler noiseModeler;
    public double effectiveStackSamples=Double.NaN;
    public ColorCorrectionTransform CCT;
    public SizeF sensorSize;
    public double angleX;
    public double angleY;
    public double perXAngle;
    public double perYAngle;
    public double XPerMm;
    public double YPerMm;
    public double[] cameraIntrinsic = new double[9];
    public double[] cameraIntrinsicRev = new double[9];
    public float[][] tonemapCurves = new float[3][];
    public float gammaCurve = 2.0f;

    public int tile = 16;
    public int tilesX = 0;
    public Point alignmentSize = new Point(0, 0);

    public int calibrationIlluminant1 = -1;
    public int calibrationIlluminant2 = -1;

    public float[] calibrationTransform1 = new float[9];
    public float[] ForwardTransform1 = new float[9];
    public float[] ColorMatrix1 = new float[9];
    public float[] ColorMatrix2 = new float[9];
    public float[] calibrationTransform2 = new float[9];
    public float[] ForwardTransform2 = new float[9];

    public boolean mirror = false;
    public String cameraID = ScameraApp.getSettings().mCameraID;
    public int physicalID = 0;
    public int logicalID = 0;

    @Tunable(title = "Use Dynamic Black Level", category = "Parameters", defaultValue = 0, min = 0, max = 1, step = 1,
            description = "Брать уровень чёрного из каждого кадра, а не из паспорта камеры. На части телефонов работает нестабильно."
    )
    boolean useDynamicBlackLevel;

    @Tunable(title = "Use Dynamic White Level", category = "Parameters", defaultValue = 1, min = 0, max = 1, step = 1,
            description = "Брать уровень белого из каждого кадра, а не из паспорта камеры. На части телефонов работает нестабильно."
    )
    boolean useDynamicWhiteLevel;

    @SensorConfig(title = "White Level Override",
            defaultValue = -1, min = -1, max = 65535, step = 1,
            description = "Белый уровень RAW вручную, в единицах RAW этого модуля; Авто — от камеры и по отсчётам кадра. Больше — снимок темнее; меньше — света выгорают раньше.")
    int whiteLevelOverride;

    @Tunable(title = "Disable front mirror", category = "Parameters", defaultValue = 0, min = 0, max = 1, step = 1,
            description = "Фото с фронтальной камеры сохраняются без зеркального отражения, как видит камера.")
    boolean disableMirror;

    @SensorConfig(title = "Black Level Override",
            description = "Чёрный уровень RAW вручную, в единицах RAW этого модуля; Авто — от камеры, ручное значение выключает «Уровень чёрного по кадру». Больше — тени темнее и проваливаются; меньше — тени серые.",
            min = -1.0f, max = 8192.0f, step = 1.0f, defaultValue = -1.0f
    )
    float blackLevelOverride;

    public void FillConstParameters(CameraCharacteristics characteristics, Point size) {
        com.particlesdevs.photoncamera.settings.TunableInjector.inject(this);
        cameraID = ScameraApp.getSettings().mCameraID;
        // Split x-y, x - logical, y - physical
        if(cameraID.contains("-")){
            String[] ids = cameraID.split("-");
            logicalID = Integer.parseInt(ids[0]);
            physicalID = Integer.parseInt(ids[1]);
        } else {
            physicalID = Integer.parseInt(cameraID);
            logicalID = Integer.parseInt(cameraID);
        }
        com.particlesdevs.photoncamera.settings.SensorConfigInjector.applyToSensor(String.valueOf(physicalID), this);

        rawSize = size;
        alignmentSize = new Point((size.x / (tile)) + 1, (size.y / (tile)) + 1);
        tilesX = (rawSize.x / 800) + 1;
        Integer analogue = characteristics.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY);
        if (analogue != null) {
            analogIso = analogue;
        } else analogIso = 100;
        for (int i = 0; i < 4; i++) blackLevel[i] = 64;
        Object ptr = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
        if (ptr != null) cfaPattern = (byte) (int) ptr;
        if (ScameraApp.getSettings().cfaPattern >= 0) {
            cfaPattern = (byte) ScameraApp.getSettings().cfaPattern;
        }
        baseCfaPattern = (cfaPattern >= 0 && cfaPattern <= 3) ? cfaPattern : 0;
        float[] flen = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        if (flen == null || flen.length <= 0) {
            flen = new float[1];
            flen[0] = 4.75f;
        }
        sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        XPerMm = rawSize.x / sensorSize.getWidth();
        YPerMm = rawSize.y / sensorSize.getHeight();


        double[] cameraIntrinsic = this.cameraIntrinsic;
        cameraIntrinsic[0] = flen[0];
        cameraIntrinsic[1] = 0.0;
        cameraIntrinsic[2] = rawSize.x / 2.0;
        cameraIntrinsic[3] = 0.0;
        cameraIntrinsic[4] = flen[0];
        cameraIntrinsic[5] = rawSize.y / 2.0;
        cameraIntrinsic[6] = 0.0;
        cameraIntrinsic[7] = 0.0;
        cameraIntrinsic[8] = 1.0;

        cameraIntrinsicRev[0] = 1.0;
        cameraIntrinsicRev[1] = 0.0;
        cameraIntrinsicRev[2] = -rawSize.x / 2.0;
        cameraIntrinsicRev[3] = 0.0;
        cameraIntrinsicRev[4] = 1.0;
        cameraIntrinsicRev[5] = -rawSize.y / 2.0;
        cameraIntrinsicRev[6] = 0.0;
        cameraIntrinsicRev[7] = 0.0;
        cameraIntrinsicRev[8] = flen[0];

        Log.d(TAG, "IntrinsicMatrix:\n"
                + cameraIntrinsic[0] + "," + cameraIntrinsic[1] + "," + cameraIntrinsic[2] + ",\n"
                + cameraIntrinsic[3] + "," + cameraIntrinsic[4] + "," + cameraIntrinsic[5] + ",\n"
                + cameraIntrinsic[6] + "," + cameraIntrinsic[7] + "," + cameraIntrinsic[8] + ",\n");
        angleX = (2 * Math.atan(sensorSize.getWidth() / ((double) flen[0] * 2)));
        angleY = (2 * Math.atan(sensorSize.getWidth() / ((double) flen[0] * 2)));
        perXAngle = rawSize.x / angleX;
        perYAngle = rawSize.y / angleY;
        Log.d(TAG, "Focal Length:" + flen[0]);
        focalLength = flen[0];

        float[] aperture = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
        if (aperture == null || aperture.length <= 0) {
            aperture = new float[1];
            aperture[0] = 1.8f;
        }
        Log.d(TAG, "Aperture:" + aperture[0]);
        this.aperture = aperture[0];

        Object whiteLevel = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
        if (whiteLevel != null) this.whiteLevel = ((int) whiteLevel);
        hasGainMap = false;
        mapSize = new Point(1, 1);
        gainMap = new float[4];
        gainMap[0] = 1.f;
        gainMap[1] = 1.f;
        gainMap[2] = 1.f;
        gainMap[3] = 1.f;
        sensorPix = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (sensorPix == null) {
            sensorPix = new Rect(0, 0, rawSize.x, rawSize.y);
        } else if (rawBinning > 1) {
            sensorPix = new Rect(sensorPix.left / rawBinning, sensorPix.top / rawBinning,
                    sensorPix.right / rawBinning, sensorPix.bottom / rawBinning);
        }
        // P108: a full-resolution RAW (Pixel 10 Pro / Pro XL: 8160x6144 against the binned 4080x3072 active array) is described
        // by the max-resolution active array
        if (sensorPix != null && rawSize != null && rawSize.x > sensorPix.width() * 3 / 2
                && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            Rect max = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION);
            sensorPix = max != null && max.width() <= rawSize.x && max.height() <= rawSize.y
                    ? new Rect(max) : new Rect(0, 0, rawSize.x, rawSize.y);
        }
        var facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
            mirror = true;
        }
        if(disableMirror){
            mirror = false;
        }
        //hotPixels = ScameraApp.getCameraFragment().mHotPixelMap;
    }

    public void FillDynamicParameters(CaptureResult result, CaptureRequest request, int ISO) {
        // Selection may change the reference after the first metadata pass.
        // Missing tags must not retain another frame's dynamic calibration.
        usedDynamic = false;
        hasGainMap = false;
        Integer staticWhite = CaptureController.mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
        if (staticWhite != null) whiteLevel = staticWhite;
        Integer sensivity = result.get(CaptureResult.SENSOR_SENSITIVITY);
        if (sensivity == null) {
            sensivity = request.get(CaptureRequest.SENSOR_SENSITIVITY);
            if (sensivity == null) {
                sensivity = ISO;
            }
        }
        iso = sensivity;
        noiseModeler = new NoiseModeler(result.get(CaptureResult.SENSOR_NOISE_PROFILE), analogIso, sensivity, cfaPattern);
        Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        if (exposure == null) {
            exposure = request.get(CaptureRequest.SENSOR_EXPOSURE_TIME);
            if (exposure == null) {
                exposure = 1000000000L / 30; // Default to 1/30s if not available
            }
        }
        exposureTime = ExposureIndex.time2sec(exposure);

        int[] blarr = new int[4];
        BlackLevelPattern level = CaptureController.mCameraCharacteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
        if (result != null) {
            boolean isHuawei = Build.BRAND.equals("Huawei");

            float[] reportedBlack = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
            Log.i(TAG, "black level static=" + level + " dynamic=" + java.util.Arrays.toString(reportedBlack)
                    + " whiteDynamic=" + result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL));
            try {
                gainMap = new float[]{1.f, 1.f, 1.f, 1.f};
                mapSize = new Point(1, 1);
                LensShadingMap lensMap = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);
                if (lensMap != null) {
                    gainMap = new float[lensMap.getGainFactorCount()];
                    mapSize = new Point(lensMap.getColumnCount(), lensMap.getRowCount());
                    lensMap.copyGainFactors(gainMap, 0);
                    hasGainMap = true;
                    if ((gainMap[(gainMap.length / 8) - (gainMap.length / 8) % 4]) == 1.0 &&
                            (gainMap[(gainMap.length / 2) - (gainMap.length / 2) % 4]) == 1.0 &&
                            (gainMap[(gainMap.length / 2 + gainMap.length / 8) - (gainMap.length / 2 + gainMap.length / 8) % 4]) == 1.0) {
                        hasGainMap = false;
                        if (isHuawei) {
                            Log.d(TAG, "DETECTED FAKE GAINMAP, REPLACING WITH STATIC GAINMAP");
                            gainMap = new float[Const.gainMap.length];
                            for (int i = 0; i < Const.gainMap.length; i += 4) {
                                float in = (float) Const.gainMap[i] + (float) Const.gainMap[i + 1] + (float) Const.gainMap[i + 2] + (float) Const.gainMap[i + 3];
                                in /= 4.f;
                                gainMap[i] = in;
                                gainMap[i + 1] = in;
                                gainMap[i + 2] = in;
                                gainMap[i + 3] = in;
                            }
                            mapSize = Const.mapSize;
                        }
                    }
                } else applyStoredShading(result);
            } catch (Exception e){
                Log.d(TAG, "Error retrieving lens shading map, disabling gain map: " + Log.getStackTraceString(e));
            }
            if (hasGainMap && rawShadingAlreadyApplied(CaptureController.mCameraCharacteristics)) {
                // The HAL delivered RAW with lens shading already corrected; applying the
                // map again brightens the edges up to ~5x and lifts their blacks.
                Log.i(TAG, "RAW lens shading already applied by HAL, gain map skipped");
                gainMap = new float[]{1.f, 1.f, 1.f, 1.f};
                mapSize = new Point(1, 1);
                hasGainMap = false;
            }
            // P118 «16:9»: the frames are a centred crop (Wide169) and the map covers the whole frame: its rows of the crop only
            // (P162: of the frame as captured, before RawCrop)
            final com.particlesdevs.photoncamera.api.Settings s = ScameraApp.getSettings();
            final Point captured = rawCrop != null && rawCropFull != null ? rawCropFull : rawSize;
            if (hasGainMap && mapSize.y > 1 && s != null && s.aspect169 && captured != null && sensorPix != null
                    && captured.x > captured.y && sensorPix.width() > 0) {
                final int full = (int) Math.round((double) captured.x * sensorPix.height() / sensorPix.width());
                if (com.particlesdevs.photoncamera.processing.Wide169.rows(captured.x, full) == captured.y)
                    gainMap = com.particlesdevs.photoncamera.processing.Wide169.cropGainMap(gainMap, mapSize.x, mapSize.y, full,
                            com.particlesdevs.photoncamera.processing.Wide169.firstRow(captured.x, full), captured.y);
                else Log.w(TAG, "16:9 frame " + captured + " is not the crop of " + captured.x + "x" + full + ": lens shading map kept");
            }
            // P162: the map over the kept part of the frame only
            if (hasGainMap && rawCrop != null && rawCropFull != null)
                gainMap = com.particlesdevs.photoncamera.processing.RawCrop.cropGainMap(gainMap, mapSize.x, mapSize.y,
                        rawCropFull.x, rawCropFull.y, rawCrop);
            hotPixels = result.get(CaptureResult.STATISTICS_HOT_PIXEL_MAP);
            if (rawBinning > 1) hotPixels = new Point[0]; // sensor coordinates; a binned site averages it away
            else if (hotPixels != null && rawCrop != null) hotPixels = keptHotPixels(hotPixels, rawCrop);
            ReCalcColor(false, result);
        }
        // P127: black and white at the bit depth of the stream (RawLevels: declared levels, the frame's dynamic ones in their own
        // units, what the session's samples proved), then the user's overrides
        if (level != null) level.copyTo(blarr, 0);
        final Integer dynamicWhite = result == null ? null : result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL);
        final com.particlesdevs.photoncamera.processing.RawLevels.Levels levels =
                com.particlesdevs.photoncamera.processing.RawLevels.forFrame(
                        level == null ? null : new float[]{blarr[0], blarr[1], blarr[2], blarr[3]},
                        staticWhite == null ? 0 : staticWhite,
                        result == null ? null : result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL),
                        dynamicWhite == null ? 0 : dynamicWhite, useDynamicBlackLevel, useDynamicWhiteLevel);
        System.arraycopy(levels.black, 0, blackLevel, 0, 4);
        whiteLevel = levels.white;
        usedDynamic = levels.dynamicBlack;
        levelBits = levels.bits;
        levelSource = levels.source;
        if (whiteLevelOverride >= 0) whiteLevel = whiteLevelOverride;
        if (blackLevelOverride >= 0) {
            for (int i = 0; i < 4; i++) blackLevel[i] = blackLevelOverride;
        }
        Log.i(TAG, "RAW levels: " + levels + (whiteLevelOverride >= 0 ? ", white override " + whiteLevelOverride : "")
                + (blackLevelOverride >= 0 ? ", black override " + blackLevelOverride : ""));
        Float aperture = result.get(CaptureResult.LENS_APERTURE);
        if (aperture == null) {
            aperture = request.get(CaptureRequest.LENS_APERTURE);
            if (aperture == null) {
                aperture = 1.8f; // Default to f/1.8 if not available
            }
        }
        this.aperture = aperture;

        Float focalLength = result.get(CaptureResult.LENS_FOCAL_LENGTH);
        if (focalLength == null) {
            focalLength = request.get(CaptureRequest.LENS_FOCAL_LENGTH);
            if (focalLength == null) {
                focalLength = 4.75f; // Default to 4.75mm if not available
            }
        }
        this.focalLength = focalLength;

        if (Allocator.binning) {
            for (int i = 0; i < blackLevel.length; i++) {
                blackLevel[i] = Math.min(blackLevel[i] * 4f, 65535f);
            }
            whiteLevel = Math.min(whiteLevel * 4, 65535);
        }
    }


    /**
     * Lowers blackLevel when the RAW itself proves it too high. Signal cannot sit below
     * black, so the dark floor of the frame is an upper bound for it. OPPO Find X8 Ultra
     * RAW_SENSOR reports 64 while the data floor drifts to ~60 per frame; subtracting 64
     * clips the weaker R and B channels first and turns the shadows green.
     * Estimate: third-lowest mean of 32x32-sample blocks per CFA channel (one dead column
     * or defect block cannot drive it), common for all channels. Never raises black.
     * buffer: packed 16-bit RAW, rowStride = width*2.
     */
    public void refineBlackLevel(java.nio.ByteBuffer buffer, int width, int height) {
        if (buffer == null || width < 128 || height < 128) return;
        final boolean floorOn = blackLevelOverride < 0 && PreferenceKeys.isRawBlackFromData();
        // Same frame again (SCAM re-reads the reference metadata): reuse what it showed.
        if (buffer == blackFloorSource) {
            widenToSamples(blackFloorMax, blackFloorLow);
            if (floorOn && blackFloorEstimate != Float.MAX_VALUE) applyBlackFloor(blackFloorEstimate);
            return;
        }
        java.nio.ShortBuffer data = buffer.duplicate().order(java.nio.ByteOrder.nativeOrder()).asShortBuffer();
        if (data.capacity() < width * height) return;
        final int bs = 32;
        int bw = width / 2 / bs, bh = height / 2 / bs;
        // Padding is not signal: the vivo X200 Pro (MediaTek, owner's log 2026-10-07) delivers its 4096x3072 RAW_SENSOR with
        // the image in 4000x3000 and the rest of the buffer exactly 0. Those blocks drove the floor to 0, black 64 was never
        // subtracted and every photo came out pink (black x WB gains R 2.5 / B 1.4). A sample below a quarter of the reported
        // black is padding or a masked area, never a drift of the black level (the OPPO's drift is 64 -> ~60): a block with
        // one is left out (blocks on the border of the padding hold both).
        float reportedMin = Float.MAX_VALUE;
        for (float b : blackLevel) reportedMin = Math.min(reportedMin, b);
        final float paddingBelow = reportedMin > 4f ? reportedMin * 0.25f : -1f;
        final int blocks = bw * bh;
        final float[][] means = new float[4][blocks];
        final boolean[][] low = new boolean[4][blocks], zero = new boolean[4][blocks];
        int padding = 0, max = 0, max2 = 0;
        float lowest = Float.MAX_VALUE;
        for (int c = 0; c < 4; c++) {
            int ox = c % 2, oy = c / 2;
            for (int by = 0; by < bh; by++) for (int bx = 0; bx < bw; bx++) {
                // Every 4th sample of the block both ways (64 per block): the block mean
                // stays well below 1 DN of noise, at 1/16 of the full-frame read cost.
                long sum = 0;
                boolean padded = false, allZero = true;
                for (int y = 0; y < bs; y += 4) {
                    int row = (by * bs + y) * 2 + oy;
                    int base = row * width + bx * bs * 2 + ox;
                    for (int x = 0; x < bs; x += 4) {
                        int v = data.get(base + x * 2) & 0xffff;
                        sum += v;
                        if (v > max) { max2 = max; max = v; } else if (v > max2) max2 = v;
                        padded |= v < paddingBelow; // a block on the border of the padding: its mean is half signal
                        allZero &= v == 0;
                    }
                }
                final int i = by * bw + bx;
                means[c][i] = sum / (float) ((bs / 4) * (bs / 4));
                if (!allZero) lowest = Math.min(lowest, means[c][i]);
                low[c][i] = padded;
                zero[c][i] = allZero;
                if (padded) padding++;
            }
        }
        // P127: the frame's own samples beyond its white: a wider stream than the session had shown (black and white rescaled; the
        // padding test below depends on the black, so the blocks are read again at the new levels)
        final float darkest = lowest == Float.MAX_VALUE ? -1f : lowest;
        if (widenToSamples(max2, darkest)) {
            refineBlackLevel(buffer, width, height);
            return;
        }
        blackFloorSource = buffer;
        blackFloorMax = max2;
        blackFloorLow = darkest;
        blackFloorEstimate = Float.MAX_VALUE;
        if (!floorOn) return;
        // P114: padding is a strip of the frame (X200 Pro: 4.6 % of it), a noisy night dips below the quarter now and then. When
        // most of the frame holds such samples the HAL delivered the RAW without its black pedestal (OPPO Find X8 Ultra, owner's
        // log 2026-10-13 11:51-12:43 until the phone was restarted: 80-97 % of the blocks, data floor 18-23 DN for a reported 64,
        // the bracket's measured gains +20 % / -20 %, teal shadows): the floor is then measured over every block but the zero
        // padding (blocks of exact zeros and their neighbours), else the darkest blocks of the frame are the ones left out.
        final boolean pedestalLost = paddingBelow > 0 && padding > 2 * blocks;
        if (pedestalLost) Log.w(TAG, "RAW black floor: " + padding + " of " + (4 * blocks) + " blocks hold samples below " + paddingBelow
                + " DN: not padding, the RAW came without its black pedestal; measured over every block but zero padding");
        else if (padding > 0) Log.i(TAG, "RAW black floor: " + padding + " of " + (4 * blocks) + " blocks below " + paddingBelow
                + " DN skipped (padding / masked area, not signal)");
        float estimate = Float.MAX_VALUE;
        for (int c = 0; c < 4; c++) {
            float low1 = Float.MAX_VALUE, low2 = Float.MAX_VALUE, low3 = Float.MAX_VALUE;
            for (int by = 0; by < bh; by++) for (int bx = 0; bx < bw; bx++) {
                final int i = by * bw + bx;
                if (pedestalLost ? nearZeroBlock(zero[c], bw, bh, bx, by) : low[c][i]) continue;
                final float mean = means[c][i];
                if (mean < low1) { low3 = low2; low2 = low1; low1 = mean; }
                else if (mean < low2) { low3 = low2; low2 = mean; }
                else if (mean < low3) low3 = mean;
            }
            estimate = Math.min(estimate, low3);
        }
        if (estimate == Float.MAX_VALUE) return;
        blackFloorEstimate = estimate;
        applyBlackFloor(estimate);
    }

    /**
     * P127: two samples of the shot's frame above its white (the second largest: one hot pixel is no range) prove a stream wider
     * than the levels were resolved for (RawLevels.sampledBits: its range, 14 instead of 12 bits when the frame's darkest block
     * sits on the 14-bit pedestal): black and white rescaled to that depth (the user's overrides kept). True when changed.
     */
    boolean widenToSamples(int secondMax, float lowestBlock) {
        if (whiteLevelOverride >= 0 || secondMax <= whiteLevel || !com.particlesdevs.photoncamera.processing.RawLevels.wideEnabled())
            return false;
        final int from = levelBits > 0 ? levelBits : com.particlesdevs.photoncamera.processing.RawLevels.bitsOfWhite(whiteLevel);
        float black = Float.MAX_VALUE;
        for (float b : blackLevel) black = Math.min(black, b);
        final int bits = com.particlesdevs.photoncamera.processing.RawLevels.sampledBits(secondMax, whiteLevel, black, lowestBlock);
        if (from <= 0 || bits <= from) return false;
        final int k = bits - from;
        if (blackLevelOverride < 0) for (int i = 0; i < 4; i++) blackLevel[i] = Math.min(65535f, blackLevel[i] * (1 << k));
        whiteLevel = (int) Math.min(65535L, ((whiteLevel + 1L) << k) - 1);
        levelBits = bits;
        levelSource += ", widened to the frame's samples (" + secondMax + ")";
        Log.i(TAG, "RAW levels: the frame's samples reach " + secondMax + ": " + from + " -> " + bits + " bit, black "
                + Arrays.toString(blackLevel) + ", white " + whiteLevel);
        return true;
    }

    /** P114: a block of exact zeros (padding) or next to one (its border holds both). */
    private static boolean nearZeroBlock(boolean[] zero, int bw, int bh, int bx, int by) {
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            int x = bx + dx, y = by + dy;
            if (x >= 0 && y >= 0 && x < bw && y < bh && zero[y * bw + x]) return true;
        }
        return false;
    }

    private java.nio.ByteBuffer blackFloorSource;
    private int blackFloorMax;
    private float blackFloorLow = -1f;
    /** Where the black and white levels of the shot came from (diagnostics): bit depth, override / data floor / dynamic / static. */
    public String levelSources() {
        String black = blackLevelOverride >= 0 ? "override" : blackFloorSource != null && blackFloorEstimate != Float.MAX_VALUE
                ? "data floor (" + blackFloorEstimate + ")" : usedDynamic ? "dynamic" : "static";
        String white = whiteLevelOverride >= 0 ? "override" : useDynamicWhiteLevel ? "dynamic if reported" : "static";
        return levelBits + " bit, black=" + black + " white=" + white + " [" + levelSource + "]";
    }
    private float blackFloorEstimate = Float.MAX_VALUE;

    private void applyBlackFloor(float estimate) {
        // ~1 DN of a 10-bit range; below that it is noise in the estimate, not a wrong black.
        float margin = Math.max(1f, whiteLevel / 1023f);
        boolean changed = false;
        float[] before = blackLevel.clone();
        for (int i = 0; i < 4; i++) {
            if (blackLevel[i] > estimate + margin) { blackLevel[i] = estimate; changed = true; }
        }
        if (changed) Log.i(TAG, "RAW black level from data: " + Arrays.toString(before)
                + " -> " + Arrays.toString(blackLevel) + " (data floor " + estimate + ")");
    }

    /**
     * Whether RAW_SENSOR already carries the HAL's lens shading correction, so the
     * STATISTICS_LENS_SHADING_CORRECTION_MAP must not be applied again.
     * Setting pref_raw_lsc_mode: auto | apply | skip; only "skip" drops the map.
     * SENSOR_INFO_LENS_SHADING_APPLIED is not trusted: vivo X200 Ultra reports true,
     * yet its RAW renders correctly only with the map applied.
     */
    public static boolean rawShadingAlreadyApplied(CameraCharacteristics characteristics) {
        return "skip".equals(PreferenceKeys.getRawLscMode());
    }

    /**
     * P212e (W1 = M2): a frame without a lens shading map that is an OPPO camera's native FULLRES frame (its native size is the
     * key of a stored plain state, OppoPlainState) takes the camera's stored plain map - the bin of the light nearest to the
     * result's neutral - so a cold start straight into FULLRES no longer develops with 'lens shading map 1x1' (10 of 22 OPPO
     * FULLRES photos: corners 1.2-2.2 EV dark, colour-shaded). The floats are LensShadingMap.copyGainFactors' order, so the
     * crops below and the DNG gain map take them as they take a reported map. True when it did.
     */
    boolean applyStoredShading(@androidx.annotation.Nullable CaptureResult result) {
        if (rawSize == null || !com.particlesdevs.photoncamera.capture.OppoNativeFullRes.brand()) return false;
        float[] neutral = null;
        final Rational[] n = result == null ? null : result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
        if (n != null && n.length >= 3) neutral = new float[]{n[0].floatValue(), n[1].floatValue(), n[2].floatValue()};
        final com.particlesdevs.photoncamera.capture.OppoPlainState.Shading stored =
                com.particlesdevs.photoncamera.capture.OppoPlainState.shading(String.valueOf(physicalID), rawSize.x, rawSize.y, neutral);
        if (stored == null) return false;
        gainMap = stored.gains.clone();
        mapSize = new Point(stored.cols, stored.rows);
        hasGainMap = true;
        Log.i(TAG, "lens shading map " + stored + " (stored, the camera's plain session)");
        return true;
    }

    /**
     * P212e (W1): a usable white point for {@code wp} (the result's neutral as read) when it is none - NaN, Inf, zero or negative
     * (the OPPO native mode reports NaN; complete() leaves it when it has nothing to fill): the inverse of the result's
     * white-balance gains, else the camera's stored plain neutral, else equal gains. Null when {@code wp} is usable.
     */
    @androidx.annotation.Nullable
    static float[] unusableNeutralFallback(@androidx.annotation.Nullable float[] wp,
                                           @androidx.annotation.Nullable android.hardware.camera2.params.RggbChannelVector gains,
                                           @androidx.annotation.Nullable float[] stored) {
        if (usableNeutral(wp)) return null;
        if (gains != null) {
            final float g = 0.5f * (gains.getGreenEven() + gains.getGreenOdd());
            final float[] inv = {g / gains.getRed(), 1f, g / gains.getBlue()};
            if (g > 0f && usableNeutral(inv)) return inv;
        }
        if (usableNeutral(stored)) return new float[]{stored[0] / stored[1], 1f, stored[2] / stored[1]};
        return new float[]{1f, 1f, 1f};
    }

    private static boolean usableNeutral(@androidx.annotation.Nullable float[] wp) {
        if (wp == null || wp.length < 3) return false;
        for (int i = 0; i < 3; i++) if (!(wp[i] > 0f) || Float.isInfinite(wp[i])) return false;
        return true;
    }

    public float[] customNeutral;
    /** P121: the neutral is SCAMERA's own (a mosaic stream): the ISP's colour transform picked at the camera's neutral does not fit. */
    public boolean ownNeutral;
    /**
     * P212f (W2 = M3): the hand-set white balance's gains at the press (P201, ManualWhiteBalance.gains on the camera's
     * characteristics, as the requests got them; null: automatic then), so a switch back to Auto during the series does not leak
     * into the shot. Set before the merge.
     */
    @androidx.annotation.Nullable
    public android.hardware.camera2.params.RggbChannelVector manualWbAtPress;
    /** P212f: where the kept hand-set white balance came from (log), empty when none. */
    public String handSetSource = "";

    /** P212f (W2): the white point {G / R, 1, G / B} of hand-set gains (the formula of ReCalcColor's AWB-off branch); null: none. */
    @androidx.annotation.Nullable
    static float[] handSetNeutral(@androidx.annotation.Nullable android.hardware.camera2.params.RggbChannelVector gains) {
        if (gains == null) return null;
        final float green = (gains.getGreenEven() + gains.getGreenOdd()) * .5f;
        if (!(green > 0) || !(gains.getRed() > 0) || !(gains.getBlue() > 0)) return null;
        final float[] wp = {green / gains.getRed(), 1, green / gains.getBlue()};
        return usableNeutral(wp) ? wp : null;
    }

    /**
     * P212f (W2 = M3): a mosaic shot (ISZ / FULLRES) keeps a hand-set white balance (P201) instead of its own estimate - the
     * mosaic white balance replaced it on every such photo and its DNG (ReCalcColor(true) skips the AWB-off branch): the gains at
     * the press ({@link #manualWbAtPress}) first, else those of a result that says AWB OFF (an OPPO native result carries the
     * inverse of the neutral complete() filled - exactly the hand-set one, OppoNativeFullRes.setNeutral / OppoNativeWb.hand),
     * else, AWB OFF without gains, the white point already read. Sets {@link #customNeutral} (ownNeutral false: the ISP's colour
     * transform stays usable as on a 12 MP shot); the caller runs ReCalcColor(true). False: automatic white balance.
     */
    public boolean keepHandSetWhiteBalance(@androidx.annotation.Nullable CaptureResult meta) {
        float[] hand = handSetNeutral(manualWbAtPress);
        String how = "the press";
        if (hand == null) {
            final Integer awb = meta == null ? null : meta.get(CaptureResult.CONTROL_AWB_MODE);
            if (awb == null || awb != CaptureRequest.CONTROL_AWB_MODE_OFF) return false;
            hand = handSetNeutral(meta.get(CaptureResult.COLOR_CORRECTION_GAINS));
            how = "the result's gains (AWB off)";
            if (hand == null) {
                if (!usableNeutral(whitePoint)) return false;
                hand = whitePoint.clone();
                how = "the result's neutral (AWB off)";
            }
        }
        customNeutral = hand;
        ownNeutral = false;
        handSetSource = how;
        return true;
    }

    public void ReCalcColor(boolean customNeutr, CaptureResult result) {
        CameraCharacteristics characteristics = CaptureController.mCameraCharacteristics;
        Rational[] neutralR = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
        Integer awb = result.get(CaptureResult.CONTROL_AWB_MODE);
        android.hardware.camera2.params.RggbChannelVector manualGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS);
        if (!customNeutr) {
            if (neutralR != null)
                for (int i = 0; i < neutralR.length && i < whitePoint.length; i++) {
                    whitePoint[i] = neutralR[i].floatValue();
                }
            // P212e (W1): a NaN / missing neutral never reaches the photo (the OPPO native mode's, with nothing to fill it)
            final float[] read = neutralR == null ? null : whitePoint;
            final float[] fixed = usableNeutral(read) ? null : unusableNeutralFallback(read, manualGains,
                    com.particlesdevs.photoncamera.capture.OppoNativeFullRes.brand() && rawSize != null
                            ? com.particlesdevs.photoncamera.capture.OppoPlainState.neutral(String.valueOf(physicalID), rawSize.x, rawSize.y) : null);
            if (fixed != null) {
                Log.w(TAG, "the result's neutral " + java.util.Arrays.toString(whitePoint) + " is no white point: "
                        + java.util.Arrays.toString(fixed) + " used");
                whitePoint = fixed;
            }
        }
        else {
            whitePoint = customNeutral;
        }
        if (!customNeutr && awb != null && awb == CaptureRequest.CONTROL_AWB_MODE_OFF && manualGains != null) {
            float green=(manualGains.getGreenEven()+manualGains.getGreenOdd())*.5f;
            if(green>0 && manualGains.getRed()>0 && manualGains.getBlue()>0)
                whitePoint=new float[]{green/manualGains.getRed(),1,green/manualGains.getBlue()};
        }
        int ref1 = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1);
        int ref2;
        Object ref2obj = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2);
        if (ref2obj != null) {
            ref2 = (byte) ref2obj;
        } else {
            ref2 = ref1;
        }
        calibrationIlluminant1 = ref1;
        calibrationIlluminant2 = ref2;

        ColorSpaceTransform calibration1 = characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1);
        ColorSpaceTransform calibration2 = characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2);
        ColorSpaceTransform colorMat1 = characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1);
        ColorSpaceTransform colorMat2 = characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2);
        ColorSpaceTransform forwardt1 = characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1);
        ColorSpaceTransform forwardt2 = characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2);

        // Check if forward matrices have each component non-zero, otherwise replace with identity
        boolean invertible = true;
        for (int i = 0; i < 3; i++) {
            float sum = 0.0f;
            for (int j = 0; j < 3; j++) {
                if (forwardt1 != null)
                    sum += Math.abs(forwardt1.getElement(i, j).floatValue());
            }
            if(sum == 0.0f) invertible = false;
        }
        if(!invertible) {
            Log.d(TAG, "Forward matrix 1 is not invertible, using identity");
            forwardt1 = new ColorSpaceTransform(new Rational[]{
                    new Rational(1,1), new Rational(0,1), new Rational(0,1),
                    new Rational(0,1), new Rational(1,1), new Rational(0,1),
                    new Rational(0,1), new Rational(0,1), new Rational(1,1)
            });
        }
        invertible = true;
        for (int i = 0; i < 3; i++) {
            float sum = 0.0f;
            for (int j = 0; j < 3; j++) {
                if (forwardt2 != null)
                    sum += Math.abs(forwardt2.getElement(i, j).floatValue());
            }
            if(sum == 0.0f) invertible = false;
        }
        if(!invertible) {
            Log.d(TAG, "Forward matrix 1 is not invertible, using identity");
            forwardt2 = new ColorSpaceTransform(new Rational[]{
                    new Rational(1,1), new Rational(0,1), new Rational(0,1),
                    new Rational(0,1), new Rational(1,1), new Rational(0,1),
                    new Rational(0,1), new Rational(0,1), new Rational(1,1)
            });
        }

        Converter.convertColorspaceTransform(calibration1, calibrationTransform1);
        Converter.convertColorspaceTransform(calibration2, calibrationTransform2);
        Converter.convertColorspaceTransform(forwardt1, ForwardTransform1);
        Converter.convertColorspaceTransform(forwardt2, ForwardTransform2);
        Converter.convertColorspaceTransform(colorMat1, ColorMatrix1);
        Converter.convertColorspaceTransform(colorMat2, ColorMatrix2);

        float[] normalizedForwardTransform1 = ForwardTransform1.clone();
        float[] normalizedColorMatrix1 = ColorMatrix1.clone();
        float[] normalizedColorMatrix2 = ColorMatrix2.clone();
        float[] normalizedForwardTransform2 = ForwardTransform2.clone();

        Converter.normalizeFM(normalizedForwardTransform1);
        Converter.normalizeFM(normalizedForwardTransform2);

        Converter.normalizeFM(normalizedColorMatrix1);
        Converter.normalizeFM(normalizedColorMatrix2);
        float[] sensorToXYZ = new float[9];
        Log.d("Parameters", "calibrationTransform1: " + Arrays.toString(calibrationTransform1) + " calibrationTransform2: " + Arrays.toString(calibrationTransform2) + " normalizedColorMatrix1: " + Arrays.toString(normalizedColorMatrix1) + " normalizedColorMatrix2: " + Arrays.toString(normalizedColorMatrix2));
        double interpolationFactor = Converter.findDngInterpolationFactor(ref1,
                ref2, calibrationTransform1, calibrationTransform2,
                normalizedColorMatrix1, normalizedColorMatrix2, whitePoint);
        Log.d("Parameters", "Interpolation factor: " + interpolationFactor);
        {
            float mired1 = 1e6f / OppoTunedColor.cct(ref1), mired2 = 1e6f / OppoTunedColor.cct(ref2);
            sceneCct = 1e6f / (mired1 + (float) interpolationFactor * (mired2 - mired1));
        }
        boolean oppoTuned = false;
        if (OppoTunedColor.applies()) {
            float cctK = OppoTunedColor.estimateCct(characteristics, whitePoint);
            {
                float[] fl = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                float f0 = fl != null && fl.length > 0 ? fl[0] : 0f;
                Log.i(TAG, String.format(java.util.Locale.ROOT, "OPPO CCT: focal %.2f mm neutral R/G %.4f B/G %.4f ln(R/B) %.4f anchor %.3f -> %.0f K",
                        f0, whitePoint[0] / whitePoint[1], whitePoint[2] / whitePoint[1], Math.log(whitePoint[0] / whitePoint[2]), OppoTunedColor.anchor(f0), cctK));
            }
            sceneCct = cctK;
            float[] tunedForward = OppoTunedColor.forwardAt(characteristics, cctK);
            if (tunedForward != null) {
                normalizedForwardTransform1 = tunedForward.clone();
                Converter.normalizeFM(normalizedForwardTransform1);
                normalizedForwardTransform2 = normalizedForwardTransform1.clone();
                oppoTuned = true;
                oppoTunedCcm = true;
                Log.d(TAG, "OPPO tuned forward matrix at " + cctK + " K: " + Arrays.toString(normalizedForwardTransform1));
            }
        }
        Log.d("Parameters", "normalizedForwardTransform1:" + Arrays.toString(normalizedForwardTransform1) +
                " normalizedForwardTransform2:" + Arrays.toString(normalizedForwardTransform2));
        Converter.calculateCameraToXYZD50Transform(normalizedForwardTransform1, normalizedForwardTransform2,
                calibrationTransform1, calibrationTransform2, whitePoint,
                interpolationFactor, /*out*/sensorToXYZ);
        Log.d("Parameters", "sensorToXYZ: " + Arrays.toString(sensorToXYZ));
        Converter.multiply(Converter.sXYZtoProPhoto, sensorToXYZ, /*out*/sensorToProPhoto);
        //ColorSpaceTransform CST = ScameraApp.getCaptureController().mColorSpaceTransform;//= result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
        ColorSpaceTransform CST = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
        CST = oplusCcmInstead(CST, result);
        Log.d(TAG, "result colorCorrection transform=" + CST + " gains=" + result.get(CaptureResult.COLOR_CORRECTION_GAINS));
        assert calibration2 != null;
        assert forwardt1 != null;
        assert forwardt2 != null;
        CCT = new ColorCorrectionTransform();
        boolean wrongCalibration =
                forwardt1.getElement(0, 0).floatValue() == forwardt2.getElement(0, 0).floatValue() &&
                        forwardt1.getElement(1, 1).floatValue() == forwardt2.getElement(1, 1).floatValue() &&
                        forwardt1.getElement(2, 2).floatValue() == forwardt2.getElement(2, 2).floatValue() &&
                        forwardt1.getElement(1, 2).floatValue() == forwardt2.getElement(1, 2).floatValue();
        Rational[] rat = new Rational[9];
        if (ScameraApp.getSettings().colorMethod == 1){
            wrongCalibration = false;
        }
        if (ScameraApp.getSettings().colorMethod == 2){
            wrongCalibration = true;
        }
        if (CST != null) {
            CST.copyElements(rat, 0);
            int cnt = 0;
            for (int i = 0; i < 9; i++) {
                if (rat[i].floatValue() != 0.0f) cnt++;
            }
            if (cnt <= 4) wrongCalibration = false;
        } else wrongCalibration = false;
        if (oppoTuned) wrongCalibration = false;
        // vivo X200 Ultra: the DNG matrices in the characteristics are generic (the interpolation always lands near D65), but the
        // HAL reports the colour transform its ISP tuning uses at the scene's colour temperature, together with the
        // matching white balance gains. SCAM HDR renders with that matrix (the same idea as the OPPO tuned CCM per CCT).
        if (!oppoTuned && !ownNeutral && ispCcmUsable(CST)) {
            wrongCalibration = true;
            Log.d(TAG, "Using the ISP colour transform of the capture result (vivo)");
        }
        if (wrongCalibration) {
            sensorToProPhoto[0] = 1.0f / whitePoint[0];
            sensorToProPhoto[1] = 0.0f;
            sensorToProPhoto[2] = 0.0f;

            sensorToProPhoto[3] = 0.0f;
            sensorToProPhoto[4] = 1.0f / whitePoint[1];
            sensorToProPhoto[5] = 0.0f;

            sensorToProPhoto[6] = 0.0f;
            sensorToProPhoto[7] = 0.0f;
            sensorToProPhoto[8] = 1.0f / whitePoint[2];
            Log.d(TAG, "Using captured color correction transform");
        } else {
            Log.d(TAG, "Using calculated color correction transform");
        }
        Log.d(TAG, Arrays.toString(sensorToProPhoto) + ScameraApp.getSettings().colorMethod);
        Converter.multiply(Converter.sXYZtoSRGB, Converter.sProPhotoToXYZ, /*out*/proPhotoToSRGB);
        if (CST != null && wrongCalibration) {
            Rational[] temp = new Rational[9];
            CST.copyElements(temp, 0);
            for (int i = 0; i < 9; i++) {
                proPhotoToSRGB[i] = temp[i].floatValue();
            }
            //Normalize CST result
            normalize(proPhotoToSRGB);
        }

        CCT.matrix = proPhotoToSRGB;
        float[] dcpXyz = com.particlesdevs.photoncamera.processing.color.DcpProfiles.activeToXyz(whitePoint);
        if (dcpXyz != null) {
            Converter.multiply(Converter.sXYZtoProPhoto, dcpXyz, sensorToProPhoto);
            Converter.multiply(Converter.sXYZtoSRGB, Converter.sProPhotoToXYZ, proPhotoToSRGB);
            CCT = new ColorCorrectionTransform();
            CCT.matrix = proPhotoToSRGB;
        }
        // vivo: the DNG estimate above lands on a reference illuminant for every scene; the white balance of the shot
        // against the camera's daylight white gives the real colour temperature (the warm cast kept in the render,
        // the warm-light hue look).
        if (!oppoTuned && dcpXyz == null && wrongCalibration) {
            float wbCct = com.particlesdevs.photoncamera.processing.color.SceneIlluminant.cct(cameraID, whitePoint, CCT.matrix);
            if (wbCct > 0f) {
                Log.d(TAG, "Scene light from the white balance: " + wbCct + " K (DNG estimate " + sceneCct + " K, camera " + cameraID + ")");
                sceneCct = wbCct;
            }
        }
    }

    /**
     * P98: an identity colorCorrection.transform (OPPO Find X7 Ultra) is replaced by the OPPO ISP matrix of the same frame
     * (com.oplus.custom.ccm) when there is a valid one; any other transform is kept. scam_dev "oplus_ccm 0" turns it off.
     */
    private static ColorSpaceTransform oplusCcmInstead(ColorSpaceTransform cst, CaptureResult result) {
        if (cst == null || result == null || !PreferenceKeys.scamDevSwitch("oplus_ccm", true)) return cst;
        Rational[] r = new Rational[9];
        cst.copyElements(r, 0);
        float[] m = new float[9];
        for (int i = 0; i < 9; i++) m[i] = r[i].floatValue();
        if (!com.particlesdevs.photoncamera.processing.color.OplusCcm.identity(m)) return cst;
        float[] oplus;
        try {
            oplus = com.particlesdevs.photoncamera.processing.color.OplusCcm.parse(result.get(new CaptureResult.Key<>(
                    com.particlesdevs.photoncamera.processing.color.OplusCcm.TAG_NAME, byte[].class)));
        } catch (RuntimeException noTag) {
            return cst;
        }
        if (oplus == null) return cst;
        int[] q = new int[18];
        for (int i = 0; i < 9; i++) { q[2 * i] = Math.round(oplus[i] * 1024f); q[2 * i + 1] = 1024; }
        Log.d(TAG, "Identity colour transform replaced by the OPPO ISP matrix " + Arrays.toString(oplus));
        return new ColorSpaceTransform(q);
    }

    /** The reported transform of a vivo SCAM HDR capture: a full matrix whose rows sum to about 1 (white stays white). */
    private static boolean ispCcmUsable(ColorSpaceTransform cst) {
        if (cst == null || !PreferenceKeys.isScamEnabled() || PreferenceKeys.routeInternalValue("isp_ccm", 1f) <= 0f) return false;
        Rational[] r = new Rational[9];
        cst.copyElements(r, 0);
        int nonZero = 0;
        for (int i = 0; i < 9; i++) if (r[i].floatValue() != 0f) nonZero++;
        if (nonZero < 7) return false;
        for (int row = 0; row < 3; row++) {
            float sum = r[row * 3].floatValue() + r[row * 3 + 1].floatValue() + r[row * 3 + 2].floatValue();
            if (sum < 0.85f || sum > 1.15f || r[row * 3 + row].floatValue() < 0.8f) return false;
        }
        return true;
    }

    private void normalize(float[] in) {
        float avr = in[0] + in[1] + in[2];
        in[0] /= avr;
        in[1] /= avr;
        in[2] /= avr;
        avr = in[3] + in[4] + in[5];
        in[3] /= avr;
        in[4] /= avr;
        in[5] /= avr;
        avr = in[6] + in[7] + in[8];
        in[6] /= avr;
        in[7] /= avr;
        in[8] /= avr;
    }

    private static void PrintMat(float[] mat) {
        StringBuilder outp = new StringBuilder();
        for (int i = 0; i < mat.length; i++) {
            outp.append(mat[i]).append(" ");
            if (i % 3 == 2) outp.append("\n");
        }
        Log.d(TAG, "matrix:\n" + outp);
    }

    /** P162: the hot pixels inside {@code area}, in its own coordinates. */
    public static Point[] keptHotPixels(Point[] hot, com.particlesdevs.photoncamera.processing.RawCrop.Area area) {
        java.util.ArrayList<Point> kept = new java.util.ArrayList<>();
        for (Point p : hot) {
            if (p == null) continue;
            final int x = p.x - area.x, y = p.y - area.y;
            if (x >= 0 && y >= 0 && x < area.width && y < area.height) kept.add(new Point(x, y));
        }
        return kept.toArray(new Point[0]);
    }

    protected Parameters Build() {
        Parameters params = new Parameters();
        params.multiFrameCount = multiFrameCount;
        params.cfaPattern = cfaPattern;
        params.baseCfaPattern = baseCfaPattern;
        params.usedDynamic = usedDynamic;
        params.blackLevel = blackLevel.clone();
        params.whitePoint = whitePoint.clone();
        params.sceneCct = sceneCct;
        params.whiteLevel = whiteLevel;
        params.levelBits = levelBits;
        params.levelSource = levelSource;
        params.finishSnr = finishSnr;
        params.realWL = realWL;
        params.hasGainMap = hasGainMap;
        params.mapSize = new Point(mapSize);
        params.sensorPix = new Rect(sensorPix);
        params.rawBinning = rawBinning;
        params.rawCrop = rawCrop;
        params.rawCropFull = rawCropFull == null ? null : new Point(rawCropFull);
        params.gainMap = gainMap.clone();
        params.proPhotoToSRGB = proPhotoToSRGB.clone();
        params.sensorToProPhoto = sensorToProPhoto.clone();
        params.hotPixels = hotPixels.clone();
        params.focalLength = focalLength;
        params.cameraRotation = cameraRotation;
        params.CCT = CCT;
        return params;
    }

    @NonNull
    @Override
    public String toString() {
        return "parameters:\n" +
                "\n hasGainMap=" + hasGainMap +
                "\n FrameCount=" + (multiFrameCount>0 ? multiFrameCount : FrameNumberSelector.frameCount) +
                "\n CameraID=" + cameraID +
                "\n Noise Merging=" + FltFormat(ScameraApp.getSettings().mergeStrength) +
                "\n Align=" + ScameraApp.getSettings().alignAlgorithm +
                "\n Color=" + ScameraApp.getSettings().colorMethod +
                "\n FocalL=" + FltFormat(focalLength) +
                "\n Version=" + ScameraApp.getVersion();
    }

    @SuppressLint("DefaultLocale")
    private String FltFormat(Object in) {
        return String.format("%.2f", Float.parseFloat(in.toString()));
    }
}

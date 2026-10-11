package com.particlesdevs.photoncamera.circularbarlib.camera;

import android.hardware.camera2.CameraCharacteristics;
import android.util.Log;
import android.util.Range;

/**
 * Created by vibhorSrv
 */
public class CameraProperties {
    private static final String TAG = CameraProperties.class.getSimpleName();
    private final Float maxFocal;
    private final Float minFocal;
    public Range<Float> focusRange;
    public Range<Integer> isoRange;
    public Range<Long> expRange;
    public Range<Float> evRange;

    public CameraProperties(CameraCharacteristics cameraCharacteristics) {
        this.minFocal = cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        //this.maxFocal = cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE);
        this.maxFocal = 0.0f;
        this.focusRange = (!(minFocal == null || maxFocal == null || minFocal == 0.0f)) ? new Range<>(Math.min(minFocal, maxFocal), Math.max(minFocal, maxFocal)) : null;
        float evStep = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP).floatValue();
        this.isoRange = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (this.isoRange == null) this.isoRange = new Range<>(100, 3200);
        this.expRange = new Range<>(IsoExpoSelector.getEXPLOW(cameraCharacteristics), IsoExpoSelector.getEXPHIGH(cameraCharacteristics));
        RangeProvider p = rangeProvider;
        if (p != null) {
            try {
                Range<Integer> iso = p.iso(cameraCharacteristics);
                Range<Long> exp = p.exposure(cameraCharacteristics);
                if (iso != null) this.isoRange = iso;
                if (exp != null) this.expRange = exp;
            } catch (RuntimeException e) {
                Log.w(TAG, "range provider failed: " + e);
            }
        }
        this.evRange = new Range<>((cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE).getLower() * evStep),
                (cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE).getUpper() * evStep));
        logIt();
    }

    /**
     * P87: the app's ISO / shutter range of a module (vendor pro-mode keys, the frame-duration ceiling, the AE's own extremes)
     * instead of the declared SENSOR_INFO ranges, which some HALs publish narrowed (vivo X200 Ultra: ISO 1144..3200, 0.33 s).
     */
    public interface RangeProvider {
        Range<Integer> iso(CameraCharacteristics characteristics);
        Range<Long> exposure(CameraCharacteristics characteristics);
    }

    public static volatile RangeProvider rangeProvider;

    private void logIt() {
        Log.d(TAG, "focusRange : " + (focusRange == null ? "Fixed [" + maxFocal + "]" : focusRange.toString()));
        Log.d(TAG, "isoRange : " + isoRange.toString());
        Log.d(TAG, "expRange : " + expRange.toString());
        Log.d(TAG, "evCompRange : " + evRange.toString());
    }

}
package com.particlesdevs.photoncamera.manual;
/*
 * Class responsible for setting manual mode parameters to the camera preview.
 *
 *         Copyright (C) 2021  Vibhor Srivastava
 *         This program is free software: you can redistribute it and/or modify
 *         it under the terms of the GNU General Public License as published by
 *         the Free Software Foundation, either version 3 of the License, or
 *         (at your option) any later version.
 *
 *         This program is distributed in the hope that it will be useful,
 *         but WITHOUT ANY WARRANTY; without even the implied warranty of
 *         MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *         GNU General Public License for more details.
 *
 *         You should have received a copy of the GNU General Public License
 *         along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import androidx.annotation.NonNull;

import com.particlesdevs.photoncamera.circularbarlib.control.ManualParamModel;
import com.particlesdevs.photoncamera.circularbarlib.ui.ManualPanelState;
import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.processing.parameters.ExposureIndex;

import java.util.Observable;
import java.util.Observer;

/**
 * Observer class for {@link ManualParamModel}
 * This class is responsible for setting manual parameters to the camera preview
 * <p>
 * Created by Vibhor Srivastava on 07/Jan/2021
 */
public class ParamController implements Observer {
    public int ISO = -1;
    public int EV = 0;
    public long SHUTTER = -1;
    public float FOCUS = -1;
    private int whiteBalanceKelvin;
    private android.hardware.camera2.params.ColorSpaceTransform manualColorTransform;
    public void applyWhiteBalance(CaptureRequest.Builder builder) {
        if(builder==null)return;
        if(whiteBalanceKelvin==0) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE,CaptureRequest.CONTROL_AWB_MODE_AUTO);
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE,CaptureRequest.COLOR_CORRECTION_MODE_FAST);
            return;
        }
        if(manualColorTransform==null)manualColorTransform=captureController.mColorSpaceTransform;
        if(manualColorTransform==null)return; // Wait for the first valid preview result.
        try {
            android.hardware.camera2.params.RggbChannelVector gains =
                com.particlesdevs.photoncamera.circularbarlib.camera.ManualWhiteBalance.gains(CaptureController.mCameraCharacteristics,whiteBalanceKelvin);
            builder.set(CaptureRequest.CONTROL_AWB_LOCK,false);
            builder.set(CaptureRequest.CONTROL_AWB_MODE,CaptureRequest.CONTROL_AWB_MODE_OFF);
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE,CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS,gains);
            builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM,manualColorTransform);
        } catch(IllegalArgumentException e) {
            Log.e(TAG,"Manual WB unavailable: " + e.getMessage());
        }
    }
    public void setWhiteBalance(int kelvin) {
        if(whiteBalanceKelvin==0)manualColorTransform=captureController.mColorSpaceTransform;
        whiteBalanceKelvin=kelvin;
        applyWhiteBalance(captureController.mPreviewRequestBuilder);
        captureController.rebuildPreviewBuilder();
    }

    private static final String TAG = "ParamController";
    private final CaptureController captureController;
    private ManualParamModel manualParamModel;

    public ParamController(@NonNull CaptureController captureController) {
        this.captureController = captureController;
    }

    /**
     * P82: ISO and shutter of the viewfinder request. Both auto: the camera's AE. Both by hand: AE off at those values.
     * One by hand: shutter / ISO priority ({@link ExposurePriority}): the camera's own priority AE where the module lists it,
     * else AE off with the other parameter following the viewfinder's brightness ({@link #onPreviewResult}). Before, the
     * other parameter stayed frozen at the last metered value.
     */
    private synchronized void applyExposure() {
        CaptureRequest.Builder builder = captureController.mPreviewRequestBuilder;
        if (builder == null) {
            Log.w(TAG, "applyExposure(): mPreviewRequestBuilder is null");
            return;
        }
        final boolean isoManual = manualIso(), shutterManual = manualShutter();
        final int mode = ExposurePriority.mode(isoManual, shutterManual);
        final int iso = isoManual ? (int) manualParamModel.getCurrentISOValue() : 0;
        final long shutter = shutterManual ? previewShutter((long) manualParamModel.getCurrentExposureValue()) : 0;
        if (mode == ExposurePriority.NONE) {
            stopPriority(builder);
            if (!isoManual) {
                captureController.resetPreviewAEMode();
            } else {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter);
            }
        } else if (ExposurePriority.halSupports(availablePriorityModes(), mode) && !halRefused[mode]) {
            if (!halPriority || priority != mode) Log.i(TAG, "priority " + modeName(mode) + ": the camera's priority AE");
            loop = null;
            SceneLuma.setWanted(false);
            if (qcomPriority) setQcomPriorityOff(builder);
            qcomPriority = false;
            halPriority = true;
            halMisses = 0;
            captureController.resetPreviewAEMode();
            builder.set(CaptureRequest.CONTROL_AE_PRIORITY_MODE, ExposurePriority.halMode(mode));
            if (mode == ExposurePriority.ISO) builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            else builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter);
        } else if (qcomPriorityAvailable() && !qcomRefused[mode]) {
            // P87: the Qualcomm vendor priority (as MotionCam's "vendor priority fallback"): AE on, select_priority 0 = ISO
            // (use_iso_exp_priority and use_iso_value = the ISO), 1 = exposure time (use_iso_exp_priority = ns, use_iso_value 0)
            if (!qcomPriority || priority != mode) Log.i(TAG, "priority " + modeName(mode) + ": the Qualcomm vendor priority");
            loop = null;
            SceneLuma.setWanted(false);
            if (halPriority) setHalPriorityOff(builder);
            halPriority = false;
            qcomPriority = true;
            halMisses = 0;
            captureController.resetPreviewAEMode();
            final boolean isoMode = mode == ExposurePriority.ISO;
            builder.set(QCOM_SELECT_PRIORITY, isoMode ? 0 : 1);
            builder.set(QCOM_ISO_EXP_PRIORITY, isoMode ? (long) iso : shutter);
            builder.set(QCOM_USE_ISO_VALUE, isoMode ? iso : 0);
            if (isoMode) builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            else builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter);
        } else {
            if (qcomPriority) setQcomPriorityOff(builder);
            qcomPriority = false;
            if (halPriority) setHalPriorityOff(builder);
            halPriority = false;
            if (loop == null || loop.mode != mode) {
                // from the camera's AE (its last values) or from the other priority (its learned brightness)
                loop = loop == null ? new ExposurePriority.Loop(mode, Math.max(1, captureController.mPreviewIso),
                        Math.max(1, captureController.mPreviewExposureTime)) : new ExposurePriority.Loop(mode, loop);
                Log.i(TAG, "priority " + modeName(mode) + ": viewfinder loop from ISO " + loop.iso() + " " + loop.shutterNs() + " ns");
            }
            loop.setFixed(iso, shutter, isoMin(), isoMax(), shutterMin(), shutterMax());
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, loop.iso());
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, loop.shutterNs());
            appliedAtNs = System.nanoTime();
            SceneLuma.setWanted(true);
        }
        priority = mode;
        captureController.rebuildPreviewBuilder();
    }

    /** P82: one viewfinder result (camera thread): checks the camera's priority AE, or moves the software loop. */
    public synchronized void onPreviewResult(CaptureResult result) {
        if (priority == ExposurePriority.NONE || result == null) return;
        CaptureRequest.Builder builder = captureController.mPreviewRequestBuilder;
        if (builder == null) return;
        if (halPriority || qcomPriority) {
            // A HAL that lists the priority but ignores it: after 15 frames off the hand-set value, the next way.
            final boolean iso = priority == ExposurePriority.ISO;
            Number want = iso ? builder.get(CaptureRequest.SENSOR_SENSITIVITY) : builder.get(CaptureRequest.SENSOR_EXPOSURE_TIME);
            Number got = iso ? result.get(CaptureResult.SENSOR_SENSITIVITY) : result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            if (want == null || got == null) return;
            if (Math.abs(got.doubleValue() / Math.max(1, want.doubleValue()) - 1) <= 0.03) {
                halMisses = 0;
            } else if (++halMisses >= 15) {
                Log.w(TAG, (qcomPriority ? "the Qualcomm " : "the camera's ") + modeName(priority) + " priority gives " + got
                        + " for " + want + ": the next way");
                if (qcomPriority) qcomRefused[priority] = true;
                else halRefused[priority] = true;
                applyExposure();
            }
            return;
        }
        if (loop == null) return;
        final double luma = SceneLuma.mean();
        final long at = SceneLuma.atNs();
        // a measurement of a frame with the values in force: 0.2 s after the request (pipeline depth), each one once
        if (Double.isNaN(luma) || at == lumaUsedNs || at - appliedAtNs < 200_000_000L) return;
        lumaUsedNs = at;
        if (loop.measured(luma, evStops(), isoMin(), isoMax(), shutterMin(), shutterMax())) {
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, loop.iso());
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, loop.shutterNs());
            appliedAtNs = System.nanoTime();
            captureController.rebuildPreviewBuilder();
        }
    }

    private void stopPriority(CaptureRequest.Builder builder) {
        if (halPriority) setHalPriorityOff(builder);
        if (qcomPriority) setQcomPriorityOff(builder);
        halPriority = false;
        qcomPriority = false;
        loop = null;
        SceneLuma.setWanted(false);
    }

    private static void setHalPriorityOff(CaptureRequest.Builder builder) {
        if (availablePriorityModes() != null) builder.set(CaptureRequest.CONTROL_AE_PRIORITY_MODE, 0);
    }

    // P87: Qualcomm CamX vendor priority tags (org.codeaurora.qcamera3.iso_exp_priority, the X200 Ultra lists them)
    private static final CaptureRequest.Key<Integer> QCOM_SELECT_PRIORITY =
            new CaptureRequest.Key<>("org.codeaurora.qcamera3.iso_exp_priority.select_priority", Integer.class);
    private static final CaptureRequest.Key<Long> QCOM_ISO_EXP_PRIORITY =
            new CaptureRequest.Key<>("org.codeaurora.qcamera3.iso_exp_priority.use_iso_exp_priority", Long.class);
    private static final CaptureRequest.Key<Integer> QCOM_USE_ISO_VALUE =
            new CaptureRequest.Key<>("org.codeaurora.qcamera3.iso_exp_priority.use_iso_value", Integer.class);

    /** Whether the module publishes the Qualcomm ISO / exposure priority (its iso_available_modes). */
    private static boolean qcomPriorityAvailable() {
        CameraCharacteristics c = CaptureController.mCameraCharacteristics;
        if (c == null) return false;
        try {
            for (CameraCharacteristics.Key<?> k : c.getKeys())
                if ("org.codeaurora.qcamera3.iso_exp_priority.iso_available_modes".equals(k.getName())) return true;
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    private static void setQcomPriorityOff(CaptureRequest.Builder builder) {
        try {
            builder.set(QCOM_SELECT_PRIORITY, 0);
            builder.set(QCOM_ISO_EXP_PRIORITY, 0L);
            builder.set(QCOM_USE_ISO_VALUE, 0);
        } catch (RuntimeException ignored) {
        }
    }

    private static String modeName(int mode) {
        return mode == ExposurePriority.SHUTTER ? "shutter" : "ISO";
    }

    /** The module's CONTROL_AE_AVAILABLE_PRIORITY_MODES (Android 16), null without. */
    private static int[] availablePriorityModes() {
        if (android.os.Build.VERSION.SDK_INT < 36 || CaptureController.mCameraCharacteristics == null) return null;
        try {
            return CaptureController.mCameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_PRIORITY_MODES);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean manualIso() {
        return manualParamModel != null && manualParamModel.getCurrentISOValue() != ManualParamModel.ISO_AUTO;
    }

    private boolean manualShutter() {
        return manualParamModel != null && manualParamModel.getCurrentExposureValue() != ManualParamModel.EXPOSURE_AUTO;
    }

    /**
     * The viewfinder runs at most 1/10 s, 10 fps (owner, 2026-10-11; was 1/5 s): a longer hand-set shutter is the photo's only.
     */
    static final long PREVIEW_SHUTTER_MAX_NS = ExposureIndex.sec / 10;

    private static long previewShutter(long shutterNs) {
        return Math.min(shutterNs, PREVIEW_SHUTTER_MAX_NS);
    }

    private double evStops() {
        CameraCharacteristics c = CaptureController.mCameraCharacteristics;
        android.util.Rational step = c == null ? null : c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        return EV * (step == null ? 1.0 / 3 : step.doubleValue());
    }

    private int isoMin() {
        android.util.Range<Integer> r = isoRange();
        return r == null ? 50 : r.getLower();
    }

    private int isoMax() {
        android.util.Range<Integer> r = isoRange();
        return r == null ? 3200 : r.getUpper();
    }

    private android.util.Range<Integer> isoRange() {
        // P87: the rulers' range (vendor pro range, the AE's own extremes), not the narrowed declared one
        return CaptureController.mCameraCharacteristics == null ? null
                : com.particlesdevs.photoncamera.capture.ExposureRanges.iso(CaptureController.mCameraCharacteristics, captureController.physicalId());
    }

    private static long shutterMin() {
        android.util.Range<Long> r = shutterRange();
        return r == null ? 100_000L : r.getLower();
    }

    private static long shutterMax() {
        android.util.Range<Long> r = shutterRange();
        return previewShutter(r == null ? PREVIEW_SHUTTER_MAX_NS : r.getUpper());
    }

    private static android.util.Range<Long> shutterRange() {
        return CaptureController.mCameraCharacteristics == null ? null
                : com.particlesdevs.photoncamera.capture.ExposureRanges.shutter(CaptureController.mCameraCharacteristics);
    }

    /**
     * P84: a hand-set shutter longer than this is exposed after the shutter (PSL), not taken from the ZSL ring: the owner's
     * 1/8 s, lowered to the viewfinder's 1/10 s ceiling (the ring holds viewfinder frames, which a longer shutter never reaches).
     */
    public static final long PSL_SHUTTER_NS = Math.min(ExposureIndex.sec / 8, PREVIEW_SHUTTER_MAX_NS);

    /**
     * P84 (owner, 2026-10-11: «при выдержке длиннее 1/8 использовался PSL, а не ZSL»): the hand-set shutter of the photo when
     * it is longer than PSL_SHUTTER_NS, else 0. The viewfinder (and so the ZSL ring) runs at most 1/10 s, so these frames are exposed after
     * the shutter at the hand-set value.
     */
    public synchronized long pslShutterNs() {
        if (!manualShutter()) return 0;
        final long ns = (long) manualParamModel.getCurrentExposureValue();
        return ns > PSL_SHUTTER_NS ? ns : 0;
    }

    /**
     * P84: the ISO of a PSL photo at {@code shutterNs}: the hand-set one; in shutter priority the viewfinder's exposure (ISO ×
     * its shutter, at most 1/10 s) moved onto the longer shutter, inside the module's ISO range.
     */
    public synchronized int pslIso(long shutterNs) {
        if (manualIso()) return (int) manualParamModel.getCurrentISOValue();
        return pslIso(captureController.mPreviewIso, captureController.mPreviewExposureTime, shutterNs, isoMin(), isoMax());
    }

    static int pslIso(int previewIso, long previewNs, long shutterNs, int isoMin, int isoMax) {
        if (previewIso <= 0 || previewNs <= 0 || shutterNs <= 0) return isoMin;
        return (int) Math.max(isoMin, Math.min(isoMax, Math.round((double) previewIso * previewNs / shutterNs)));
    }

    // P82 priority state
    private int priority = ExposurePriority.NONE;
    private boolean halPriority, qcomPriority;
    private final boolean[] halRefused = new boolean[3], qcomRefused = new boolean[3];
    private int halMisses;
    private ExposurePriority.Loop loop;
    private long appliedAtNs, lumaUsedNs;

    public void setFocus(float focusDist) {
        CaptureRequest.Builder builder = captureController.mPreviewRequestBuilder;
        if (builder == null) {
            Log.w(TAG, "setFocus(): mPreviewRequestBuilder is null");
            return;
        }
        if (focusDist == ManualParamModel.FOCUS_AUTO) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, PreferenceKeys.getAfMode());
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDist);
        }
        captureController.rebuildPreviewBuilder();
    }

    public void setEV(int ev) {
        CaptureRequest.Builder builder = captureController.mPreviewRequestBuilder;
        if (builder == null) {
            Log.w(TAG, "setEV(): mPreviewRequestBuilder is null");
            return;
        }
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev);
        captureController.rebuildPreviewBuilder();
    }

    /**
     * The EV index the request gets: the stored {@link #EV}, or 0 while ISO and shutter are both manual (the owner's rule:
     * exposure compensation is unavailable then). The stored value is kept and applies again when either returns to auto.
     */
    public int effectiveEv() {
        if (manualParamModel == null) return EV;
        return ManualPanelState.effectiveEvIndex(EV, manualParamModel.getCurrentISOValue() != ManualParamModel.ISO_AUTO,
                manualParamModel.getCurrentExposureValue() != ManualParamModel.EXPOSURE_AUTO);
    }

    /** ISO or shutter changed: the EV lock may have changed, so the builder gets the effective EV before the rebuild. */
    private void syncEvLock() {
        CaptureRequest.Builder builder = captureController.mPreviewRequestBuilder;
        if (builder != null && EV != 0) builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, effectiveEv());
    }

    public boolean isManualMode() {
        if (manualParamModel != null)
            return manualParamModel.isManualMode();
        return false;
    }

    @Override
    public void update(Observable observable, Object object) {
        if (observable != null && object != null) {
            ManualParamModel model = (ManualParamModel) observable;
            manualParamModel = model;
            if (object.equals(ManualParamModel.ID_WB)) setWhiteBalance(model.getWhiteBalanceKelvin());
            if (object.equals(ManualParamModel.ID_ISO)) {
                ISO = (int) model.getCurrentISOValue();
                syncEvLock();
                applyExposure();
            }
            if (object.equals(ManualParamModel.ID_EV)) {
                EV = (int) model.getCurrentEvValue();
                setEV(effectiveEv());
            }
            if (object.equals(ManualParamModel.ID_SHUTTER)) {
                SHUTTER = (long) model.getCurrentExposureValue();
                syncEvLock();
                applyExposure();
            }
            if (object.equals(ManualParamModel.ID_FOCUS)) {
                FOCUS = (float) model.getCurrentFocusValue();
                setFocus((float) model.getCurrentFocusValue());
            }
            if(object.equals(ManualParamModel.PANEL_INVISIBILITY)) {
                Log.d(TAG, "update: " + model.isManualMode());
                ISO = -1;
                EV = 0;
                SHUTTER = -1;
                FOCUS = -1;
                synchronized (this) {
                    priority = ExposurePriority.NONE;
                    CaptureRequest.Builder b = captureController.mPreviewRequestBuilder;
                    if (b != null) stopPriority(b); // the priority tags back to off (AE is reset by the console)
                    halPriority = qcomPriority = false;
                    loop = null;
                    SceneLuma.setWanted(false);
                }
                whiteBalanceKelvin=0;
                applyWhiteBalance(captureController.mPreviewRequestBuilder);
                captureController.unlockFocus();
            }
        }
    }

    public void setupPreview() {
        applyWhiteBalance(captureController.mPreviewRequestBuilder);
        if (manualParamModel != null) {
            if (manualIso() || manualShutter())
                applyExposure();
            if(EV != 0)
                setEV(effectiveEv());
            if(FOCUS != -1)
                setFocus(FOCUS);
        }
    }

    public double getCurrentExposureValue() {
        if (manualParamModel != null)
            return manualParamModel.getCurrentExposureValue();
        return ManualParamModel.EXPOSURE_AUTO;
    }

    public double getCurrentISOValue() {
        if (manualParamModel != null)
            return manualParamModel.getCurrentISOValue();
        return ManualParamModel.ISO_AUTO;
    }
}

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
        // P377: ETTR's shutter (the automatic exposure only): shutter priority at it, the AE's compensation keeps the ISO
        final boolean ettr = !isoManual && !shutterManual && ettrShutterNs > 0;
        final int mode = ExposurePriority.mode(isoManual, shutterManual || ettr);
        final int iso = isoManual ? (int) manualParamModel.getCurrentISOValue() : 0;
        final long shutter = shutterManual ? previewShutter((long) manualParamModel.getCurrentExposureValue()) : ettr ? ettrShutterNs : 0;
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
            if (!halPriority || priority != mode) Log.i(TAG, "priority " + modeName(mode) + ": the camera's priority AE" + (ettr ? " (ETTR)" : ""));
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
            if (!qcomPriority || priority != mode) Log.i(TAG, "priority " + modeName(mode) + ": the Qualcomm vendor priority" + (ettr ? " (ETTR)" : ""));
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
                loopFresh = loop == null;
                loop = loop == null ? new ExposurePriority.Loop(mode, Math.max(1, captureController.mPreviewIso),
                        Math.max(1, captureController.mPreviewExposureTime)) : new ExposurePriority.Loop(mode, loop);
                Log.i(TAG, "priority " + modeName(mode) + ": viewfinder loop from ISO " + loop.iso() + " " + loop.shutterNs() + " ns" + (ettr ? " (ETTR)" : ""));
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
        if (result == null) return;
        com.particlesdevs.photoncamera.capture.MainsFlicker.observe(result); // P118 «Частота мерцания» Авто
        learnNeutral(result); // P121
        motionStep(result);
        if (priority == ExposurePriority.NONE) return;
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
        final int source = SceneLuma.source();
        final double luma = SceneLuma.mean();
        final long at = SceneLuma.atNs();
        // a measurement of a frame with the values in force: 0.2 s after the request (pipeline depth), each one once
        if (Double.isNaN(luma) || at == lumaUsedNs || at - appliedAtNs < 200_000_000L) return;
        lumaUsedNs = at;
        // P212h (A6 = M11): the RAW viewfinder's median and the ISP texture's mean are on other scales: a change of meter learns the
        // target again at the exposure in force (the RAW viewfinder of FULLRES publishes now, the ISP one when it takes over)
        if (source != lumaSource) {
            if (lumaSource >= 0) {
                loop.relearn();
                Log.i(TAG, "priority " + modeName(priority) + ": brightness from the " + (source == SceneLuma.RAW ? "RAW" : "ISP")
                        + " viewfinder now, target learned again");
            }
            lumaSource = source;
        }
        // P377: plus the ETTR boost - after a new loop's first measurement, which learns the target at the exposure in force (the
        // AE's, never boosted yet: the loop's shutter took the boost at the same exposure, an ISO priority's EV boost comes from here);
        // a target learned again (another meter, P212h) is learned at the boosted exposure, with the boost (review: twice else)
        final double ev;
        final boolean ettrLoop = ettrShutterNs > 0 && !manualIso() && !manualShutter() && ettrAeProduct > 0;
        if (Double.isNaN(loop.targetLuma()) && ettrLoop) {
            // learned at the exposure in force against the AE's own (review: the loop's start could not always keep it - the
            // lowest ISO, frames already brightened): the boost the target already holds is counted, the rest comes from ettrEv
            final double inForce = loop.iso() * (loop.shutterNs() / 1e9);
            ev = evStops() + Math.log(inForce / ettrAeProduct) / Math.log(2);
        } else ev = evStops() + (Double.isNaN(loop.targetLuma()) && loopFresh ? 0 : CaptureController.ettrEv(captureController));
        loopFresh = false;
        if (loop.measured(luma, ev, isoMin(), isoMax(), shutterMin(), shutterMax())) {
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, loop.iso());
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, loop.shutterNs());
            appliedAtNs = System.nanoTime();
            captureController.rebuildPreviewBuilder();
        }
    }

    // P110 «Движение» shutter ceiling ({@link MotionShutter}): its own AE-off request, apart from the manual priority
    private final MotionShutter.Controller motion = new MotionShutter.Controller();
    private final MotionShutter.Limits motionLimits = new MotionShutter.Limits();
    private final MotionShutter.Levels motionLevels = new MotionShutter.Levels();
    /** P111: the ceiling of the last viewfinder result (the setting lowered by the motion level). */
    private long motionCapNs;
    private boolean motionNoLevelLogged, motionUnsafeLogged;
    private int motionUnlearned;
    private volatile boolean motionTap, motionActive;

    /** The ceiling holds the viewfinder request (AE off for the camera AE, not a hand-set exposure). */
    public boolean motionCeilingActive() {
        return motionActive;
    }

    /** P377: ETTR's shutter of the automatic exposure (0: none): shutter priority at it (CaptureController's ETTR meter). */
    private volatile long ettrShutterNs;

    public long ettrShutterNs() {
        return ettrShutterNs;
    }

    /**
     * P377 (review): the viewfinder loop holds ETTR's shutter (AE off) but the request has AE on again - a tap's metering, the 3A
     * reset after a shot, unlockFocus: the compensation alone would act, as gain (the bug). The ETTR meter starts over then.
     */
    public synchronized boolean ettrLoopOverridden() {
        if (ettrShutterNs <= 0 || loop == null || halPriority || qcomPriority || manualIso() || manualShutter()) return false;
        final CaptureRequest.Builder b = captureController.mPreviewRequestBuilder;
        final Integer ae = b == null ? null : b.get(CaptureRequest.CONTROL_AE_MODE);
        return ae != null && ae != CaptureRequest.CONTROL_AE_MODE_OFF;
    }

    /**
     * P377: ETTR lengthens the automatic exposure's shutter to {@code ns} (0: the AE alone again): shutter priority at it, as a
     * hand-set shutter (the camera's priority AE, the Qualcomm vendor priority, else the viewfinder loop), the compensation the
     * boost so the AE keeps its ISO. Nothing while ISO or shutter is set by hand: the panel owns the exposure then.
     */
    public synchronized void setEttrShutter(long ns) {
        setEttrShutter(ns, 0);
    }

    /**
     * {@code aeProduct}: the AE's own exposure the boost is measured from (ISO x s; 0 unknown) - the viewfinder loop learns its target
     * against it (review: a loop started at the AE's lowest ISO, which could not drop by the boost, or from frames already brightened
     * by the compensation, learned the boosted brightness as the AE's and added the boost again: clipped, then pumping).
     */
    public synchronized void setEttrShutter(long ns, double aeProduct) {
        final long next = Math.max(0, ns);
        ettrAeProduct = next > 0 ? Math.max(0, aeProduct) : 0;
        if (next == ettrShutterNs) return;
        ettrShutterNs = next;
        if (!manualIso() && !manualShutter()) applyExposure();
    }

    /** P377: the AE's exposure behind the ETTR shutter in force (ISO x s, 0: unknown or none). */
    private volatile double ettrAeProduct;

    /**
     * P377: a new session or the camera closing: no ETTR shutter, and its priority state gone without a request (the session
     * builds its own; the panel's own priority stays).
     */
    public synchronized void clearEttr() {
        if (ettrShutterNs == 0) return;
        ettrShutterNs = 0;
        ettrAeProduct = 0;
        if (manualIso() || manualShutter()) return;
        final CaptureRequest.Builder b = captureController.mPreviewRequestBuilder;
        if (b != null) {
            stopPriority(b);
            captureController.resetPreviewAEMode();
        } else {
            halPriority = qcomPriority = false;
            loop = null;
            SceneLuma.setWanted(false);
        }
        priority = ExposurePriority.NONE;
    }

    /**
     * The «Движение» shutter ceiling in force (the setting, at least the latched mains half period), 0 when the mode is off or
     * the exposure is set by hand: the frames after the shutter keep within it (HybridPlan.buildNormalBack).
     */
    public synchronized long motionCeilingNs() {
        if (!com.particlesdevs.photoncamera.settings.MotionMode.on() || manualIso() || manualShutter()) return 0;
        final long setting = MotionShutter.settingNs(PreferenceKeys.hybridValue("mm_shutter", 250f));
        return motion.capNs(motionCapNs > 0 && setting > 0 ? Math.min(setting, motionCapNs) : setting);
    }

    /** A tap meters a new point (TouchFocus): the AE meters it, the ceiling learns that and takes over again. */
    /**
     * P121: the viewfinder neutrals of a plain-Bayer stream (the AWB settled) are points of the camera's illuminant curve, which
     * the white balance of its ISZ streams without remosaic is put on (MosaicNeutral). Every 16th result.
     */
    private int neutralSkip;
    private void learnNeutral(CaptureResult result) {
        if ((++neutralSkip & 15) != 0 || com.particlesdevs.photoncamera.processing.MosaicStream.block() != 1
                || com.particlesdevs.photoncamera.capture.OppoNativeFullRes.running()) return; // P192: the own neutral, not the ISP's
        try {
            final Integer awb = result.get(CaptureResult.CONTROL_AWB_MODE), state = result.get(CaptureResult.CONTROL_AWB_STATE);
            if (awb == null || awb != CaptureResult.CONTROL_AWB_MODE_AUTO) return;
            if (state != null && state != CaptureResult.CONTROL_AWB_STATE_CONVERGED && state != CaptureResult.CONTROL_AWB_STATE_LOCKED) return;
            final android.util.Rational[] n = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
            if (n == null || n.length < 3 || !(n[1].floatValue() > 0f)) return;
            String id = captureController.physicalId();
            if (id == null) return;
            id = id.substring(id.lastIndexOf('/') + 1);
            com.particlesdevs.photoncamera.processing.color.MosaicNeutral.init(com.particlesdevs.photoncamera.app.ScameraApp.getAppContext());
            com.particlesdevs.photoncamera.processing.color.MosaicNeutral.learn(id,
                    n[0].floatValue() / n[1].floatValue(), n[2].floatValue() / n[1].floatValue());
        } catch (RuntimeException ignored) {
        }
    }

    public void motionMeteringTap() {
        motionTap = true;
    }

    /** The longest shutter of a ring frame: one frame of the ring (its AE target range), at most 1/10 s. */
    private long motionMaxNs() {
        CaptureRequest.Builder b = captureController.mPreviewRequestBuilder;
        android.util.Range<Integer> fps = b == null ? null : b.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE);
        long frameNs = fps != null && fps.getUpper() > 0 ? ExposureIndex.sec / fps.getUpper() : PREVIEW_SHUTTER_MAX_NS;
        return Math.min(frameNs, PREVIEW_SHUTTER_MAX_NS);
    }

    /** The ceiling's request: AE off at its shutter and ISO, the frame no longer than one ring frame. */
    private void motionApply(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, motion.shutterNs());
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, motion.iso());
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, Math.max(motion.shutterNs(), motionMaxNs()));
        setPostRawBoost(builder, motion.underRatio());
        captureController.rebuildPreviewBuilder();
    }

    /**
     * P124: the viewfinder's brightness while the ceiling stays under the scene's exposure (the moving scene keeps its short
     * shutter, the top ISO the HAL gives is short of it): the camera's post-RAW gain, which the ring's RAW frames never see
     * (the photo is brightened by the tone stage); 100 (none) otherwise.
     */
    private static void setPostRawBoost(CaptureRequest.Builder builder, double ratio) {
        final CameraCharacteristics c = CaptureController.mCameraCharacteristics;
        if (builder == null || c == null) return;
        try {
            final android.util.Range<Integer> range = c.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE);
            if (range == null) return;
            final long boost = Math.round(100 * Math.max(1, ratio));
            builder.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST,
                    (int) Math.max(range.getLower(), Math.min(range.getUpper(), boost)));
        } catch (RuntimeException ignored) {
        }
    }

    /** The ceiling lets go; {@code resetAe}: the camera AE in the viewfinder request again (not when the panel owns it). */
    private void motionRelease(String why, boolean resetAe) {
        motion.drop();
        motionActive = false;
        setPostRawBoost(captureController.mPreviewRequestBuilder, 1);
        Log.i(TAG, "motion shutter ceiling off: " + why);
        if (resetAe) {
            captureController.resetPreviewAEMode();
            captureController.rebuildPreviewBuilder();
        }
    }

    /**
     * P110: one viewfinder result for the «Движение» ceiling. Nothing while ISO or shutter is set by hand (the manual panel
     * then owns the exposure), during a shot (the series owns the requests), or without a RAW level (no ceiling then: the
     * camera AE as before).
     */
    private void motionStep(CaptureResult result) {
        final CaptureRequest.Builder builder = captureController.mPreviewRequestBuilder;
        if (builder == null) return;
        final boolean on = com.particlesdevs.photoncamera.settings.MotionMode.on();
        final long setting = on ? MotionShutter.settingNs(PreferenceKeys.hybridValue("mm_shutter", 250f)) : 0;
        final boolean manual = manualIso() || manualShutter();
        if (setting <= 0 || manual) {
            if (motion.active()) motionRelease(manual ? "exposure set by hand" : "mode or setting off", !manual);
            motionTap = false;
            return;
        }
        if (captureController.subjectRegionsFrozen()) return; // a shot in flight: its requests, not ours
        if (captureController.previewAeOffUnsafe()) {
            if (!motionUnsafeLogged) {
                motionUnsafeLogged = true;
                Log.i(TAG, "motion shutter ceiling: not under the forced stabilisation (P54c: the viewfinder request keeps AE on)");
            }
            if (motion.active()) motionRelease("forced stabilisation", true);
            return;
        }
        final Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        final Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
        final Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
        final Integer aeMode = result.get(CaptureResult.CONTROL_AE_MODE);
        final Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
        if (exposure == null || iso == null || timestamp == null) return;
        if (motionTap) {
            // the tap wrote AE on with its region: the AE meters the point, the ceiling learns it before it takes over again
            motionTap = false;
            if (motion.active()) {
                motion.drop();
                motionActive = false;
                setPostRawBoost(builder, 1);
                Log.i(TAG, "motion shutter ceiling: tap, the AE meters it first");
            }
        } else if (motion.active()) {
            // something else wrote AE on behind the ceiling (the 3A reset after a shot, the manual console): its request again
            final Integer want = builder.get(CaptureRequest.CONTROL_AE_MODE);
            if (want == null || want != CaptureRequest.CONTROL_AE_MODE_OFF) {
                Log.i(TAG, "motion shutter: the viewfinder request had AE on again, the ceiling's request");
                motionApply(builder);
            }
        }
        final boolean flickerSafe = PreferenceKeys.hybridSwitch("mm_flicker", true);
        Integer flicker = null;
        if (flickerSafe) {
            try {
                flicker = result.get(CaptureResult.STATISTICS_SCENE_FLICKER);
            } catch (RuntimeException ignored) {
            }
        }
        final com.particlesdevs.photoncamera.capture.RawLevel.Sample sample = com.particlesdevs.photoncamera.capture.RawLevel.sample();
        final boolean aeOff = aeMode != null && aeMode == CaptureRequest.CONTROL_AE_MODE_OFF;
        // P111: the setting lowered by how fast the scene moves (the stock snapshot mode's motion levels)
        final com.particlesdevs.photoncamera.capture.MotionMeter.Sample moving = com.particlesdevs.photoncamera.capture.MotionMeter.sample();
        final int levelBefore = motionLevels.level();
        final long cap = motionLevels.capNs(setting, moving == null ? Double.NaN : moving.pxPerS(), moving == null ? 0 : moving.atNs,
                System.nanoTime());
        motionCapNs = cap;
        if (motionLevels.level() != levelBefore && moving != null)
            Log.i(TAG, String.format(java.util.Locale.ROOT, "motion level %d -> %d: subject %.0f px/s, camera %.0f px/s (%d of %d tiles moved) -> ceiling %d ns",
                    levelBefore, motionLevels.level(), moving.subjectPxPerS, moving.cameraPxPerS, moving.moved, moving.textured, cap));
        // the top ISO: the rulers' range, at most what the HAL honours for AE-off requests (P27, paired with their requests)
        motionLimits.set(cap, motionMaxNs(), isoMin(), Math.min(isoMax(), captureController.honouredIsoCap()), flickerSafe,
                evStops()).under(motionLevels.level() > 0 ? MotionShutter.MOTION_UNDER_STOPS : 0);
        final boolean wasActive = motion.active();
        final long oldShutter = motion.shutterNs(), flickerBefore = motion.flickerHalfNs();
        final com.particlesdevs.photoncamera.capture.MotionMeter.Profile rows = com.particlesdevs.photoncamera.capture.MotionMeter.profile();
        final long suspectBefore = motion.suspectHalfNs();
        final boolean bandFreeBefore = motion.bandFree();
        final int action = motion.onResult(timestamp, exposure, iso, aeOff, aeState, flicker,
                sample == null ? 0 : sample.timestampNs, sample == null ? Double.NaN : sample.level,
                rows == null ? 0 : rows.timestampNs, rows == null ? null : rows.bins, motionLimits, System.nanoTime());
        if (motion.suspectHalfNs() != suspectBefore && motion.suspectHalfNs() > 0)
            Log.i(TAG, "motion shutter: the AE's shutters suggest mains flicker (" + motion.suspectHalfNs() + " ns), the frames decide");
        if (motion.flickerHalfNs() != flickerBefore)
            Log.i(TAG, "motion shutter: mains flicker, shutters in whole " + motion.flickerHalfNs() + " ns");
        if (motion.bandFree() && !bandFreeBefore)
            Log.i(TAG, "motion shutter: no lamp bands on the short frames, short shutters stay");
        if (!wasActive && !aeOff && exposure > cap && Double.isNaN(motion.target()) && !motionNoLevelLogged
                && (sample == null || ++motionUnlearned >= 90)) {
            // nothing learned: no RAW level at all, or levels that never pair with a settled AE frame (diagnosis)
            motionNoLevelLogged = true;
            Log.i(TAG, sample == null ? "motion shutter ceiling: no RAW level on this camera yet, the camera AE as before"
                    : "motion shutter ceiling: no settled AE level after 90 frames (AE state " + aeState + ", level frame "
                    + sample.timestampNs + ", result " + timestamp + "), the camera AE as before");
        }
        if (action == MotionShutter.APPLY) {
            if (!wasActive)
                Log.i(TAG, "motion shutter ceiling on: AE " + exposure + " ns at ISO " + iso + " -> " + motion.shutterNs()
                        + " ns at ISO " + motion.iso() + " (ceiling " + motion.capNs(cap) + " ns, motion level " + motionLevels.level()
                        + String.format(java.util.Locale.ROOT, ", %.2f stops under, viewfinder gain)", Math.log(motion.underRatio()) / Math.log(2)));
            else if (motion.shutterNs() != oldShutter)
                Log.i(TAG, "motion shutter " + oldShutter + " -> " + motion.shutterNs() + " ns at ISO " + motion.iso()
                        + String.format(java.util.Locale.ROOT, " (%.2f stops under)", Math.log(motion.underRatio()) / Math.log(2)));
            motionApply(builder);
        } else if (action == MotionShutter.RELEASE) {
            Log.i(TAG, "motion shutter ceiling off: bright enough or no RAW level, the camera AE again");
            setPostRawBoost(builder, 1);
            captureController.resetPreviewAEMode();
            captureController.rebuildPreviewBuilder();
        }
        motionActive = motion.active();
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
        // P135: the automatic ISO stays within what the HAL honours for AE-off requests (P110 ExposureLimits; log 2026-10-15:
        // ISO 12800 asked for 0.8 s, 6400 delivered, the four N frames came half as bright as planned and were taken for ES)
        return pslIso(captureController.mPreviewIso, captureController.mPreviewExposureTime, shutterNs, isoMin(),
                Math.min(isoMax(), Math.max(isoMin(), captureController.honouredIsoCap())));
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
    /** P377: the loop has not measured yet since it was made (its exposure is the AE's: its target is learned without a boost). */
    private boolean loopFresh;
    /** P212h: the SceneLuma source the loop's target was learned on (-1: none yet). */
    private int lumaSource = -1;

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
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, CaptureController.ettrIndex(captureController, ev)); // P356: plus the ETTR boost
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
        if (builder != null && EV != 0) builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, CaptureController.ettrIndex(captureController, effectiveEv()));
    }

    /** P201: the hand-set ISO (0 = automatic). */
    public int manualIsoValue() {
        return manualIso() ? (int) manualParamModel.getCurrentISOValue() : 0;
    }

    /** P201: the hand-set shutter on the viewfinder (at most PREVIEW_SHUTTER_MAX_NS; 0 = automatic). */
    public long manualPreviewShutterNs() {
        return manualShutter() ? previewShutter((long) manualParamModel.getCurrentExposureValue()) : 0;
    }

    /** P201: a hand-set white balance. */
    public boolean manualWhiteBalance() {
        return whiteBalanceKelvin != 0;
    }

    /** P201: the gains of the hand-set white balance, null without one. */
    @androidx.annotation.Nullable
    public android.hardware.camera2.params.RggbChannelVector manualWhiteBalanceGains() {
        if (whiteBalanceKelvin == 0 || CaptureController.mCameraCharacteristics == null) return null;
        try {
            return com.particlesdevs.photoncamera.circularbarlib.camera.ManualWhiteBalance.gains(CaptureController.mCameraCharacteristics, whiteBalanceKelvin);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** P201: the hand-set focus distance (diopters), or -1 for the autofocus. */
    public float manualFocusDistance() {
        return FOCUS >= 0f && FOCUS != (float) ManualParamModel.FOCUS_AUTO ? FOCUS : -1f;
    }

    /**
     * P201 (owner, 2026-10-17: «убедись чтобы при работе фулреза работал полностью ручной режим»): a FULLRES session writes its own
     * exposure keys into every viewfinder request (the vivo native route AE on, the X200 Ultra's SDK route AE on at ISO 0). Put
     * the hand-set exposure back on {@code builder} after them, as {@link #applyExposure} left it: both by hand AE off at those
     * values, one by hand with the viewfinder loop AE off at the loop's values; the camera's priority AE (its own key or the
     * Qualcomm tags) is untouched by the FULLRES keys and stays. Hand-set white balance and focus as well.
     */
    public void applyManualTo(CaptureRequest.Builder builder) {
        // not synchronized: it runs inside the camera's request rebuilds (any thread), reading the last applied state
        final ManualParamModel model = manualParamModel;
        if (builder == null || model == null) return;
        final boolean isoManual = model.getCurrentISOValue() != ManualParamModel.ISO_AUTO,
                shutterManual = model.getCurrentExposureValue() != ManualParamModel.EXPOSURE_AUTO;
        final boolean ettr = !isoManual && !shutterManual && ettrShutterNs > 0; // P377
        final ExposurePriority.Loop softLoop = loop;
        if (isoManual || shutterManual || ettr) {
            if (ExposurePriority.mode(isoManual, shutterManual || ettr) == ExposurePriority.NONE) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, (int) model.getCurrentISOValue());
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, previewShutter((long) model.getCurrentExposureValue()));
            } else if (softLoop != null && !halPriority && !qcomPriority) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, softLoop.iso());
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, softLoop.shutterNs());
            }
        }
        applyManualWbFocusTo(builder);
    }

    /** P201: the hand-set white balance and focus alone (the OPPO's own exposure runs the rest). */
    public void applyManualWbFocusTo(CaptureRequest.Builder builder) {
        if (builder == null) return;
        if (whiteBalanceKelvin != 0) applyWhiteBalance(builder);
        final float focus = manualFocusDistance();
        if (focus >= 0f) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus);
        }
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
                    // the «Движение» ceiling stays: the console's 3A reset writes AE on, motionStep puts its request back
                    priority = ExposurePriority.NONE;
                    CaptureRequest.Builder b = captureController.mPreviewRequestBuilder;
                    if (b != null) stopPriority(b); // the priority tags back to off (AE is reset by the console)
                    halPriority = qcomPriority = false;
                    loop = null;
                    SceneLuma.setWanted(false);
                    ettrShutterNs = 0; // P377: the ETTR meter starts over (it sees the shutter dropped)
                    ettrAeProduct = 0;
                }
                whiteBalanceKelvin=0;
                applyWhiteBalance(captureController.mPreviewRequestBuilder);
                captureController.unlockFocus();
            }
        }
    }

    public void setupPreview() {
        synchronized (this) {
            // P110: a new session (lens, restart) starts on the camera AE; the ceiling learns its frames again
            motion.reset();
            motionLevels.reset();
            motionCapNs = 0;
            motionActive = motionTap = false;
            motionNoLevelLogged = motionUnsafeLogged = false;
            motionUnlearned = 0;
            com.particlesdevs.photoncamera.capture.RawLevel.clear();
        }
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

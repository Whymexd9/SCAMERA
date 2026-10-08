package com.particlesdevs.photoncamera.control.subject;

import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.Face;
import android.hardware.camera2.params.MeteringRectangle;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.Display;
import android.view.View;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.capture.XiaomiTeleZoom;
import com.particlesdevs.photoncamera.control.TouchFocus;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.ui.camera.views.SubjectOverlayView;
import com.particlesdevs.photoncamera.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Face detection and tracking autofocus (P42), combined from ArkCamera v50 with the existing focus path.
 * <p>
 * <b>Faces.</b> {@code STATISTICS_FACE_DETECT_MODE} is set on the preview request from the module's advertised modes
 * ({@link FaceDetectModes}); without HAL faces the platform FaceDetector runs on viewfinder frames. Unlike ArkCam —
 * whose faces only feed its own digital tone — the primary face (ArkCam's prominence score plus hysteresis) drives the
 * Camera2 AF and AE regions of the repeating preview request: AF on the face box, AE on the box grown by 30 %,
 * throttled, held 0.6 s after the face is gone, then the metering-mode regions come back.
 * <p>
 * <b>Tracking.</b> A long press (or a tap, by setting) locks onto the subject. The {@link PatchTracker} (ArkCam's ZNCC
 * tracker) follows it on the quarter-size frames {@code SubjectFrameGrabber} reads back from the viewfinder, at most
 * 15 per second, on its own thread; the AF / AE regions follow at most 10 times a second with hysteresis. When the
 * target is lost the regions return to the defaults and the continuous AF of the user's AF mode takes over (ArkCam
 * leaves the region where the target was lost).
 * <p>
 * <b>Precedence.</b> Manual focus (AF_MODE_OFF) keeps AF regions untouched, manual ISO / shutter (AE_MODE_OFF) keeps
 * AE regions untouched, an active tap focus ({@link TouchFocus#isTouchFocus}) pauses face regions, and nothing is
 * written while a shot is in flight. Face statistics and regions ride on the preview request only; the capture,
 * merge and processing code is untouched and no stream or sensor mode changes.
 * <p>
 * Threads: camera results arrive on the camera background handler, which also owns every write to the preview
 * builder from here; tracking and software detection run on a private worker thread; the overlay is updated on the
 * UI thread.
 */
public final class SubjectFocus implements SubjectFrames.Sink {
    private static final String TAG = "SubjectFocus";

    private static final long TRACK_FRAME_NS = 66_000_000L;      // tracker input <= 15 fps
    private static final long SOFT_FACE_FRAME_NS = 250_000_000L; // software faces <= 4 fps
    private static final long FACE_REGION_MIN_MS = 250;
    private static final long TRACK_REGION_MIN_MS = 100;
    private static final long FACE_HOLD_MS = 600;
    private static final long SETTINGS_MS = 500;
    private static final int MAX_FACES = 10;
    /** Tracking box (and metering region) relative to the tracker patch. */
    private static final float TRACK_BOX = 1.6f;
    /** Misses that end a track: ArkCam's 12 frames at ~30 fps is ~0.4 s, i.e. 6 frames at 15 fps. */
    private static final int MAX_LOST = 6;

    private final CaptureController cc;
    private final View preview;
    private final SubjectOverlayView overlay;
    private final TouchFocus touchFocus;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final HandlerThread workerThread;
    private final Handler worker;
    private volatile boolean released;

    // --- settings (refreshed on the camera thread)
    private volatile FaceDetectModes.Resolved faceMode = FaceDetectModes.resolve(FaceDetectModes.OFF, null);
    private volatile String trackingMode = SubjectPolicy.TRACK_DEFAULT;
    private long settingsReadMs = -SETTINGS_MS;

    // --- view (written on the UI thread)
    private volatile int viewW, viewH, gravity = 90;

    // --- camera (camera thread)
    private CameraCharacteristics chars;
    private CaptureRequest.Builder builderSeen;
    private int activeW, activeH, maxAf, maxAe, sensorOrientation;
    private boolean lensCanFocus, front;
    private int[] fdModes;

    // --- geometry: written on the camera thread, read there and by the worker under the lock
    private final Object geomLock = new Object();
    private final ViewfinderMapping mapping = new ViewfinderMapping();
    private final float[] window = new float[4], rawCrop = new float[4];

    // --- faces (camera thread)
    private final float[] faceView = new float[4 * MAX_FACES];
    private final int[] faceScore = new int[MAX_FACES];
    private int faceCount, lastPostedCount = -1;
    private float primaryCx = -1, primaryCy = -1;
    private boolean facesOwnRegions;
    private long lastFaceSeenMs, lastFaceWriteMs;
    private final int[] lastAf = new int[4], lastAe = new int[4], nextAf = new int[4], nextAe = new int[4];
    private final float[] tmp2 = new float[2], tmp4 = new float[4];

    // --- overlay hand-over (camera thread -> UI)
    private final Object overlayLock = new Object();
    private final float[] overlayFaces = new float[4 * MAX_FACES];
    private int overlayCount, overlayPrimary = -1;
    private final AtomicBoolean overlayFacesPosted = new AtomicBoolean();
    private final Runnable overlayFacesRunnable = this::drawFaces;

    // --- tracking
    private final AtomicInteger trackGen = new AtomicInteger();
    private volatile boolean tracking;
    private final PatchTracker tracker = new PatchTracker(64, MAX_LOST);
    private volatile float trackU, trackV, trackBoxW, trackBoxH; // latest tracker output (worker -> camera / UI)
    private volatile int trackState, trackResultGen;
    private long lastTrackWriteMs;
    private final Runnable trackResultRunnable = this::onTrackResult;
    private final Runnable trackOverlayRunnable = this::drawTrack;
    private final AtomicBoolean trackOverlayPosted = new AtomicBoolean();

    // --- frames (GL thread -> worker)
    private final Object poolLock = new Object();
    private final byte[][] pool = new byte[2][];
    private final boolean[] busy = new boolean[2];
    private volatile byte[] frame;
    private volatile int frameW, frameH;
    private long lastTrackFrameNs, lastSoftFrameNs;
    private byte[] luma;
    private final Runnable frameRunnable = this::processFrame;
    private SoftwareFaces softwareFaces;
    private final float[] softRects = new float[4 * MAX_FACES];
    private final int[] softScores = new int[MAX_FACES];
    private final Object softLock = new Object();
    private final float[] softStage = new float[4 * MAX_FACES];
    private final int[] softStageScores = new int[MAX_FACES];
    private int softStageCount;
    private final Runnable softFacesRunnable = this::onSoftFaces;

    public SubjectFocus(CaptureController cc, View preview, SubjectOverlayView overlay, TouchFocus touchFocus) {
        this.cc = cc;
        this.preview = preview;
        this.overlay = overlay;
        this.touchFocus = touchFocus;
        workerThread = new HandlerThread("SubjectTracker", Process.THREAD_PRIORITY_DISPLAY);
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        preview.addOnLayoutChangeListener(layoutListener);
        readView();
        SubjectFrames.setSink(this);
    }

    private final View.OnLayoutChangeListener layoutListener = (v, l, t, r, b, ol, ot, or, ob) -> readView();

    private void readView() {
        viewW = preview.getWidth();
        viewH = preview.getHeight();
        gravity = displayGravity();
    }

    /** Display rotation on the gravity scale (90 = natural portrait), as TouchFocus uses it. */
    private int displayGravity() {
        try {
            Display display = preview.getDisplay();
            if (display != null) return display.getRotation() * 90 + 90;
        } catch (RuntimeException ignored) {
            // detached view
        }
        return 90;
    }

    /** Stops everything; the instance is not used again (a new one comes with the next resume). */
    public void release() {
        released = true;
        tracking = false;
        trackGen.incrementAndGet();
        SubjectFrames.clearSink(this);
        preview.removeOnLayoutChangeListener(layoutListener);
        ui.removeCallbacksAndMessages(null);
        worker.removeCallbacksAndMessages(null);
        worker.post(() -> {
            if (softwareFaces != null) softwareFaces.release();
            softwareFaces = null;
        });
        workerThread.quitSafely();
        overlay.clearAll();
    }

    // ================================================================== face detect mode on the preview request

    /** Writes STATISTICS_FACE_DETECT_MODE for the current module into a (new) preview builder. */
    public static FaceDetectModes.Resolved applyFaceDetectMode(@Nullable CaptureRequest.Builder builder,
                                                              @Nullable CameraCharacteristics characteristics) {
        FaceDetectModes.Resolved resolved = resolveMode(characteristics);
        if (builder == null) return resolved;
        try {
            Integer current = builder.get(CaptureRequest.STATISTICS_FACE_DETECT_MODE);
            int wanted = resolved.halMode;
            // OFF is only written over a previous non-OFF value: an untouched template keeps its own default.
            if (wanted != FaceDetectModes.HAL_OFF || (current != null && current != FaceDetectModes.HAL_OFF))
                builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, wanted);
        } catch (RuntimeException e) {
            Log.w(TAG, "face detect mode: " + e);
        }
        return resolved;
    }

    private static FaceDetectModes.Resolved resolveMode(@Nullable CameraCharacteristics characteristics) {
        String pref;
        try {
            pref = PreferenceKeys.getFaceDetectMode();
        } catch (RuntimeException e) {
            pref = FaceDetectModes.DEFAULT;
        }
        int[] available = characteristics == null ? null
                : characteristics.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
        return FaceDetectModes.resolve(pref, available);
    }

    // ================================================================== camera thread: preview results

    /** Every completed preview result (camera background thread, preview state lock held). */
    public void onPreviewResult(CaptureResult result) {
        if (released) return;
        long now = SystemClock.uptimeMillis();
        CaptureRequest.Builder builder = cc.mPreviewRequestBuilder;
        if (builder == null) return;
        if (builder != builderSeen || CaptureController.mCameraCharacteristics != chars) onNewSession(builder);
        refreshSettings(now, builder);
        updateGeometry(result);
        if (touchFocus != null && touchFocus.isTouchFocus) {
            // A tap focus owns the regions; faces resume (re-written from scratch) once it is reset.
            facesOwnRegions = false;
            lastAf[2] = lastAe[2] = 0;
        }
        FaceDetectModes.Resolved mode = faceMode;
        if (mode.halMode != FaceDetectModes.HAL_OFF && !tracking) {
            Face[] faces = null;
            try {
                faces = result.get(CaptureResult.STATISTICS_FACES);
            } catch (RuntimeException ignored) {
                // vendor HALs without the key
            }
            faceCount = convertHalFaces(faces);
            onFaces(now, builder);
        } else if (!mode.enabled() || tracking) {
            if (faceCount > 0 || lastPostedCount != 0) {
                faceCount = 0;
                postFaces(-1);
            }
            if (!tracking) releaseFaceRegions(now, builder, true);
        }
    }

    private void onNewSession(CaptureRequest.Builder builder) {
        builderSeen = builder;
        chars = CaptureController.mCameraCharacteristics;
        if (chars != null) {
            Rect active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            activeW = active == null ? 0 : active.width();
            activeH = active == null ? 0 : active.height();
            Integer af = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
            Integer ae = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
            maxAf = af == null ? 0 : af;
            maxAe = ae == null ? 0 : ae;
            Float minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            lensCanFocus = minFocus == null || minFocus > 0;
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            front = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
            Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = so == null ? 90 : so;
            fdModes = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
        }
        // New builder = default regions again: nothing of ours is live any more.
        facesOwnRegions = false;
        lastAf[2] = lastAe[2] = 0;
        primaryCx = primaryCy = -1;
        faceCount = 0;
        settingsReadMs = -SETTINGS_MS;
        if (tracking) {
            Log.d(TAG, "camera / session changed: tracking stopped");
            endTracking(false, false);
        }
        postFaces(-1);
        Log.d(TAG, "session: active=" + activeW + "x" + activeH + " maxRegions AF=" + maxAf + " AE=" + maxAe
                + " so=" + sensorOrientation + " front=" + front + " fd=" + java.util.Arrays.toString(fdModes));
    }

    private void refreshSettings(long now, CaptureRequest.Builder builder) {
        if (now - settingsReadMs < SETTINGS_MS) return;
        settingsReadMs = now;
        String track;
        try {
            track = PreferenceKeys.getTrackingAfMode();
        } catch (RuntimeException e) {
            track = SubjectPolicy.TRACK_DEFAULT;
        }
        trackingMode = track;
        if (tracking && SubjectPolicy.TRACK_OFF.equals(track)) endTracking(false, true);
        FaceDetectModes.Resolved resolved = resolveMode(chars);
        FaceDetectModes.Resolved before = faceMode;
        faceMode = resolved;
        Integer current = builder.get(CaptureRequest.STATISTICS_FACE_DETECT_MODE);
        int now0 = current == null ? FaceDetectModes.HAL_OFF : current;
        if (now0 != resolved.halMode && !cc.subjectRegionsFrozen()) {
            applyFaceDetectMode(builder, chars);
            cc.rebuildPreviewBuilder();
            Log.d(TAG, "face detection " + before + " -> " + resolved);
        }
    }

    /** Visible window of the viewfinder in Camera2 coordinates for this result. */
    private void updateGeometry(CaptureResult result) {
        int w = viewW, h = viewH;
        if (activeW <= 0 || activeH <= 0 || w <= 0 || h <= 0) return;
        int rotation = ViewfinderMapping.rotationFor(sensorOrientation, gravity);
        Rect crop = null;
        Float zoom = null;
        try {
            crop = result.get(CaptureResult.SCALER_CROP_REGION);
            if (Build.VERSION.SDK_INT >= 30) zoom = result.get(CaptureResult.CONTROL_ZOOM_RATIO);
        } catch (RuntimeException ignored) {
            // keys missing on this HAL
        }
        float cl = 0, ct = 0, cw = activeW, ch = activeH;
        if (crop != null && crop.width() > 0 && crop.height() > 0) {
            cl = crop.left;
            ct = crop.top;
            cw = crop.width();
            ch = crop.height();
        }
        synchronized (geomLock) {
            if (SubjectFrames.rawDisplayed()) {
                // Same crop LiveRawRenderer develops (CaptureController.publishLiveRawFrame): clipped crop region
                // as a fraction of the array, the Xiaomi tele's extra crop on top.
                float l = Math.max(0, cl), t = Math.max(0, ct);
                float r = Math.min(activeW, cl + cw), b = Math.min(activeH, ct + ch);
                rawCrop[0] = l / activeW;
                rawCrop[1] = t / activeH;
                rawCrop[2] = Math.max(1, r - l) / activeW;
                rawCrop[3] = Math.max(1, b - t) / activeH;
                float tele = XiaomiTeleZoom.active() ? XiaomiTeleZoom.previewCrop() : 1f;
                if (tele > 1.001f) {
                    float nw = rawCrop[2] / tele, nh = rawCrop[3] / tele;
                    rawCrop[0] += (rawCrop[2] - nw) * 0.5f;
                    rawCrop[1] += (rawCrop[3] - nh) * 0.5f;
                    rawCrop[2] = nw;
                    rawCrop[3] = nh;
                }
                ViewfinderMapping.rawWindow(activeW, activeH, rawCrop, zoom == null ? 1f : zoom, w, h, rotation, window);
            } else {
                ViewfinderMapping.ispWindow(cl, ct, cw, ch, w, h, rotation, window);
            }
            mapping.set(rotation, front, window);
        }
    }

    // ================================================================== faces

    /** HAL faces -> normalized view rectangles in faceView / faceScore; returns the count. */
    private int convertHalFaces(@Nullable Face[] faces) {
        if (faces == null || faces.length == 0) return 0;
        int n = 0;
        synchronized (geomLock) {
            if (!mapping.isValid()) return 0;
            for (Face face : faces) {
                if (n >= MAX_FACES) break;
                if (face == null) continue;
                Rect b = face.getBounds();
                if (b == null || b.width() <= 0 || b.height() <= 0) continue;
                mapping.sensorRectToView(b.left, b.top, b.right, b.bottom, tmp2, tmp4);
                System.arraycopy(tmp4, 0, faceView, 4 * n, 4);
                faceScore[n] = face.getScore();
                n++;
            }
        }
        return n;
    }

    /** Software faces arrived from the worker (camera thread). */
    private void onSoftFaces() {
        if (released || tracking || !faceMode.software) return;
        CaptureRequest.Builder builder = cc.mPreviewRequestBuilder;
        if (builder == null) return;
        synchronized (softLock) {
            faceCount = Math.min(softStageCount, MAX_FACES);
            System.arraycopy(softStage, 0, faceView, 0, 4 * faceCount);
            System.arraycopy(softStageScores, 0, faceScore, 0, faceCount);
        }
        onFaces(SystemClock.uptimeMillis(), builder);
    }

    private void onFaces(long now, CaptureRequest.Builder builder) {
        int primary = SubjectPolicy.pickPrimary(faceView, faceScore, faceCount, primaryCx, primaryCy);
        if (primary >= 0) {
            primaryCx = (faceView[4 * primary] + faceView[4 * primary + 2]) * 0.5f;
            primaryCy = (faceView[4 * primary + 1] + faceView[4 * primary + 3]) * 0.5f;
            lastFaceSeenMs = now;
        } else if (now - lastFaceSeenMs > FACE_HOLD_MS) {
            primaryCx = primaryCy = -1;
        }
        boolean tapFocus = touchFocus != null && touchFocus.isTouchFocus;
        boolean afOk = SubjectPolicy.afAllowed(builder.get(CaptureRequest.CONTROL_AF_MODE), maxAf, lensCanFocus);
        boolean aeOk = SubjectPolicy.aeAllowed(builder.get(CaptureRequest.CONTROL_AE_MODE), maxAe);
        // The accent marks the face that actually steers AF / AE; under a tap focus or fully manual settings all
        // faces are drawn plain.
        postFaces(!tapFocus && (afOk || aeOk) ? primary : -1);
        if (tapFocus) return;
        if (primary < 0) {
            releaseFaceRegions(now, builder, false);
            return;
        }
        if (cc.subjectRegionsFrozen() || (!afOk && !aeOk)) return;
        int windowSide;
        synchronized (geomLock) {
            mapping.viewRectToSensor(faceView[4 * primary], faceView[4 * primary + 1], faceView[4 * primary + 2],
                    faceView[4 * primary + 3], tmp2, tmp4);
            windowSide = Math.round(Math.max(mapping.windowWidth(), mapping.windowHeight()));
        }
        int minSide = Math.max(8, Math.min(activeW, activeH) / 50);
        boolean af = afOk && ViewfinderMapping.meteringRect(tmp4[0], tmp4[1], tmp4[2], tmp4[3], 1.0f, minSide, activeW, activeH, nextAf);
        boolean ae = aeOk && ViewfinderMapping.meteringRect(tmp4[0], tmp4[1], tmp4[2], tmp4[3], 1.3f, minSide, activeW, activeH, nextAe);
        if (!af && !ae) return;
        int[] probe = af ? nextAf : nextAe, last = af ? lastAf : lastAe;
        if (facesOwnRegions && !SubjectPolicy.regionChanged(probe, last, windowSide, now, lastFaceWriteMs,
                FACE_REGION_MIN_MS, 0.04f, 0.3f)) return;
        writeRegions(builder, af ? nextAf : null, ae ? nextAe : null);
        facesOwnRegions = true;
        lastFaceWriteMs = now;
    }

    /** Hands the default regions back once the face is gone for {@link #FACE_HOLD_MS} (or right away). */
    private void releaseFaceRegions(long now, CaptureRequest.Builder builder, boolean immediately) {
        if (!facesOwnRegions) return;
        if (!immediately && now - lastFaceSeenMs < FACE_HOLD_MS) return;
        if (cc.subjectRegionsFrozen()) return;
        restoreDefaultRegions(builder);
        facesOwnRegions = false;
    }

    private void postFaces(int primary) {
        if (faceCount == 0 && lastPostedCount == 0) return;
        synchronized (overlayLock) {
            overlayCount = faceCount;
            overlayPrimary = primary;
            System.arraycopy(faceView, 0, overlayFaces, 0, 4 * faceCount);
        }
        lastPostedCount = faceCount;
        if (overlayFacesPosted.compareAndSet(false, true)) ui.post(overlayFacesRunnable);
    }

    private void drawFaces() {
        overlayFacesPosted.set(false);
        if (released) return;
        synchronized (overlayLock) {
            if (overlayCount == 0 || tracking) overlay.clearFaces();
            else overlay.setFaces(overlayFaces, overlayCount, overlayPrimary);
        }
    }

    // ================================================================== regions

    private void writeRegions(CaptureRequest.Builder builder, @Nullable int[] af, @Nullable int[] ae) {
        try {
            if (af != null) {
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{
                        new MeteringRectangle(af[0], af[1], af[2], af[3], MeteringRectangle.METERING_WEIGHT_MAX)});
                System.arraycopy(af, 0, lastAf, 0, 4);
            }
            if (ae != null) {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{
                        new MeteringRectangle(ae[0], ae[1], ae[2], ae[3], MeteringRectangle.METERING_WEIGHT_MAX)});
                System.arraycopy(ae, 0, lastAe, 0, 4);
            }
            cc.rebuildPreviewBuilder();
        } catch (RuntimeException e) {
            Log.w(TAG, "regions: " + e);
        }
    }

    /** The regions the session started with (metering mode for AE), as TouchFocus restores them. */
    private void restoreDefaultRegions(CaptureRequest.Builder builder) {
        try {
            MeteringRectangle zero = new MeteringRectangle(0, 0, 0, 0, 0);
            if (maxAf > 0) builder.set(CaptureRequest.CONTROL_AF_REGIONS, regionsOr(cc.mPreviewMeteringAF, zero));
            if (maxAe > 0) builder.set(CaptureRequest.CONTROL_AE_REGIONS, regionsOr(cc.mPreviewMeteringAE, zero));
            lastAf[2] = lastAe[2] = 0;
            cc.rebuildPreviewBuilder();
        } catch (RuntimeException e) {
            Log.w(TAG, "restore regions: " + e);
        }
    }

    private static MeteringRectangle[] regionsOr(@Nullable MeteringRectangle[] regions, MeteringRectangle fallback) {
        return regions != null && regions.length > 0 ? regions : new MeteringRectangle[]{fallback};
    }

    // ================================================================== gestures (UI thread)

    public boolean trackingActive() {
        return tracking;
    }

    /**
     * A tap on the viewfinder (screen coordinates). Returns true when the tap was used here: in "tap" mode it starts
     * tracking (or stops it when it lands on the tracked subject); otherwise a running track is dropped and the tap
     * falls through to the tap focus.
     */
    public boolean onTap(float rawX, float rawY) {
        if (released) return false;
        float[] uv = toView(rawX, rawY);
        if (uv == null) return false;
        if (SubjectPolicy.TRACK_TAP.equals(trackingMode)) {
            if (tracking && Math.abs(uv[0] - trackU) < trackBoxW * 0.6f && Math.abs(uv[1] - trackV) < trackBoxH * 0.6f) {
                stopTracking(true);
                return true;
            }
            return startTracking(uv[0], uv[1]);
        }
        if (tracking) stopTracking(false); // the tap focus writes its own regions
        return false;
    }

    /** A long press on the viewfinder (screen coordinates); true when tracking started. */
    public boolean onLongPress(float rawX, float rawY) {
        if (released || !SubjectPolicy.TRACK_LONG_PRESS.equals(trackingMode)) return false;
        float[] uv = toView(rawX, rawY);
        return uv != null && startTracking(uv[0], uv[1]);
    }

    @Nullable
    private float[] toView(float rawX, float rawY) {
        int w = preview.getWidth(), h = preview.getHeight();
        if (w <= 0 || h <= 0) return null;
        int[] loc = new int[2];
        preview.getLocationOnScreen(loc);
        float u = (rawX - loc[0]) / w, v = (rawY - loc[1]) / h;
        if (u < 0 || u > 1 || v < 0 || v > 1) return null;
        return new float[]{u, v};
    }

    /** Starts tracking at a normalized view point (UI thread). */
    public boolean startTracking(float u, float v) {
        CaptureRequest.Builder builder = cc.mPreviewRequestBuilder;
        if (builder == null || SubjectPolicy.TRACK_OFF.equals(trackingMode)) return false;
        boolean afOk = SubjectPolicy.afAllowed(builder.get(CaptureRequest.CONTROL_AF_MODE), maxAf, lensCanFocus);
        boolean aeOk = SubjectPolicy.aeAllowed(builder.get(CaptureRequest.CONTROL_AE_MODE), maxAe);
        if (!afOk && !aeOk) {
            Log.d(TAG, "tracking not started: manual focus and exposure");
            return false;
        }
        // Drop a running tap-focus sequence or lock first: CANCEL releases the lens, default regions come back.
        if (touchFocus != null) touchFocus.resetFocusCircle();
        final int gen = trackGen.incrementAndGet();
        float side = Math.min(viewW, viewH) * PatchTracker.PATCH_FRACTION * TRACK_BOX;
        trackU = u;
        trackV = v;
        trackBoxW = viewW > 0 ? side / viewW : 0.16f;
        trackBoxH = viewH > 0 ? side / viewH : 0.12f;
        trackState = PatchTracker.PENDING;
        trackResultGen = gen;
        tracking = true;
        lastTrackFrameNs = 0;
        worker.post(() -> {
            if (gen == trackGen.get()) tracker.start(u, v);
        });
        overlay.clearFaces();
        overlay.setTracking(u, v, trackBoxW, trackBoxH);
        Handler bg = cc.mBackgroundHandler;
        if (bg != null) bg.post(() -> {
            if (gen != trackGen.get() || !tracking) return;
            facesOwnRegions = false;
            lastTrackWriteMs = 0;
            lastAf[2] = lastAe[2] = 0;
            writeTrackRegions(true);
        });
        Log.d(TAG, "tracking started at " + u + "," + v);
        return true;
    }

    /** Stops tracking (UI thread); {@code restore} hands the default regions back. */
    public void stopTracking(boolean restore) {
        if (!tracking) return;
        Handler bg = cc.mBackgroundHandler;
        tracking = false;
        trackGen.incrementAndGet();
        worker.post(tracker::stop);
        overlay.hideTracking();
        if (restore && bg != null) bg.post(() -> {
            CaptureRequest.Builder builder = cc.mPreviewRequestBuilder;
            if (builder != null && !tracking && !cc.subjectRegionsFrozen()) restoreDefaultRegions(builder);
        });
    }

    /** Camera thread. */
    private void endTracking(boolean lost, boolean restore) {
        tracking = false;
        trackGen.incrementAndGet();
        worker.post(tracker::stop);
        ui.post(overlay::hideTracking);
        CaptureRequest.Builder builder = cc.mPreviewRequestBuilder;
        if (restore && builder != null && !cc.subjectRegionsFrozen()) restoreDefaultRegions(builder);
        if (lost) Log.d(TAG, "tracking lost: back to " + (builder == null ? "?" : "AF mode "
                + builder.get(CaptureRequest.CONTROL_AF_MODE)) + " with default regions");
    }

    /** Camera thread: the worker published a tracker result. */
    private void onTrackResult() {
        if (released || !tracking || trackResultGen != trackGen.get()) return;
        if (trackState == PatchTracker.LOST) {
            endTracking(true, true);
            return;
        }
        writeTrackRegions(false);
    }

    private void writeTrackRegions(boolean force) {
        CaptureRequest.Builder builder = cc.mPreviewRequestBuilder;
        if (builder == null || cc.subjectRegionsFrozen()) return;
        boolean afOk = SubjectPolicy.afAllowed(builder.get(CaptureRequest.CONTROL_AF_MODE), maxAf, lensCanFocus);
        boolean aeOk = SubjectPolicy.aeAllowed(builder.get(CaptureRequest.CONTROL_AE_MODE), maxAe);
        if (!afOk && !aeOk) return;
        float u = trackU, v = trackV, bw = trackBoxW, bh = trackBoxH;
        int windowSide;
        synchronized (geomLock) {
            if (!mapping.isValid()) return;
            mapping.viewRectToSensor(u - bw * 0.5f, v - bh * 0.5f, u + bw * 0.5f, v + bh * 0.5f, tmp2, tmp4);
            windowSide = Math.round(Math.max(mapping.windowWidth(), mapping.windowHeight()));
        }
        int minSide = Math.max(8, Math.min(activeW, activeH) / 50);
        if (!ViewfinderMapping.meteringRect(tmp4[0], tmp4[1], tmp4[2], tmp4[3], 1f, minSide, activeW, activeH, nextAf))
            return;
        long now = SystemClock.uptimeMillis();
        if (!force && !SubjectPolicy.regionChanged(nextAf, afOk ? lastAf : lastAe, windowSide, now, lastTrackWriteMs,
                TRACK_REGION_MIN_MS, 0.02f, 0.3f)) return;
        writeRegions(builder, afOk ? nextAf : null, aeOk ? nextAf : null);
        lastTrackWriteMs = now;
    }

    private void drawTrack() {
        trackOverlayPosted.set(false);
        if (released || !tracking) return;
        overlay.setTracking(trackU, trackV, trackBoxW, trackBoxH);
    }

    // ================================================================== frames (GL thread)

    @Override
    public boolean wantsFrame(long nowNs) {
        if (released) return false;
        if (tracking) {
            if (nowNs - lastTrackFrameNs < TRACK_FRAME_NS) return false;
            lastTrackFrameNs = nowNs;
            return true;
        }
        if (faceMode.software) {
            if (nowNs - lastSoftFrameNs < SOFT_FACE_FRAME_NS) return false;
            lastSoftFrameNs = nowNs;
            return true;
        }
        return false;
    }

    @Override
    public byte[] obtainBuffer(int bytes) {
        synchronized (poolLock) {
            for (int i = 0; i < pool.length; i++) {
                if (busy[i]) continue;
                if (pool[i] == null || pool[i].length < bytes) pool[i] = new byte[bytes];
                busy[i] = true;
                return pool[i];
            }
        }
        return null;
    }

    private void releaseBuffer(byte[] buffer) {
        synchronized (poolLock) {
            for (int i = 0; i < pool.length; i++) if (pool[i] == buffer) busy[i] = false;
        }
    }

    @Override
    public void onFrame(byte[] rgba, int width, int height) {
        if (released) {
            releaseBuffer(rgba);
            return;
        }
        byte[] previous = frame;
        frameW = width;
        frameH = height;
        frame = rgba;
        if (previous != null) releaseBuffer(previous); // never processed: the newer frame replaces it
        worker.removeCallbacks(frameRunnable);
        worker.post(frameRunnable);
    }

    // ================================================================== worker thread

    private void processFrame() {
        byte[] rgba = frame;
        frame = null;
        if (rgba == null) return;
        int w = frameW, h = frameH;
        try {
            if (released) return;
            int n = w * h;
            if (luma == null || luma.length < n) luma = new byte[n];
            // RGBA bottom-up (GL) -> luma top-down.
            for (int y = 0; y < h; y++) {
                int src = (h - 1 - y) * w * 4, dst = y * w;
                for (int x = 0; x < w; x++, src += 4) {
                    int r = rgba[src] & 0xFF, g = rgba[src + 1] & 0xFF, b = rgba[src + 2] & 0xFF;
                    luma[dst + x] = (byte) ((77 * r + 150 * g + 29 * b) >> 8);
                }
            }
        } finally {
            releaseBuffer(rgba);
        }
        if (tracking && tracker.active()) {
            int gen = trackGen.get();
            int state = tracker.update(luma, w, h);
            if (gen != trackGen.get()) return;
            trackState = state;
            if (state == PatchTracker.TRACKING) {
                trackU = tracker.u();
                trackV = tracker.v();
                trackBoxW = Math.min(0.5f, tracker.patchFractionX() * TRACK_BOX);
                trackBoxH = Math.min(0.5f, tracker.patchFractionY() * TRACK_BOX);
                if (trackOverlayPosted.compareAndSet(false, true)) ui.post(trackOverlayRunnable);
            }
            trackResultGen = gen;
            Handler bg = cc.mBackgroundHandler;
            if (bg != null) {
                bg.removeCallbacks(trackResultRunnable);
                bg.post(trackResultRunnable);
            }
            return;
        }
        if (!tracking && faceMode.software) {
            if (softwareFaces == null) softwareFaces = new SoftwareFaces(MAX_FACES);
            int count;
            try {
                count = softwareFaces.detect(luma, w, h, softRects, softScores);
            } catch (RuntimeException e) {
                Log.w(TAG, "software faces: " + e);
                count = 0;
            }
            synchronized (softLock) {
                softStageCount = count;
                System.arraycopy(softRects, 0, softStage, 0, 4 * count);
                System.arraycopy(softScores, 0, softStageScores, 0, count);
            }
            Handler bg = cc.mBackgroundHandler;
            if (bg != null) {
                bg.removeCallbacks(softFacesRunnable);
                bg.post(softFacesRunnable);
            }
        }
    }
}

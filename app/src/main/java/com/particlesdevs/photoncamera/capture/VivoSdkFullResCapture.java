package com.particlesdevs.photoncamera.capture;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.os.Handler;
import android.view.Surface;

import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.ImageSaver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.LongSupplier;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import com.particlesdevs.photoncamera.processing.MosaicBlockDetector;

final class VivoSdkFullResCapture {
    interface Listener {
        void started();
        void result(int index, TotalCaptureResult result);
        void complete(ScamCaptureSequence sequence, HashMap<Long, Double> exposures);
        void failed(String reason);
        /** P222: the series completes, yet stills after the shutter are missing from it (a tolerant 50 MP ZSL shot); before complete. */
        default void degraded(String reason) { }
    }

    private final CameraDevice device;
    private final CameraCaptureSession session;
    private final VivoSdkRawSession sdk;
    private final Surface rawSurface, jpegSurface;
    private final ImageSaver saver;
    private final Handler handler;
    private final LongSupplier previewTimestamp;
    private final Listener listener;
    private final int rotation;
    private final VivoSdkFullRes.Profile profile;
    private final CaptureRequest preview;
    private final List<CaptureRequest.Key<?>> sessionKeys;
    private final long generation, cutoff;
    private final int count;
    private final List<CaptureRequest> requests = new ArrayList<>();
    private final List<TotalCaptureResult> results = new ArrayList<>();
    private final SerialRawCapture serial;
    /** P222: the stills this series keeps in flight. */
    private int inFlight;
    private final TimestampFrameRouter<Image> router;
    private volatile boolean stopped;
    private final Consumer<Image> previewConsumer;
    private boolean nativeMosaicVerified;
    /** A result of this series reported the profile's sensor mode (com.qti.sensorbps.mode_index): the HAL ran the mosaic mode. */
    private boolean nativeModeReported;
    /** Per still: its role and, when > 0, its manual exposure / sensitivity (P179 ultrashort, P198 the Hybrid plan's frames). */
    private final ImageFrame.CaptureRole[] roles;
    private final long[] manualNs;
    private final int[] manualIso;
    /** P198: the plan whose stills these are (null: the series of N frames); its measured roles decide each still's role. */
    private HybridPlan plan;
    /** P198: the N frames of a 50 MP ZSL shot, from the ring before the shutter (empty: a series after the shutter only). */
    private List<ImageFrame> past = java.util.Collections.emptyList();
    /** P212h (A7 = M14 phase 1a): CONTROL_AE_LOCK on the automatic N stills (scam_dev.txt "vivo_series_ae_lock"). */
    private boolean lockNormals;
    /**
     * P212h (A7 = M14 phase 1b): the ultrashort's exposure from the first delivered N still: its factor (0: the viewfinder's, as set by
     * {@link #ultrashortLast}), the shortest shutter and the lowest ISO.
     */
    private double usFactor;
    private long usMinNs;
    private int usMinIso;

    VivoSdkFullResCapture(CameraDevice device, CameraCaptureSession session, VivoSdkRawSession sdk,
                         Surface raw, Surface jpeg, ImageSaver saver, Handler handler,
                         LongSupplier previewTimestamp, int rotation, long generation, long cutoff,
                         int count, VivoSdkFullRes.Profile profile, CaptureRequest preview,
                         List<CaptureRequest.Key<?>> sessionKeys, Listener listener) {
        this(device, session, sdk, raw, jpeg, saver, handler, previewTimestamp, rotation, generation,
                cutoff, count, profile, preview, sessionKeys, listener, null, 0);
    }

    /**
     * {@code inFlight}: the stills kept in flight (P222, FullResInFlight; 0: the route's default without scam_dev.txt and the
     * failure memory); the SDK route always one at a time.
     */
    VivoSdkFullResCapture(CameraDevice device, CameraCaptureSession session, VivoSdkRawSession sdk,
                         Surface raw, Surface jpeg, ImageSaver saver, Handler handler,
                         LongSupplier previewTimestamp, int rotation, long generation, long cutoff,
                         int count, VivoSdkFullRes.Profile profile, CaptureRequest preview,
                         List<CaptureRequest.Key<?>> sessionKeys, Listener listener, Consumer<Image> previewConsumer, int inFlight) {
        if (profile == null) throw new IllegalArgumentException("SDK FULLRES profile is missing");
        if (preview == null) throw new IllegalArgumentException("SDK FULLRES preview request is missing");
        this.profile = profile;
        this.previewConsumer = previewConsumer;
        this.preview = preview;
        this.sessionKeys = sessionKeys == null ? java.util.Collections.emptyList() : new ArrayList<>(sessionKeys);
        this.device = device;
        this.session = session;
        this.sdk = sdk;
        rawSurface = raw;
        jpegSurface = jpeg;
        this.saver = saver;
        this.handler = handler;
        this.previewTimestamp = previewTimestamp;
        this.rotation = rotation;
        this.generation = generation;
        this.cutoff = cutoff;
        this.count = count;
        this.listener = listener;
        roles = new ImageFrame.CaptureRole[Math.max(0, count)];
        java.util.Arrays.fill(roles, ImageFrame.CaptureRole.NORMAL);
        manualNs = new long[roles.length];
        manualIso = new int[roles.length];
        // P179: the native RAW-only series keeps several stills queued, so they follow each other at the sensor's pace instead
        // of each waiting behind the repeating preview requests (200 MP: ~5 s per frame one by one); P222 how many (FullResInFlight)
        this.inFlight = !profile.nativeRoute() ? 1 : inFlight > 0 ? Math.min(FullResInFlight.MAX, inFlight)
                : FullResInFlight.choose(count, true, FullResInFlight.huge(profile), 0, null).count;
        serial = new SerialRawCapture(count, profile.nativeBlock == 0, this.inFlight, new SerialRawCapture.Listener() {
            public void submit(int index) { submitFrame(index); }
            public void complete() { finish(); }
            public void failed(String reason) { fail(reason); }
            public void head(int index) { armTimeout(index); }
        });
        router = new TimestampFrameRouter<>((image, still) -> {
            if (!still && profile.nativeRoute() && !stopped) {
                if (this.previewConsumer == null) image.close(); else this.previewConsumer.accept(image);
                return;
            }
            long timestamp = image.getTimestamp();
            if (stopped || !serial.acceptsRaw(timestamp)) { image.close(); return; }
            try {
                Image.Plane plane = image.getPlanes()[0];
                if (image.getFormat() != ImageFormat.RAW_SENSOR
                        || image.getWidth() != profile.size.getWidth() || image.getHeight() != profile.size.getHeight()
                        || plane.getPixelStride() != 2
                        || !hasNonZeroPixels(plane.getBuffer(), image.getWidth(), image.getHeight(), plane.getRowStride())) {
                    image.close();
                    serial.fail("SDK FULLRES50 delivered an empty or invalid RAW buffer");
                    return;
                }
                // P194: a native Bayer row (the HAL's hardware remosaic) fails on a confident Quad / Tetra still the same way
                if (profile.nativeRoute()) {
                    final boolean[] mosaic = mosaicCheck(profile, plane.getBuffer(), image.getWidth(), image.getHeight(), plane.getRowStride());
                    if (mosaic[1]) {
                        image.close();
                        serial.fail("Native FULLRES mosaic does not match its sensor profile");
                        return;
                    }
                    nativeMosaicVerified |= mosaic[0];
                }
                saver.initProcess(image);
                serial.raw(timestamp);
            } catch (RuntimeException failure) {
                try { image.close(); } catch (RuntimeException ignored) { }
                serial.fail("RAW copy failed: " + failure.getMessage());
            }
        }, Image::close);
    }

    void start() {
        // P179: a 200 MP series stops the repeating preview at the shutter, so its stills follow the requests the HAL already
        // holds instead of a queue of ~13 preview frames (2.7 fps at 200 MP: ~5 s); the camera closes for the merge right after
        if (stopsPreview(profile)) {
            try {
                session.stopRepeating();
            } catch (Exception e) {
                android.util.Log.w("SCAM_CAPTURE", "200 MP: the preview did not stop for the series: " + e);
            }
        }
        serial.start();
    }

    /** P179: a native RAW-only series above 100 MP stops the preview while it runs. */
    static boolean stopsPreview(VivoSdkFullRes.Profile profile) {
        return profile != null && profile.nativeBlock > 1
                && (long) profile.size.getWidth() * profile.size.getHeight() > CaptureController.HUGE_SHOT_PIXELS;
    }

    /**
     * P198 (owner, 2026-10-17: «Так же сделай так чтобы 50мп снимались в zsl и только 200мп снимались в psl»): a native FULLRES
     * session up to 100 MP (50 MP: Quad, or P194's hardware-remosaic Bayer row) keeps a ZSL ring of its frames and takes a shot's
     * N from it, as the 12 MP Hybrid; 200 MP (Tetra) and the X200 Ultra's SDK route (nativeBlock 0, JPEG reader) stay serial PSL.
     */
    static boolean zslRoute(VivoSdkFullRes.Profile profile) {
        return profile != null && profile.nativeRoute()
                && (long) profile.size.getWidth() * profile.size.getHeight() <= CaptureController.HUGE_SHOT_PIXELS;
    }

    /**
     * P198: the N frames a 50 MP ZSL shot asks of its ring, as the 12 MP Hybrid: P190's «Кадров в снимке 50 Мп» when set (N > 0),
     * else (0) the merge's own frame setting.
     */
    static int zslRequested(int fullRes50Frames, int hybridFrames) {
        return Math.max(1, fullRes50Frames > 0 ? fullRes50Frames : hybridFrames);
    }

    /**
     * P198: the ring frames a 50 MP ZSL shot keeps beside its {@code post} stills after the shutter when the memory allows
     * {@code budget} frames in all: the newest ones, at least one (0 without ring frames).
     */
    static int zslNormals(int ringFrames, int post, int budget) {
        if (ringFrames <= 0) return 0;
        return Math.max(1, Math.min(ringFrames, budget - Math.max(0, post)));
    }

    /**
     * P232 (speed-ux A2): the frames of a 50 MP ZSL shot once the memory budget is known - {ring frames kept, N after the shutter,
     * budget}. The budget is asked for the whole shot ({@code requested} N, at least the ring's, and the plan's {@code extras}
     * ultrashort / bracketed stills); the ring frames stay as far as the budget holds them beside the extras (zslNormals), and
     * the N after the shutter fill what is left up to {@code requested}. Before P232 the N after the shutter were fixed first and
     * the budget then dropped the oldest ring frames: the zero-lag ones went, the frames after the press stayed.
     */
    static int[] zslSplit(int ringFrames, int extras, int requested, java.util.function.IntUnaryOperator budget) {
        final int ring = Math.max(0, ringFrames), post = Math.max(0, extras);
        final int asked = Math.max(ring, Math.max(1, requested)) + post;
        final int allowed = Math.min(asked, budget.applyAsInt(asked));
        final int keep = zslNormals(ring, post, allowed);
        final int topUp = Math.max(0, Math.min(Math.max(1, requested) - keep, allowed - post - keep));
        return new int[]{keep, topUp, allowed};
    }

    /**
     * P233 (speed-ux A5): the N frames a 50 MP ZSL shot gives the merge, the figure of «Снято K из N кадров» - the ring's and those
     * after the shutter (the plan's ultrashort / bracketed stills are not N). Before P233 the toast counted the ring's alone.
     */
    static int zslTaken(int ringKept, int topUp) {
        return Math.max(0, ringKept) + Math.max(0, topUp);
    }

    /**
     * The result's sensor mode is the profile's: the SDK route needs it reported, the native route fails only on a reported other
     * mode (P198: the 50 MP ZSL ring's frames are held to the same rule as the stills).
     */
    static boolean modeMatches(VivoSdkFullRes.Profile profile, int[] mode) {
        if (profile.nativeBlock == 0 && mode == null) return false;
        return mode == null || mode.length > 0 && mode[0] == profile.sensorMode;
    }

    /** The proof's key: the camera's profile (size, sensor mode, colour block). */
    static String proofKey(VivoSdkFullRes.Profile profile) {
        return profile.size.getWidth() + "x" + profile.size.getHeight() + "/" + profile.sensorMode + "/" + profile.nativeBlock;
    }

    /** Profiles whose colour block a confident still proved on this firmware (Build.FINGERPRINT): kept across runs. */
    static final class MosaicProof {
        private static final String PREFS = "scam_fullres_mosaic_proof";
        private static final java.util.Set<String> PROVEN = java.util.concurrent.ConcurrentHashMap.newKeySet();

        private MosaicProof() {
        }

        static boolean known(String key) {
            if (PROVEN.contains(key)) return true;
            final android.content.SharedPreferences p = prefs();
            final boolean stored = p != null && android.os.Build.FINGERPRINT != null
                    && android.os.Build.FINGERPRINT.equals(p.getString(key, null));
            if (stored) PROVEN.add(key);
            return stored;
        }

        static void remember(String key) {
            if (!PROVEN.add(key)) return;
            final android.content.SharedPreferences p = prefs();
            if (p != null) p.edit().putString(key, android.os.Build.FINGERPRINT).apply();
        }

        /** Tests. */
        static void clear() {
            PROVEN.clear();
        }

        @androidx.annotation.Nullable
        private static android.content.SharedPreferences prefs() {
            try {
                final android.content.Context c = com.particlesdevs.photoncamera.app.ScameraApp.getAppContext();
                return c == null ? null : c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** P194 / P198: the pixels are no confident mosaic of another colour block than the profile's; {verified, mismatch}. */
    static boolean[] mosaicCheck(VivoSdkFullRes.Profile profile, ByteBuffer pixels, int width, int height, int rowStride) {
        MosaicBlockDetector.Result mosaic = MosaicBlockDetector.detect(pixels, width, height, rowStride, 64, 1023, 64);
        return new boolean[]{mosaic.confident && mosaic.block == profile.nativeBlock, mosaic.confident && mosaic.block != profile.nativeBlock};
    }
    void image(Image image) {
        if (stopped) image.close(); else router.image(image.getTimestamp(), image);
    }
    void jpegDrained(long timestamp) { if (!stopped) serial.jpeg(timestamp); }
    void previewStarted(long timestamp) { if (!stopped && profile.nativeRoute()) router.request(timestamp, false); }

    /** P179: the last still of a native series is an ultrashort one (manual exposure); before {@link #start}. */
    void ultrashortLast(long exposureNs, int iso) {
        if (exposureNs <= 0 || iso <= 0 || count < 2 || !profile.nativeRoute()) return;
        roles[count - 1] = ImageFrame.CaptureRole.EXTRA_SHORT;
        manualNs[count - 1] = exposureNs;
        manualIso[count - 1] = iso;
    }

    /**
     * P212h (A7 = M14 phase 1a, scam_dev.txt "vivo_series_ae_lock", default off): the automatic N stills of a native series carry
     * CONTROL_AE_LOCK (the HAL holds the exposure it converged to across the series) instead of AE ON with the lock off; the caller sets
     * it only when the viewfinder AE is CONVERGED at the press. Whether a vendor mode honours the lock across P208's switch to the
     * 200 MP mode is unknown: the series' spread of exposure x ISO is logged at its end. Before {@link #start}.
     */
    void lockNormals(boolean on) {
        lockNormals = on && profile.nativeRoute();
    }

    /**
     * P212h (A7 = M14 phase 1b, scam_dev.txt "vivo_still_us_from_n", default off): on P208's still-only route the viewfinder is the
     * normal mode, so {@link #ultrashortLast}'s exposure (the viewfinder's over {@code factor}) is not 1 / factor of the N the 200 MP
     * mode delivers. With 4 stills or more the last one is built after the first has completed (at most count - 1 in flight, SerialRawCapture
     * submits lazily), so it takes the first delivered N's shutter over {@code factor} at that N's ISO (ultrashortFrom). Count, order
     * and mode stay. Before {@link #start}.
     */
    void ultrashortFromFirstN(double factor, long minNs, int minIso) {
        if (!(factor > 1) || !profile.nativeRoute()) return;
        usFactor = factor;
        usMinNs = Math.max(1L, minNs);
        usMinIso = Math.max(1, minIso);
        // P222 keeps up to 6 in flight: the last still must still be built after the first N has completed
        if (count >= 4 && inFlight > count - 1) {
            inFlight = count - 1;
            serial.limitInFlight(inFlight);
        }
    }

    /**
     * P212h (phase 1b): the ultrashort {shutter ns, ISO} from an N still at {@code nNs} / {@code nIso}: the N's shutter over
     * {@code factor} at the N's ISO; below {@code minNs} the ISO takes the rest (not below {@code minIso}). Null for no N.
     */
    @androidx.annotation.Nullable
    static long[] ultrashortFrom(long nNs, int nIso, double factor, long minNs, int minIso) {
        if (nNs <= 0 || nIso <= 0 || !(factor > 1)) return null;
        double ns = nNs / factor;
        long iso = nIso;
        if (ns < minNs) {
            iso = Math.max(Math.max(1, minIso), Math.round(nIso * ns / minNs));
            ns = minNs;
        }
        return new long[]{Math.round(ns), iso};
    }

    /** P212h (phase 1a): the spread (max / min - 1) of exposure x ISO over the N stills; NaN for fewer than 2. */
    static double exposureSpread(double[] products, int n) {
        double lo = Double.POSITIVE_INFINITY, hi = 0;
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (!(products[i] > 0)) continue;
            lo = Math.min(lo, products[i]);
            hi = Math.max(hi, products[i]);
            k++;
        }
        return k < 2 ? Double.NaN : hi / lo - 1;
    }

    /**
     * P201: the N stills of a series after the shutter at a hand-set exposure (the ultrashort / bracketed ones keep theirs).
     * Before {@link #start}.
     */
    void manualNormals(long exposureNs, int iso) {
        if (exposureNs <= 0 || iso <= 0) return;
        for (int i = 0; i < count; i++) {
            if (roles[i] != ImageFrame.CaptureRole.NORMAL || manualNs[i] > 0) continue;
            manualNs[i] = exposureNs;
            manualIso[i] = iso;
        }
    }

    /**
     * P198: the stills are the 12 MP Hybrid's post-shutter frames of {@code plan} (ultrashort / Bento, bracketed / Shasta), one per
     * request, each with the plan's role and exposure; a still that comes back at another exposure keeps the role its measured
     * exposure gives (HybridPlan.classify) or leaves the merge. Before {@link #start}.
     */
    void plan(HybridPlan plan) {
        if (plan == null || plan.requests.size() != count || !profile.nativeRoute())
            throw new IllegalArgumentException("Native FULLRES plan does not match its series");
        this.plan = plan;
        for (int i = 0; i < count; i++) {
            HybridPlan.Request r = plan.requests.get(i);
            roles[i] = r.role;
            manualNs[i] = r.autoExposure ? 0 : r.shutterNs;
            manualIso[i] = r.autoExposure ? 0 : r.iso;
        }
    }

    /**
     * P198: the shot's N frames from the 50 MP ZSL ring (already in the saver's buffer, checked for the mode and the mosaic);
     * {@code mosaicVerified}: one of them proved the profile's colour block. Before {@link #start}.
     */
    void zsl(List<ImageFrame> past, boolean mosaicVerified) {
        this.past = past == null ? java.util.Collections.emptyList() : new ArrayList<>(past);
        nativeMosaicVerified |= mosaicVerified;
    }

    /** P198: the stills after the shutter of a 50 MP ZSL shot: the N its ring lacks and the plan's bracket (VivoSdkFullRes: 64 a series). */
    static final int MAX_SERIES = 64;

    /** P222: the stills this series keeps in flight (FullResInFlight; the SDK route 1). */
    int inFlight() { return inFlight; }

    /**
     * A still that has been the oldest one in flight this long without its RAW, result (and JPEG) fails the series. P222: from
     * the moment it became the oldest (SerialRawCapture.Listener.head), not from its submit: a still queued behind others (6 of
     * them at a 1 s hand-set shutter) waits for them first. One in flight: from its submit, as before.
     */
    static final long STILL_TIMEOUT_MS = 8000;

    private void armTimeout(int index) {
        handler.postDelayed(() -> {
            if (!stopped && !serial.finished() && serial.index() == index)
                serial.fail("SDK RAW/result/JPEG timeout at frame " + index);
        }, STILL_TIMEOUT_MS);
    }

    void cancel() {
        stopped = true;
        serial.cancel();
        router.clear();
    }

    private void submitFrame(int index) {
        try {
            long displayTimestamp = previewTimestamp.getAsLong();
            if (displayTimestamp <= 0) throw new IllegalStateException("SDK RAW requires a live preview timestamp");
            CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(rawSurface);
            if (jpegSurface != null) builder.addTarget(jpegSurface);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            Antibanding.apply(builder, null); // P411
            builder.set(CaptureRequest.JPEG_ORIENTATION, rotation);
            builder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);
            if (profile.nativeRoute()) VivoPd2454NativeRaw.applyFullRes(builder, profile);
            else {
                sdk.apply(builder, true, displayTimestamp);
                VivoSdkRawSession.applyAutomaticExposure(builder);
            }
            for (CaptureRequest.Key<?> key : sessionKeys) copySessionKey(builder, key);
            if (profile.nativeRoute()) {
                VivoPd2454NativeRaw.applyFullRes(builder, profile);
                if (profile.stillOnly && VivoNativeFullRes.forceOnStills()) VivoPd2454NativeRaw.forceMode(builder, profile);
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                        preview.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION));
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, preview.get(CaptureRequest.CONTROL_AE_REGIONS));
            }
            copyManual(builder);
            // P212h (A7 phase 1a): the automatic N stills hold the exposure the viewfinder AE converged to
            if (lockNormals && roles[index] == ImageFrame.CaptureRole.NORMAL && manualNs[index] <= 0)
                builder.set(CaptureRequest.CONTROL_AE_LOCK, true);
            // P212h (A7 phase 1b): the last still's ultrashort from the first N the mode delivered
            if (index == count - 1) retargetUltrashort(index);
            if (manualNs[index] > 0 && manualIso[index] > 0) {
                // P179: the Bento frame: manual exposure (the preview's over the ultrashort factor), the same sensitivity; P198 a frame
                // of the Hybrid plan at its exposure (on the OPPO over the mode's own AE-off exposure, its frame time kept long enough)
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualNs[index]);
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso[index]);
                final Long frameNs = builder.get(CaptureRequest.SENSOR_FRAME_DURATION);
                if (frameNs != null && frameNs > 0 && frameNs < manualNs[index])
                    builder.set(CaptureRequest.SENSOR_FRAME_DURATION, manualNs[index]);
            }
            builder.setTag(new ImageFrame.ScamCaptureTag(generation, index, roles[index]));
            CaptureRequest request = builder.build();
            requests.add(request);
            session.capture(request, new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureStarted(CameraCaptureSession s, CaptureRequest r, long timestamp, long frame) {
                    if (stopped || r != request) return;
                    serial.started(index, timestamp);
                    if (stopped) return;
                    listener.started();
                    router.request(timestamp, true);
                }
                @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
                    if (stopped || r != request) return;
                    Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                    Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                    Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                    int[] mode = result.get(new CaptureResult.Key<>("com.qti.sensorbps.mode_index", int[].class));
                    if (!modeMatches(profile, mode)) {
                        serial.fail("SDK FULLRES50 sensor mode is not active");
                        return;
                    }
                    if (mode != null) nativeModeReported = true;
                    if (result.getRequest() == null || result.getRequest().getTag() != request.getTag()
                            || timestamp == null || exposure == null || exposure <= 0 || iso == null || iso <= 0) {
                        serial.fail("Invalid SDK RAW metadata");
                        return;
                    }
                    results.add(result);
                    listener.result(index, result);
                    serial.result(index, timestamp);
                }
                @Override public void onCaptureSequenceCompleted(CameraCaptureSession s, int sequenceId, long frame) {
                    if (!stopped) serial.sequenceCompleted(index);
                }
                @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure failure) {
                    if (!stopped) serial.fail("SDK STILL failed: " + failure.getReason());
                }
                @Override public void onCaptureBufferLost(CameraCaptureSession s, CaptureRequest r, Surface target, long frame) {
                    if (!stopped && (target == rawSurface || target == jpegSurface)) serial.fail("SDK STILL buffer lost");
                }
                @Override public void onCaptureSequenceAborted(CameraCaptureSession s, int sequenceId) {
                    if (!stopped) serial.fail("SDK STILL aborted");
                }
            }, handler);
        } catch (Exception failure) {
            serial.fail("SDK STILL submit failed: " + failure.getMessage());
        }
    }

    /** P212h (A7 phase 1b): the first delivered N still's result (null: none yet). */
    @androidx.annotation.Nullable
    private TotalCaptureResult firstNormal() {
        for (TotalCaptureResult r : results) {
            final Object tag = r.getRequest() == null ? null : r.getRequest().getTag();
            if (tag instanceof ImageFrame.ScamCaptureTag && ((ImageFrame.ScamCaptureTag) tag).role == ImageFrame.CaptureRole.NORMAL) return r;
        }
        return null;
    }

    /** P212h (A7 phase 1b): the ultrashort still {@code index} at 1 / factor of the first delivered N (ultrashortFromFirstN). */
    private void retargetUltrashort(int index) {
        if (!(usFactor > 1) || count < 4 || roles[index] != ImageFrame.CaptureRole.EXTRA_SHORT || manualNs[index] <= 0) return;
        final TotalCaptureResult n = firstNormal();
        final Long nNs = n == null ? null : n.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        final Integer nIso = n == null ? null : n.get(CaptureResult.SENSOR_SENSITIVITY);
        final long[] us = nNs == null || nIso == null ? null : ultrashortFrom(nNs, nIso, usFactor, usMinNs, usMinIso);
        if (us == null) {
            com.particlesdevs.photoncamera.util.Log.i("SCAM_CAPTURE", "200 MP still-only: no N result before the ultrashort still, the viewfinder's "
                    + manualNs[index] + " ns ISO " + manualIso[index] + " kept");
            return;
        }
        com.particlesdevs.photoncamera.util.Log.i("SCAM_CAPTURE", String.format(java.util.Locale.ROOT,
                "200 MP still-only: ultrashort from the first N (%.3f ms ISO %d) over %.1f: %.3f ms ISO %d (the viewfinder's %.3f ms ISO %d)",
                nNs / 1e6, nIso, usFactor, us[0] / 1e6, us[1], manualNs[index] / 1e6, manualIso[index]));
        manualNs[index] = us[0];
        manualIso[index] = (int) us[1];
    }

    /**
     * P201: a hand-set white balance (AWB off and its gains / transform) and focus (AF off and its distance) of the viewfinder
     * request go onto every still: the still template carries the automatic ones.
     */
    private void copyManual(CaptureRequest.Builder builder) {
        final Integer awb = preview.get(CaptureRequest.CONTROL_AWB_MODE);
        if (awb != null && awb == CaptureRequest.CONTROL_AWB_MODE_OFF) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);
            copyKey(builder, CaptureRequest.COLOR_CORRECTION_MODE);
            copyKey(builder, CaptureRequest.COLOR_CORRECTION_GAINS);
            copyKey(builder, CaptureRequest.COLOR_CORRECTION_TRANSFORM);
        }
        final Integer af = preview.get(CaptureRequest.CONTROL_AF_MODE);
        if (af != null && af == CaptureRequest.CONTROL_AF_MODE_OFF) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            copyKey(builder, CaptureRequest.LENS_FOCUS_DISTANCE);
        }
    }

    private <T> void copyKey(CaptureRequest.Builder builder, CaptureRequest.Key<T> key) {
        final T value = preview.get(key);
        if (value != null) builder.set(key, value);
    }

    private <T> void copySessionKey(CaptureRequest.Builder builder, CaptureRequest.Key<T> key) {
        builder.set(key, preview.get(key));
    }

    private void finish() {
        try {
            // P194: a Bayer still the detector cannot tell (a flat scene) is no mosaic either: only Quad / Tetra must be proven
            if (profile.nativeBlock > 1) {
                final String key = proofKey(profile);
                if (nativeMosaicVerified) MosaicProof.remember(key);
                // owner, 2026-10-19 (X200 Ultra 200 MP, a blurred flat sheet: «Native FULLRES mosaic has not been verified from RAW
                // pixels», the shot lost): a scene the detector cannot read proves nothing either way. No still contradicted the
                // profile (that fails at once above); the HAL's reported sensor mode or an earlier proof of this camera's mosaic on
                // this firmware vouch for it. Never proven and not reported: the shot fails as before.
                else if (nativeModeReported || MosaicProof.known(key))
                    com.particlesdevs.photoncamera.util.Log.i("SCAM_CAPTURE", "native FULLRES: the scene shows no confident colour block; "
                            + (nativeModeReported ? "the HAL reported sensor mode " + profile.sensorMode : "this camera's mosaic was proven before")
                            + ", block " + profile.nativeBlock + " kept");
                else throw new IllegalStateException("Native FULLRES mosaic has not been verified from RAW pixels");
            }
            // P198: a 50 MP ZSL shot is the ring's N and these stills, tolerant as the 12 MP Hybrid (a still the plan's roles do not
            // fit leaves the merge); the series after the shutter alone stays strict
            final boolean zsl = !past.isEmpty();
            ScamCaptureSequence sequence = zsl ? ScamCaptureSequence.hybridZsl(requests, past, cutoff, MAX_SERIES)
                    : ScamCaptureSequence.hybridFuture(requests, cutoff);
            HashMap<Long, Double> exposures = new HashMap<>();
            for (ImageFrame frame : past) exposures.put(frame.timestamp, frame.measuredExposure / 1e9 * frame.measuredIso);
            int offRole = 0;
            for (TotalCaptureResult result : results) {
                if (plan != null) {
                    ImageFrame.CaptureRole role = plan.classify(result.getRequest(), result);
                    if (role == null) { sequence.lost(result.getRequest(), "exposure outside every hybrid role"); offRole++; continue; }
                    sequence.reclassify(result.getRequest(), role);
                }
                sequence.completed(result.getRequest(), result);
                exposures.put(result.get(CaptureResult.SENSOR_TIMESTAMP),
                        result.get(CaptureResult.SENSOR_EXPOSURE_TIME) / 1e9 * result.get(CaptureResult.SENSOR_SENSITIVITY));
            }
            if (profile.nativeRoute() && past.isEmpty() && plan == null) logNormalSpread(); // P212h: a PSL series of AE-on N
            List<ImageFrame> unmatched = sequence.bindAndValidate(saver.snapshotFrames());
            if (zsl) {
                if (!unmatched.isEmpty()) saver.removeFrames(unmatched);
                // P222: a still that came back whole yet is not in the shot (its RAW or metadata lost; the exposures outside
                // every role aside) is a missing frame of the series
                final int missing = requests.size() - offRole - sequence.boundFutureCount();
                if (missing > 0) listener.degraded(missing + " of " + requests.size() + " stills after the shutter missing");
            } else if (!unmatched.isEmpty() || sequence.boundFutureCount() != requests.size())
                throw new IllegalStateException("Incomplete SDK RAW series");
            cancel();
            listener.complete(sequence, exposures);
        } catch (RuntimeException failure) {
            fail(failure.getMessage());
        }
    }

    /** P212h (A7 phase 1a): the series' spread of exposure x ISO over its N stills after the shutter (log only). */
    private void logNormalSpread() {
        final double[] products = new double[results.size()];
        int n = 0;
        for (TotalCaptureResult r : results) {
            final Object tag = r.getRequest() == null ? null : r.getRequest().getTag();
            if (!(tag instanceof ImageFrame.ScamCaptureTag) || ((ImageFrame.ScamCaptureTag) tag).role != ImageFrame.CaptureRole.NORMAL) continue;
            final Long ns = r.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            final Integer iso = r.get(CaptureResult.SENSOR_SENSITIVITY);
            if (ns != null && iso != null) products[n++] = (double) ns * iso;
        }
        final double spread = exposureSpread(products, n);
        if (!Double.isNaN(spread))
            com.particlesdevs.photoncamera.util.Log.i("SCAM_CAPTURE", String.format(java.util.Locale.ROOT,
                    "native series: %d N stills, exposure x ISO spread %.2f %% (AE lock %s)", n, spread * 100, lockNormals ? "on" : "off"));
    }

    private void fail(String reason) {
        if (stopped) return;
        cancel();
        listener.failed(reason);
    }

    static boolean hasNonZeroPixels(ByteBuffer buffer, int width, int height, int stride) {
        if (buffer == null || width <= 0 || height <= 0 || width > Integer.MAX_VALUE / 2 || stride < width * 2
                || (long) (height - 1) * stride + width * 2 > buffer.remaining()) return false;
        int base = buffer.position(), rowBytes = width * 2;
        for (int y = 0; y < height; y++) {
            int row = base + y * stride, x = 0;
            for (; x <= rowBytes - 8; x += 8) if (buffer.getLong(row + x) != 0) return true;
            for (; x < rowBytes; x++) if (buffer.get(row + x) != 0) return true;
        }
        return false;
    }
}

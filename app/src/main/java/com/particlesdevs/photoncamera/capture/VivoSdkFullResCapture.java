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
    private final TimestampFrameRouter<Image> router;
    private volatile boolean stopped;
    private final Consumer<Image> previewConsumer;
    private boolean nativeMosaicVerified;
    /** Per still: its role and, when > 0, its manual exposure / sensitivity (P179 ultrashort, P198 the Hybrid plan's frames). */
    private final ImageFrame.CaptureRole[] roles;
    private final long[] manualNs;
    private final int[] manualIso;
    /** P198: the plan whose stills these are (null: the series of N frames); its measured roles decide each still's role. */
    private HybridPlan plan;
    /** P198: the N frames of a 50 MP ZSL shot, from the ring before the shutter (empty: a series after the shutter only). */
    private List<ImageFrame> past = java.util.Collections.emptyList();

    VivoSdkFullResCapture(CameraDevice device, CameraCaptureSession session, VivoSdkRawSession sdk,
                         Surface raw, Surface jpeg, ImageSaver saver, Handler handler,
                         LongSupplier previewTimestamp, int rotation, long generation, long cutoff,
                         int count, VivoSdkFullRes.Profile profile, CaptureRequest preview,
                         List<CaptureRequest.Key<?>> sessionKeys, Listener listener) {
        this(device, session, sdk, raw, jpeg, saver, handler, previewTimestamp, rotation, generation,
                cutoff, count, profile, preview, sessionKeys, listener, null);
    }

    VivoSdkFullResCapture(CameraDevice device, CameraCaptureSession session, VivoSdkRawSession sdk,
                         Surface raw, Surface jpeg, ImageSaver saver, Handler handler,
                         LongSupplier previewTimestamp, int rotation, long generation, long cutoff,
                         int count, VivoSdkFullRes.Profile profile, CaptureRequest preview,
                         List<CaptureRequest.Key<?>> sessionKeys, Listener listener, Consumer<Image> previewConsumer) {
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
        // P179: the native RAW-only series keeps up to NATIVE_IN_FLIGHT stills queued, so they follow each other at the
        // sensor's pace instead of each waiting behind the repeating preview requests (200 MP: ~5 s per frame one by one)
        serial = new SerialRawCapture(count, profile.nativeBlock == 0, profile.nativeRoute() ? NATIVE_IN_FLIGHT : 1, new SerialRawCapture.Listener() {
            public void submit(int index) { submitFrame(index); }
            public void complete() { finish(); }
            public void failed(String reason) { fail(reason); }
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
     * The result's sensor mode is the profile's: the SDK route needs it reported, the native route fails only on a reported other
     * mode (P198: the 50 MP ZSL ring's frames are held to the same rule as the stills).
     */
    static boolean modeMatches(VivoSdkFullRes.Profile profile, int[] mode) {
        if (profile.nativeBlock == 0 && mode == null) return false;
        return mode == null || mode.length > 0 && mode[0] == profile.sensorMode;
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

    /** P179: stills of the native RAW-only series in flight at once (the RAW reader holds 4 images). */
    static final int NATIVE_IN_FLIGHT = 3;

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
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                        preview.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION));
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, preview.get(CaptureRequest.CONTROL_AE_REGIONS));
            }
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
            handler.postDelayed(() -> {
                if (!stopped && !serial.finished() && serial.index() == index)
                    serial.fail("SDK RAW/result/JPEG timeout at frame " + index);
            }, 8000);
        } catch (Exception failure) {
            serial.fail("SDK STILL submit failed: " + failure.getMessage());
        }
    }

    private <T> void copySessionKey(CaptureRequest.Builder builder, CaptureRequest.Key<T> key) {
        builder.set(key, preview.get(key));
    }

    private void finish() {
        try {
            // P194: a Bayer still the detector cannot tell (a flat scene) is no mosaic either: only Quad / Tetra must be proven
            if (profile.nativeBlock > 1 && !nativeMosaicVerified)
                throw new IllegalStateException("Native FULLRES mosaic has not been verified from RAW pixels");
            // P198: a 50 MP ZSL shot is the ring's N and these stills, tolerant as the 12 MP Hybrid (a still the plan's roles do not
            // fit leaves the merge); the series after the shutter alone stays strict
            final boolean zsl = !past.isEmpty();
            ScamCaptureSequence sequence = zsl ? ScamCaptureSequence.hybridZsl(requests, past, cutoff, MAX_SERIES)
                    : ScamCaptureSequence.hybridFuture(requests, cutoff);
            HashMap<Long, Double> exposures = new HashMap<>();
            for (ImageFrame frame : past) exposures.put(frame.timestamp, frame.measuredExposure / 1e9 * frame.measuredIso);
            for (TotalCaptureResult result : results) {
                if (plan != null) {
                    ImageFrame.CaptureRole role = plan.classify(result.getRequest(), result);
                    if (role == null) { sequence.lost(result.getRequest(), "exposure outside every hybrid role"); continue; }
                    sequence.reclassify(result.getRequest(), role);
                }
                sequence.completed(result.getRequest(), result);
                exposures.put(result.get(CaptureResult.SENSOR_TIMESTAMP),
                        result.get(CaptureResult.SENSOR_EXPOSURE_TIME) / 1e9 * result.get(CaptureResult.SENSOR_SENSITIVITY));
            }
            List<ImageFrame> unmatched = sequence.bindAndValidate(saver.snapshotFrames());
            if (zsl) {
                if (!unmatched.isEmpty()) saver.removeFrames(unmatched);
            } else if (!unmatched.isEmpty() || sequence.boundFutureCount() != requests.size())
                throw new IllegalStateException("Incomplete SDK RAW series");
            cancel();
            listener.complete(sequence, exposures);
        } catch (RuntimeException failure) {
            fail(failure.getMessage());
        }
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

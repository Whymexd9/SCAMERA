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
    private final long generation, cutoff;
    private final List<CaptureRequest> requests = new ArrayList<>();
    private final List<TotalCaptureResult> results = new ArrayList<>();
    private final SerialRawCapture serial;
    private final TimestampFrameRouter<Image> router;
    private volatile boolean stopped;

    VivoSdkFullResCapture(CameraDevice device, CameraCaptureSession session, VivoSdkRawSession sdk,
                         Surface raw, Surface jpeg, ImageSaver saver, Handler handler,
                         LongSupplier previewTimestamp, int rotation, long generation, long cutoff,
                         int count, Listener listener) {
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
        this.listener = listener;
        serial = new SerialRawCapture(count, new SerialRawCapture.Listener() {
            public void submit(int index) { submitFrame(index); }
            public void complete() { finish(); }
            public void failed(String reason) { fail(reason); }
        });
        router = new TimestampFrameRouter<>((image, still) -> {
            long timestamp = image.getTimestamp();
            if (stopped || !serial.acceptsRaw(timestamp)) { image.close(); return; }
            try {
                Image.Plane plane = image.getPlanes()[0];
                if (image.getFormat() != ImageFormat.RAW_SENSOR || image.getWidth() != 8192 || image.getHeight() != 6144
                        || plane.getPixelStride() != 2
                        || !hasNonZeroPixels(plane.getBuffer(), image.getWidth(), image.getHeight(), plane.getRowStride())) {
                    image.close();
                    serial.fail("SDK FULLRES50 delivered an empty or invalid RAW buffer");
                    return;
                }
                saver.initProcess(image);
                serial.raw(timestamp);
            } catch (RuntimeException failure) {
                try { image.close(); } catch (RuntimeException ignored) { }
                serial.fail("RAW copy failed: " + failure.getMessage());
            }
        }, Image::close);
    }

    void start() { serial.start(); }
    void image(Image image) {
        if (stopped) image.close(); else router.image(image.getTimestamp(), image);
    }
    void jpegDrained(long timestamp) { if (!stopped) serial.jpeg(timestamp); }

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
            builder.addTarget(jpegSurface);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            builder.set(CaptureRequest.JPEG_ORIENTATION, rotation);
            builder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);
            sdk.apply(builder, true, displayTimestamp);
            builder.setTag(new ImageFrame.ScamCaptureTag(generation, index, ImageFrame.CaptureRole.NORMAL));
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
                    if (mode == null || mode.length == 0 || mode[0] != 13) {
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

    private void finish() {
        try {
            ScamCaptureSequence sequence = ScamCaptureSequence.hybridFuture(requests, cutoff);
            HashMap<Long, Double> exposures = new HashMap<>();
            for (TotalCaptureResult result : results) {
                sequence.completed(result.getRequest(), result);
                exposures.put(result.get(CaptureResult.SENSOR_TIMESTAMP),
                        result.get(CaptureResult.SENSOR_EXPOSURE_TIME) / 1e9 * result.get(CaptureResult.SENSOR_SENSITIVITY));
            }
            List<ImageFrame> unmatched = sequence.bindAndValidate(saver.snapshotFrames());
            if (!unmatched.isEmpty() || sequence.boundFutureCount() != requests.size())
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

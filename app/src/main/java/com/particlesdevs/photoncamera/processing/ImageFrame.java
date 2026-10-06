package com.particlesdevs.photoncamera.processing;

import android.graphics.ImageFormat;
import android.media.Image;
import com.particlesdevs.photoncamera.util.Log;

import com.particlesdevs.photoncamera.control.GyroBurst;
import com.particlesdevs.photoncamera.processing.parameters.IsoExpoSelector;
import com.particlesdevs.photoncamera.util.Allocator;

import java.nio.ByteBuffer;

public class ImageFrame {
    public ByteBuffer buffer;
    /** SCAM HDR on a Quad / Tetra stream: the frame's own mosaic samples, kept when its buffer is rearranged into plain bayer. */
    public ByteBuffer mosaic;
    public long timestamp;
    public boolean fromZsl = false;
    /** RawPayloadCheck verdict of the reader Image this frame was copied from; null = plain 16-bit. */
    public String rawPayloadError;
    /** NICE: L exposure ratio for an L built from the ZSL N frames (0 = L was captured). */
    public float syntheticLongRatio = 0;
    public int width, height;
    public GyroBurst frameGyro;
    public float[][][] BlurKernels;
    public double posx, posy;
    public double rX, rY, rZ;
    public double[] HomographyMatrix;
    public double rotation;
    public int number;
    public IsoExpoSelector.ExpoPair pair;
    /**
     * Relative gradient energy of this frame, NaN until computeSharpness() runs.
     * Comparable only between frames of equal exposure and ISO: the measure is
     * normalised by mean level, not by the noise model, so a brighter or noisier
     * frame would score higher for reasons unrelated to focus or motion blur.
     */
    public float sharpness = Float.NaN;

    public long measuredExposure;
    public int measuredIso;
    public float noiseSlope = Float.NaN, noiseOffset = Float.NaN;
    public float focusDiopters = Float.NaN;
    public boolean lensMoving;
    public double blurPixels = Double.NaN;
    private android.hardware.camera2.CaptureResult captureMetadata;
    public enum CaptureRole { NORMAL, LONG, SHORT, EXTRA_SHORT }
    public static final class NiceCaptureTag {
        public final long generation;
        public final int index;
        public final CaptureRole role;
        public NiceCaptureTag(long generation, int index, CaptureRole role) {
            if (index < 0 || role == null) throw new IllegalArgumentException("Invalid NICE request tag");
            this.generation = generation;
            this.index = index;
            this.role = role;
        }
        @Override public String toString() { return role + " series=" + generation + " index=" + index; }
    }

    /** Role follows its request/result even if other burst images are missing. */
    public CaptureRole getCaptureRole() {
        android.hardware.camera2.CaptureResult matched = getMatchedCaptureMetadata();
        if (matched == null) return null;
        if (fromZsl) return CaptureRole.NORMAL;
        android.hardware.camera2.CaptureRequest request = matched.getRequest();
        Object tag = request == null ? null : request.getTag();
        if (tag instanceof NiceCaptureTag) return ((NiceCaptureTag) tag).role;
        return tag instanceof CaptureRole ? (CaptureRole) tag : null;
    }

    /** Full calibration must come from this RAW, not the last burst callback. */
    public android.hardware.camera2.CaptureResult getMatchedCaptureMetadata() {
        if (captureMetadata == null) return null;
        Long sensorTimestamp = captureMetadata.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP);
        return sensorTimestamp != null && sensorTimestamp == timestamp ? captureMetadata : null;
    }

    public void setCaptureMetadata(android.hardware.camera2.CaptureResult result) {
        // A ZSL frame already owns its result; a second metadata-map drain can
        // return null and must not erase that association.
        if (result == null) return;
        captureMetadata = result;
        noiseSlope = Float.NaN;
        noiseOffset = Float.NaN;
        Long time = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY);
        measuredExposure = time == null ? 0 : time;
        measuredIso = iso == null ? 0 : iso;
        android.util.Pair<Double,Double>[] model = result.get(android.hardware.camera2.CaptureResult.SENSOR_NOISE_PROFILE);
        if (model != null && model.length > 0) {
            double slope=0, offset=0;
            for (android.util.Pair<Double,Double> channel:model) {
                if (channel == null || channel.first == null || channel.second == null) { slope=Double.NaN; break; }
                slope += channel.first; offset += channel.second;
            }
            if (Double.isFinite(slope+offset) && slope>=0 && offset>=0) {
                noiseSlope=(float)(slope/model.length); noiseOffset=(float)(offset/model.length);
            }
        }
        Float focus = result.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE);
        Integer state = result.get(android.hardware.camera2.CaptureResult.LENS_STATE);
        focusDiopters = focus == null ? Float.NaN : focus;
        lensMoving = state != null && state == android.hardware.camera2.CaptureResult.LENS_STATE_MOVING;
    }

    public void computeSharpness() { computeSharpness(1); }

    public void computeSharpness(int block) {
        sharpness = (float) com.particlesdevs.photoncamera.capture.RawFrameQuality.score(
                buffer, width, height, width * 2, 2, block);
    }

    public long getTimestamp() {
        return timestamp;
    }

    public ImageFrame(ByteBuffer in, int format, int width, int row_stride, int shift, int capacity) {
        ByteBuffer direct;
        if (Allocator.binning) {
            int height = capacity / row_stride;
            if (format == 0x25) {
                direct = Allocator.allocateAndCopyConvertBinning(capacity, in, width, row_stride, shift);
            } else if (format == 0x26) {
                direct = Allocator.allocateAndCopyConvert12Binning(capacity, in, width, row_stride, shift);
            } else {
                direct = Allocator.allocateAndCopyBinning(capacity, in, width, height, row_stride);
            }
        } else {
            if(format == 0x25){
                direct = Allocator.allocateAndCopyConvert(capacity, in, width, row_stride, shift);
            } else if (format == 0x26) {
                direct = Allocator.allocateAndCopyConvert12(capacity, in, width, row_stride, shift);
            } else {
                direct = Allocator.allocateAndCopy(capacity, in, shift);
            }
        }
        direct.position(0);
        buffer = direct;
    }

    // Deferred ZSL copy: the frame keeps its reader Image until materialize(), so
    // the bracket can be submitted before ~20 RAWs are copied out of the reader.
    private Image pendingImage;
    private int pendingFormat, pendingRowStride, pendingShift, pendingCapacity, pendingWidth;
    private boolean pendingBinning;

    private ImageFrame() {}

    public static ImageFrame deferred(Image image, int format, int width, int rowStride, int shift, int capacity) {
        ImageFrame frame = new ImageFrame();
        frame.pendingImage = image;
        frame.pendingFormat = format;
        frame.pendingWidth = width;
        frame.pendingRowStride = rowStride;
        frame.pendingShift = shift;
        frame.pendingCapacity = capacity;
        frame.pendingBinning = Allocator.binning;
        return frame;
    }

    /** Copies a deferred frame out of the ImageReader and releases its slot. */
    public synchronized void materialize() {
        if (pendingImage == null) return;
        try {
            ByteBuffer in = pendingImage.getPlanes()[0].getBuffer();
            ByteBuffer direct;
            if (pendingBinning) {
                int height = pendingCapacity / pendingRowStride;
                direct = pendingFormat == 0x25
                        ? Allocator.allocateAndCopyConvertBinning(pendingCapacity, in, pendingWidth, pendingRowStride, pendingShift)
                        : pendingFormat == 0x26
                        ? Allocator.allocateAndCopyConvert12Binning(pendingCapacity, in, pendingWidth, pendingRowStride, pendingShift)
                        : Allocator.allocateAndCopyBinning(pendingCapacity, in, pendingWidth, height, pendingRowStride);
            } else {
                direct = pendingFormat == 0x25
                        ? Allocator.allocateAndCopyConvert(pendingCapacity, in, pendingWidth, pendingRowStride, pendingShift)
                        : pendingFormat == 0x26
                        ? Allocator.allocateAndCopyConvert12(pendingCapacity, in, pendingWidth, pendingRowStride, pendingShift)
                        : Allocator.allocateAndCopy(pendingCapacity, in, pendingShift);
            }
            direct.position(0);
            buffer = direct;
        } finally {
            pendingImage.close();
            pendingImage = null;
        }
    }

    public ImageFrame(ByteBuffer in) {
        ByteBuffer direct = Allocator.allocateAndCopy(in.capacity(), in, 0);
        direct.position(0);
        buffer = direct;
    }

    public synchronized void close() {
        if (pendingImage != null) {
            pendingImage.close();
            pendingImage = null;
        }
        if (mosaic != null) {
            Allocator.free(mosaic);
            mosaic = null;
        }
        if (buffer != null) {
            Allocator.free(buffer);
            buffer = null;
        } else {
            Log.d("ImageFrame", "Buffer is already null, nothing to close.");
        }
    }
}

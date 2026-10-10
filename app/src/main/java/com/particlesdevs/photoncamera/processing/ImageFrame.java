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
    /**
     * P72: format the RAW was read in (the stream's, or the packed layout behind RAW_SENSOR: RAW10, RawUnpack.RAW14*). The 17
     * Ultra tele in in-sensor zoom alternates RAW10 and RAW14 frames; one merge takes frames of one layout only.
     */
    public int sourceFormat;
    /** SCAM: L exposure ratio for an L built from the ZSL N frames (0 = L was captured). */
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
    /** P27: sensor samples averaged into one sample of this frame (RawBin; 1 = as captured). Divides the noise model. */
    public int binnedSamples = 1;
    public float focusDiopters = Float.NaN;
    public boolean lensMoving;
    public double blurPixels = Double.NaN;
    private android.hardware.camera2.CaptureResult captureMetadata;
    public enum CaptureRole { NORMAL, LONG, SHORT, EXTRA_SHORT }
    public static final class ScamCaptureTag {
        public final long generation;
        public final int index;
        public final CaptureRole role;
        public ScamCaptureTag(long generation, int index, CaptureRole role) {
            if (index < 0 || role == null) throw new IllegalArgumentException("Invalid SCAM request tag");
            this.generation = generation;
            this.index = index;
            this.role = role;
        }
        @Override public String toString() { return role + " series=" + generation + " index=" + index; }
    }

    /**
     * P27: the role this post-shutter frame really has by its measured exposure when the HAL delivered another exposure than
     * the request asked for (HybridPlan.classify, set by ScamCaptureSequence.bindAndValidate); null = the request's role.
     */
    public CaptureRole measuredRole;

    /** Role follows its request/result even if other burst images are missing. */
    public CaptureRole getCaptureRole() {
        android.hardware.camera2.CaptureResult matched = getMatchedCaptureMetadata();
        if (matched == null) return null;
        if (fromZsl) return CaptureRole.NORMAL;
        if (measuredRole != null) return measuredRole;
        android.hardware.camera2.CaptureRequest request = matched.getRequest();
        Object tag = request == null ? null : request.getTag();
        if (tag instanceof ScamCaptureTag) return ((ScamCaptureTag) tag).role;
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
        this(in, format, width, row_stride, shift, capacity, null);
    }

    /** P30: a plain 16-bit RAW goes straight into the shot's arena when there is one (ShotArena), else a native copy. */
    public ImageFrame(ByteBuffer in, int format, int width, int row_stride, int shift, int capacity,
                      com.particlesdevs.photoncamera.util.ShotArena arena) {
        ByteBuffer direct = arena == null || Allocator.binning ? null
                : Allocator.isPackedRaw(format) ? arena.copyUnpacked(in, shift, format, width, row_stride, capacity)
                : arena.copy(in, shift, capacity);
        if (direct != null) {
            direct.position(0);
            buffer = direct;
            return;
        }
        if (com.particlesdevs.photoncamera.util.RawUnpack.isRaw14(format)) {
            // P72: packed RAW14 without the shot's arena: unpacked here (liballocator has no binning copy for it)
            buffer = unpackRaw14(in, shift, format, width, row_stride, capacity, Allocator.binning);
            return;
        }
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
    /** P30: the shot's arena the deferred copy goes into (null: a native copy). */
    public com.particlesdevs.photoncamera.util.ShotArena arena;
    /** Bytes the deferred copy will take (0 when not deferred): a packed RAW10 / RAW12 frame is unpacked to 16 bit. */
    public int pendingBytes() {
        if (pendingImage == null) return 0;
        return Allocator.isPackedRaw(pendingFormat) && pendingRowStride > 0
                ? pendingWidth * (pendingCapacity / pendingRowStride) * 2 : pendingCapacity;
    }

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

    /** P72: a packed RAW14 frame ({@code capacity} bytes of rows from {@code offset}) to uint16, binned 2x2 when asked. */
    private static ByteBuffer unpackRaw14(ByteBuffer in, int offset, int format, int width, int rowStride, int capacity, boolean binning) {
        final int height = capacity / rowStride;
        ByteBuffer src = in.duplicate();
        src.position(offset);
        ByteBuffer plain = ByteBuffer.allocateDirect(width * height * 2).order(java.nio.ByteOrder.nativeOrder());
        if (!com.particlesdevs.photoncamera.util.RawUnpack.unpack(src, format, width, height, rowStride, plain))
            throw new IllegalStateException("RAW14 frame does not fit its geometry");
        ByteBuffer out = binning ? Allocator.allocateAndCopyBinning(width * height * 2, plain, width, height, width * 2) : plain;
        out.position(0);
        return out;
    }

    /** Copies a deferred frame out of the ImageReader and releases its slot. */
    public synchronized void materialize() {
        if (pendingImage == null) return;
        try {
            ByteBuffer in = pendingImage.getPlanes()[0].getBuffer();
            ByteBuffer direct = arena == null || pendingBinning ? null
                    : Allocator.isPackedRaw(pendingFormat)
                    ? arena.copyUnpacked(in, pendingShift, pendingFormat, pendingWidth, pendingRowStride, pendingCapacity)
                    : arena.copy(in, pendingShift, pendingCapacity);
            if (direct != null) {
                // the shot's arena (P30)
            } else if (com.particlesdevs.photoncamera.util.RawUnpack.isRaw14(pendingFormat)) {
                direct = unpackRaw14(in, pendingShift, pendingFormat, pendingWidth, pendingRowStride, pendingCapacity, pendingBinning);
            } else if (pendingBinning) {
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

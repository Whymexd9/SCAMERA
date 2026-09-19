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
    public long timestamp;
    public boolean fromZsl = false;
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

    public void setCaptureMetadata(android.hardware.camera2.CaptureResult result) {
        if (result == null) return;
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
            } else {
                direct = Allocator.allocateAndCopyBinning(capacity, in, width, height, row_stride);
            }
        } else {
            if(format == 0x25){
                direct = Allocator.allocateAndCopyConvert(capacity, in, width, row_stride, shift);
            } else {
                direct = Allocator.allocateAndCopy(capacity, in, shift);
            }
        }
        direct.position(0);
        buffer = direct;
    }

    public ImageFrame(ByteBuffer in) {
        ByteBuffer direct = Allocator.allocateAndCopy(in.capacity(), in, 0);
        direct.position(0);
        buffer = direct;
    }

    public void close() {
        if (buffer != null) {
            Allocator.free(buffer);
            buffer = null;
        } else {
            Log.d("ImageFrame", "Buffer is already null, nothing to close.");
        }
    }
}


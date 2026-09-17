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

    /**
     * Mean squared gradient over the central 60% of the raw frame, normalised by
     * the squared mean level.
     *
     * Neighbours are taken two pixels apart so both samples share a CFA colour,
     * and the grid is walked in steps of four to keep the cost near a megapixel
     * of reads regardless of sensor size. Motion blur and defocus both suppress
     * high-frequency energy, so the frame scoring highest is the sharpest of a
     * constant-exposure group.
     */
    public void computeSharpness() {
        if (buffer == null || width <= 8 || height <= 8) return;
        // Buffers from Allocator come through JNI NewDirectByteBuffer, which yields
        // BIG_ENDIAN regardless of the platform, so asShortBuffer() would read each
        // RAW16 sample byte-swapped. Duplicate rather than reorder in place: the
        // buffer is uploaded to GL elsewhere and its position must not move.
        java.nio.ShortBuffer raw = buffer.duplicate()
                .order(java.nio.ByteOrder.nativeOrder())
                .asShortBuffer();
        if (raw.remaining() < width * height) return;
        int x0 = (width / 5) & ~1, x1 = width - x0;
        int y0 = (height / 5) & ~1, y1 = height - y0;
        double grad = 0.0, mean = 0.0;
        long n = 0;
        for (int y = y0; y < y1 - 2; y += 4) {
            int row = y * width;
            for (int x = x0; x < x1 - 2; x += 4) {
                int c = raw.get(row + x) & 0xFFFF;
                int dx = (raw.get(row + x + 2) & 0xFFFF) - c;
                int dy = (raw.get(row + 2 * width + x) & 0xFFFF) - c;
                grad += (double) dx * dx + (double) dy * dy;
                mean += c;
                n++;
            }
        }
        if (n == 0) return;
        mean /= n;
        if (mean <= 1.0) return;
        sharpness = (float) (grad / n / (mean * mean));
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


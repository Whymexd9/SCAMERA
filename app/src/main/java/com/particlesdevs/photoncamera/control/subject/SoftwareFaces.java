package com.particlesdevs.photoncamera.control.subject;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.media.FaceDetector;

import java.nio.ShortBuffer;

/**
 * Software face detection for modules without Camera2 face statistics (P42): the platform's
 * {@link android.media.FaceDetector} (no ML dependency) on the downscaled, upright viewfinder frame the tracker also
 * uses. It finds frontal faces by their eyes; the box is built from the eye midpoint and eye distance
 * (about 2.2 x 2.6 eye distances, centre a quarter eye distance below the eyes). Worker thread only; buffers are
 * reused while the frame size stays.
 */
final class SoftwareFaces {
    private static final float MIN_CONFIDENCE = 0.3f;

    private final int maxFaces;
    private final FaceDetector.Face[] found;
    private final PointF mid = new PointF();
    private FaceDetector detector;
    private Bitmap bitmap;
    private short[] pixels;
    private ShortBuffer buffer;
    private int width, height;

    SoftwareFaces(int maxFaces) {
        this.maxFaces = maxFaces;
        found = new FaceDetector.Face[maxFaces];
    }

    /**
     * Detects faces on a luma frame (top row first, stride = frameW).
     *
     * @param rects  out: normalized view rectangles, 4 floats per face
     * @param scores out: 1..100
     * @return number of faces
     */
    int detect(byte[] luma, int frameW, int frameH, float[] rects, int[] scores) {
        int w = frameW & ~1; // FaceDetector needs an even width
        if (w < 32 || frameH < 32) return 0;
        if (w != width || frameH != height) {
            if (bitmap != null) bitmap.recycle();
            bitmap = Bitmap.createBitmap(w, frameH, Bitmap.Config.RGB_565);
            detector = new FaceDetector(w, frameH, maxFaces);
            pixels = new short[w * frameH];
            buffer = ShortBuffer.wrap(pixels);
            width = w;
            height = frameH;
        }
        for (int y = 0, k = 0; y < frameH; y++) {
            int row = y * frameW;
            for (int x = 0; x < w; x++) {
                int g = luma[row + x] & 0xFF;
                pixels[k++] = (short) (((g >> 3) << 11) | ((g >> 2) << 5) | (g >> 3));
            }
        }
        buffer.rewind();
        bitmap.copyPixelsFromBuffer(buffer);
        int n = detector.findFaces(bitmap, found);
        int out = 0;
        for (int i = 0; i < n && out < rects.length / 4; i++) {
            FaceDetector.Face f = found[i];
            if (f == null || f.confidence() < MIN_CONFIDENCE) continue;
            f.getMidPoint(mid);
            float e = f.eyesDistance();
            float cx = mid.x, cy = mid.y + 0.25f * e, hw = 1.1f * e, hh = 1.3f * e;
            rects[4 * out] = (cx - hw) / frameW;
            rects[4 * out + 1] = (cy - hh) / frameH;
            rects[4 * out + 2] = (cx + hw) / frameW;
            rects[4 * out + 3] = (cy + hh) / frameH;
            scores[out] = Math.max(1, Math.min(100, Math.round(f.confidence() * 100f)));
            out++;
        }
        return out;
    }

    void release() {
        if (bitmap != null) bitmap.recycle();
        bitmap = null;
        detector = null;
        width = height = 0;
    }
}

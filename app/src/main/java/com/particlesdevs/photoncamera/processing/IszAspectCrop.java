package com.particlesdevs.photoncamera.processing;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.particlesdevs.photoncamera.processing.heif.TenBitBitmaps;

public final class IszAspectCrop {
    private IszAspectCrop() { }

    public static Rect bounds(int width, int height) {
        int unit = Math.min(Math.max(width, height) / 4, Math.min(width, height) / 3) & ~1;
        if (unit < 2) throw new IllegalArgumentException("Image too small for even 4:3 crop");
        int w = width >= height ? 4 * unit : 3 * unit;
        int h = width >= height ? 3 * unit : 4 * unit;
        int x = (width - w) / 2, y = (height - h) / 2;
        return new Rect(x, y, x + w, y + h);
    }

    public static Bitmap crop(Bitmap image, Rect bounds) {
        if (bounds.left == 0 && bounds.top == 0 && bounds.width() == image.getWidth()
                && bounds.height() == image.getHeight()) return image;
        return TenBitBitmaps.crop(image, bounds.left, bounds.top, bounds.width(), bounds.height());
    }
}

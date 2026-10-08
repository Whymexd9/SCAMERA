package com.particlesdevs.photoncamera.capture;

import android.util.Size;

/**
 * Stream sizes for a camera whose session failed to configure with the usual ones. Camera2 guarantees a preview stream only up
 * to 1080p next to a maximum-size YUV stream; the vivo X200 Pro's front camera (owner's log 2026-10-08) refused a 2560x1920
 * preview next to its 3264x2448 YUV stream, which the back cameras accept.
 */
public final class SafeStreamSizes {
    /** The 1080p area Camera2's guaranteed stream combinations call PREVIEW. */
    public static final long PREVIEW_AREA = 1920L * 1080L;

    private SafeStreamSizes() {}

    /**
     * The largest of {@code sizes} with an area of at most {@code maxArea} and the aspect of {@code want} (1 % tolerance), else
     * the largest within the area of any aspect, else {@code want} itself (nothing smaller is listed).
     */
    public static Size capped(Size[] sizes, Size want, long maxArea) {
        if (sizes == null || want == null || want.getHeight() == 0) return want;
        final double aspect = (double) want.getWidth() / want.getHeight();
        Size sameAspect = null, any = null;
        for (Size s : sizes) {
            if (s == null || s.getHeight() == 0) continue;
            final long area = (long) s.getWidth() * s.getHeight();
            if (area > maxArea) continue;
            if (any == null || area > (long) any.getWidth() * any.getHeight()) any = s;
            final double a = (double) s.getWidth() / s.getHeight();
            if (Math.abs(a / aspect - 1.0) <= 0.01 && (sameAspect == null || area > (long) sameAspect.getWidth() * sameAspect.getHeight()))
                sameAspect = s;
        }
        return sameAspect != null ? sameAspect : any != null ? any : want;
    }
}

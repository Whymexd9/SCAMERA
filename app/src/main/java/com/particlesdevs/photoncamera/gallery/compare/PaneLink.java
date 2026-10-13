package com.particlesdevs.photoncamera.gallery.compare;

/**
 * How the two compare panes follow each other: through the photos, not through their pixels. A point of photo 2 is
 * {@code a * (point of photo 1) + b}, both in the photos' own (oriented) source pixels, and pane 2's scale is pane 1's
 * divided by {@code a}. So:
 * <ul>
 * <li>photos of a different resolution (12 MP next to 50 MP) keep the same zoom relative to the photo and the same place:
 * {@code a} is the ratio of their sizes, not 1;</li>
 * <li>panes at a different zoom - one magnified before the link, or a 2x photo next to a 1x one - keep the same scene point
 * in the middle while either pans or zooms: a pan of one pane moves the other through its own zoom.</li>
 * </ul>
 * Linked once, from the panes as they are shown at that moment ({@link #asShown}), or at the start from the field of view of
 * the two photos ({@link #byFieldOfView}); a pane clamped at its own fit or edge does not change the link.
 */
public final class PaneLink {
    /** Source pixels of photo 2 per source pixel of photo 1 for the same scene. */
    public final float a;
    public final float bx, by;

    private PaneLink(float a, float bx, float by) {
        this.a = a;
        this.bx = bx;
        this.by = by;
    }

    /**
     * The panes as they are shown: the centres show the same point, and the same scene detail is as large on screen in both
     * (a displacement of d screen pixels is d / s1 source pixels in photo 1 and d / s2 in photo 2).
     */
    public static PaneLink asShown(float s1, float c1x, float c1y, float s2, float c2x, float c2y) {
        float a = s1 > 0f && s2 > 0f ? s1 / s2 : 1f;
        return new PaneLink(a, c2x - a * c1x, c2y - a * c1y);
    }

    /**
     * From the photos' field of view (the 35 mm equivalent focal lengths of their EXIF, which refer to the frame diagonal):
     * the centres show the same point, and photo 2 magnifies the scene f2 / f1 times as much per diagonal as photo 1.
     */
    public static PaneLink byFieldOfView(float diag1, float c1x, float c1y, float f1,
                                         float diag2, float c2x, float c2y, float f2) {
        float a = diag1 > 0f && diag2 > 0f && f1 > 0f && f2 > 0f ? diag2 / diag1 * (f2 / f1) : 1f;
        return new PaneLink(a, c2x - a * c1x, c2y - a * c1y);
    }

    public float scale2(float scale1) {
        return scale1 / a;
    }

    public float scale1(float scale2) {
        return scale2 * a;
    }

    public float x2(float x1) {
        return a * x1 + bx;
    }

    public float y2(float y1) {
        return a * y1 + by;
    }

    public float x1(float x2) {
        return (x2 - bx) / a;
    }

    public float y1(float y2) {
        return (y2 - by) / a;
    }
}

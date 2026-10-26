package com.particlesdevs.photoncamera.gallery.editor;

/**
 * P351: the editor's geometry, pure Java (JVM tests). Frames:
 * <ul>
 *   <li>source: the texture as decoded (stored orientation for a JPEG / DNG read by the app, already turned for a photo the
 *       platform decoder turned); {@link #orientationToDisplay} maps its uv to the oriented photo;</li>
 *   <li>oriented: the photo as the gallery shows it, W x H;</li>
 *   <li>base: the oriented photo turned by quarterTurns x 90 degrees clockwise and mirrored when flipH, Wb x Hb (the crop rect is
 *       axis-aligned in it, 0..1);</li>
 *   <li>output: the crop, 0..1, after the straighten rotation about the base frame's centre.</li>
 * </ul>
 * An output pixel maps back through crop -> straighten (inverse) -> unflip -> unturn -> oriented -> source; all of it affine.
 */
public final class CropMath {
    private CropMath() {}

    /** x' = a x + b y + c, y' = d x + e y + f. */
    public static final class Affine {
        public final double a, b, c, d, e, f;

        public Affine(double a, double b, double c, double d, double e, double f) {
            this.a = a; this.b = b; this.c = c; this.d = d; this.e = e; this.f = f;
        }

        public static final Affine IDENTITY = new Affine(1, 0, 0, 0, 1, 0);

        public static Affine scale(double sx, double sy) { return new Affine(sx, 0, 0, 0, sy, 0); }

        public static Affine translate(double tx, double ty) { return new Affine(1, 0, tx, 0, 1, ty); }

        /** This one first, then {@code next}. */
        public Affine then(Affine n) {
            return new Affine(n.a * a + n.b * d, n.a * b + n.b * e, n.a * c + n.b * f + n.c,
                    n.d * a + n.e * d, n.d * b + n.e * e, n.d * c + n.e * f + n.f);
        }

        public Affine inverse() {
            double det = a * e - b * d;
            if (Math.abs(det) < 1e-300) throw new ArithmeticException("singular affine");
            double ia = e / det, ib = -b / det, id = -d / det, ie = a / det;
            return new Affine(ia, ib, -(ia * c + ib * f), id, ie, -(id * c + ie * f));
        }

        public double[] apply(double x, double y) {
            return new double[]{a * x + b * y + c, d * x + e * y + f};
        }

        /** Column-major mat3 for glUniformMatrix3fv (transpose false). */
        public float[] mat3() {
            return new float[]{(float) a, (float) d, 0f, (float) b, (float) e, 0f, (float) c, (float) f, 1f};
        }
    }

    // ---------------------------------------------------------------- orientation

    /** EXIF / TIFF orientation 1..8: source uv -> displayed (oriented) uv. */
    public static Affine orientationToDisplay(int orientation) {
        switch (orientation) {
            case 2: return new Affine(-1, 0, 1, 0, 1, 0);       // mirror
            case 3: return new Affine(-1, 0, 1, 0, -1, 1);      // 180
            case 4: return new Affine(1, 0, 0, 0, -1, 1);       // flip
            case 5: return new Affine(0, 1, 0, 1, 0, 0);        // transpose
            case 6: return new Affine(0, -1, 1, 1, 0, 0);       // 90 clockwise: (1 - v, u)
            case 7: return new Affine(0, -1, 1, -1, 0, 1);      // transverse
            case 8: return new Affine(0, 1, 0, -1, 0, 1);       // 270 clockwise: (v, 1 - u)
            default: return Affine.IDENTITY;
        }
    }

    public static boolean swapsSides(int orientation) {
        return orientation >= 5 && orientation <= 8;
    }

    // ---------------------------------------------------------------- frames

    /** {Wb, Hb}: the oriented size after the quarter turns. */
    public static double[] baseSize(double w, double h, int quarterTurns) {
        return (quarterTurns & 1) == 1 ? new double[]{h, w} : new double[]{w, h};
    }

    /** Base uv (after turn + flip, before straighten) -> oriented uv. */
    static Affine baseToOrientedUnrotated(int quarterTurns, boolean flipH) {
        Affine m = flipH ? new Affine(-1, 0, 1, 0, 1, 0) : Affine.IDENTITY;
        Affine turn;
        switch (((quarterTurns % 4) + 4) % 4) {
            case 1: turn = new Affine(0, 1, 0, -1, 0, 1); break;   // oriented = (v, 1 - u)
            case 2: turn = new Affine(-1, 0, 1, 0, -1, 1); break;  // (1 - u, 1 - v)
            case 3: turn = new Affine(0, -1, 1, 1, 0, 0); break;   // (1 - v, u)
            default: turn = Affine.IDENTITY;
        }
        return m.then(turn);
    }

    /** Displayed base uv -> image base uv: the inverse straighten rotation about the centre, in pixel proportions. */
    static Affine unstraighten(double wb, double hb, double degrees) {
        double t = Math.toRadians(degrees), c = Math.cos(t), s = Math.sin(t);
        // centre in pixels, rotate by -t: X' = c X + s Y, Y' = -s X + c Y
        Affine toPx = Affine.translate(-0.5, -0.5).then(Affine.scale(wb, hb));
        Affine rot = new Affine(c, s, 0, -s, c, 0);
        Affine back = Affine.scale(1 / wb, 1 / hb).then(Affine.translate(0.5, 0.5));
        return toPx.then(rot).then(back);
    }

    /** Displayed base uv (the frame the crop lives in) -> oriented uv, for an oriented photo of w x h. */
    public static Affine baseToOriented(EditState s, double w, double h) {
        double[] b = baseSize(w, h, s.quarterTurns);
        return unstraighten(b[0], b[1], s.straighten).then(baseToOrientedUnrotated(s.quarterTurns, s.flipH));
    }

    /** Output (crop) uv -> displayed base uv. */
    public static Affine cropToBase(EditState s) {
        return new Affine(s.cropR - s.cropL, 0, s.cropL, 0, s.cropB - s.cropT, s.cropT);
    }

    /** Output uv -> oriented uv. */
    public static Affine outputToOriented(EditState s, double w, double h) {
        return cropToBase(s).then(baseToOriented(s, w, h));
    }

    /** The crop in pixels of the oriented w x h photo: {width, height}. */
    public static double[] cropPixels(EditState s, double w, double h) {
        double[] b = baseSize(w, h, s.quarterTurns);
        return new double[]{(s.cropR - s.cropL) * b[0], (s.cropB - s.cropT) * b[1]};
    }

    // ---------------------------------------------------------------- fitting

    /**
     * The largest k <= any such that the rect centred at (cx, cy) (base pixels, from the centre) with half sizes k*a0 x k*b0
     * stays inside a wb x hb photo rotated by {@code degrees}: |c Cx + s Cy| + k(a0|c| + b0|s|) <= wb/2 and
     * |-s Cx + c Cy| + k(a0|s| + b0|c|) <= hb/2.
     */
    public static double maxScale(double cx, double cy, double a0, double b0, double wb, double hb, double degrees) {
        double t = Math.toRadians(degrees), c = Math.cos(t), s = Math.sin(t);
        double ac = Math.abs(c), as = Math.abs(s);
        double k1 = (wb / 2 - Math.abs(c * cx + s * cy)) / Math.max(1e-12, a0 * ac + b0 * as);
        double k2 = (hb / 2 - Math.abs(-s * cx + c * cy)) / Math.max(1e-12, a0 * as + b0 * ac);
        return Math.min(k1, k2);
    }

    /** Whether the crop rect l,t,r,b (base uv) lies inside the straightened photo (with a hair of tolerance). */
    public static boolean fits(double l, double t, double r, double b, double wb, double hb, double degrees) {
        double cx = ((l + r) / 2 - 0.5) * wb, cy = ((t + b) / 2 - 0.5) * hb;
        double a0 = (r - l) / 2 * wb, b0 = (b - t) / 2 * hb;
        return l >= -1e-6 && t >= -1e-6 && r <= 1 + 1e-6 && b <= 1 + 1e-6 && maxScale(cx, cy, a0, b0, wb, hb, degrees) >= 1 - 1e-6;
    }

    /**
     * The crop shrunk about its own centre until it fits the straightened photo (automatic inset: no empty corners); when its
     * centre is outside the photo (or the shrink leaves less than half of what the photo's centre allows) it moves to the
     * centre. Returns {l, t, r, b}.
     */
    public static float[] inset(float l, float t, float r, float b, double wb, double hb, double degrees) {
        double cx = ((l + r) / 2.0 - 0.5) * wb, cy = ((t + b) / 2.0 - 0.5) * hb;
        double a0 = (r - l) / 2.0 * wb, b0 = (b - t) / 2.0 * hb;
        double k = maxScale(cx, cy, a0, b0, wb, hb, degrees);
        if (k >= 1) return clampRect(l, t, r, b);
        double k0 = maxScale(0, 0, a0, b0, wb, hb, degrees);
        if (k < 0.5 * Math.min(1, k0)) {
            cx = 0;
            cy = 0;
            k = k0;
        }
        k = Math.min(1, Math.max(1e-4, k)) * (1 - 1e-6);
        double hw = a0 * k / wb, hh = b0 * k / hb;
        double ux = cx / wb + 0.5, uy = cy / hb + 0.5;
        return clampRect((float) (ux - hw), (float) (uy - hh), (float) (ux + hw), (float) (uy + hh));
    }

    static float[] clampRect(float l, float t, float r, float b) {
        return new float[]{Math.max(0f, l), Math.max(0f, t), Math.min(1f, r), Math.min(1f, b)};
    }

    /** Applies {@link #inset} to the state's crop (in place). */
    public static void insetState(EditState s, double w, double h) {
        double[] bs = baseSize(w, h, s.quarterTurns);
        float[] c = inset(s.cropL, s.cropT, s.cropR, s.cropB, bs[0], bs[1], s.straighten);
        s.cropL = c[0]; s.cropT = c[1]; s.cropR = c[2]; s.cropB = c[3];
    }

    // ---------------------------------------------------------------- aspect presets

    /** The crop presets in the panel's order: free, the photo's own, 1:1, 4:5, 3:4, 4:3, 16:9, 9:16 (width / height). */
    public static final float[] PRESETS = {EditState.ASPECT_FREE, EditState.ASPECT_ORIGINAL, 1f, 4f / 5f, 3f / 4f, 4f / 3f, 16f / 9f, 9f / 16f};

    /** The labels of {@link #PRESETS} after the first two («Свободный», «Оригинал» are words: EditPanels). */
    public static final String[] PRESET_RATIOS = {"", "", "1:1", "4:5", "3:4", "4:3", "16:9", "9:16"};

    /** The pixel width / height a preset asks for in a base frame of wb x hb; 0 for free. */
    public static double ratioOf(float aspect, double wb, double hb) {
        if (aspect == EditState.ASPECT_ORIGINAL) return wb / hb;
        return aspect > 0 ? aspect : 0;
    }

    /** The preset index matching {@code aspect}, -1 for none (a turned 4:5 is 5:4). */
    public static int presetIndex(float aspect) {
        for (int i = 0; i < PRESETS.length; i++) if (Math.abs(PRESETS[i] - aspect) < 1e-4f) return i;
        return -1;
    }

    /**
     * The largest crop of pixel ratio {@code ratio} (w/h) about the current crop's centre that fits the straightened photo
     * (the photo's centre when that gives a clearly larger one). Ratio 0 leaves the crop as it is.
     */
    public static void applyAspect(EditState s, double w, double h) {
        double[] bs = baseSize(w, h, s.quarterTurns);
        double ratio = ratioOf(s.aspect, bs[0], bs[1]);
        if (ratio <= 0) return;
        double wb = bs[0], hb = bs[1];
        double a0, b0; // half sizes in pixels of the largest such rect inside wb x hb
        if (wb / hb > ratio) {
            b0 = hb / 2;
            a0 = b0 * ratio;
        } else {
            a0 = wb / 2;
            b0 = a0 / ratio;
        }
        double cx = ((s.cropL + s.cropR) / 2.0 - 0.5) * wb, cy = ((s.cropT + s.cropB) / 2.0 - 0.5) * hb;
        double k = Math.min(1, maxScale(cx, cy, a0, b0, wb, hb, s.straighten));
        double k0 = Math.min(1, maxScale(0, 0, a0, b0, wb, hb, s.straighten));
        if (k < 0.8 * k0) {
            cx = 0;
            cy = 0;
            k = k0;
        }
        k = Math.max(1e-4, k) * (1 - 1e-6);
        double hw = a0 * k / wb, hh = b0 * k / hb, ux = cx / wb + 0.5, uy = cy / hb + 0.5;
        float[] c = clampRect((float) (ux - hw), (float) (uy - hh), (float) (ux + hw), (float) (uy + hh));
        s.cropL = c[0]; s.cropT = c[1]; s.cropR = c[2]; s.cropB = c[3];
    }

    // ---------------------------------------------------------------- turn / flip

    /**
     * Turns the whole result 90 degrees clockwise: the crop rect turns with it, the straighten angle stays (rotations about the
     * centre commute), a ratio preset swaps its sides. With the mirror on, the turn before the mirror goes the other way.
     */
    public static void rotateClockwise(EditState s) {
        float l = s.cropL, t = s.cropT, r = s.cropR, b = s.cropB;
        s.cropL = 1 - b;
        s.cropT = l;
        s.cropR = 1 - t;
        s.cropB = r;
        s.quarterTurns = (s.quarterTurns + (s.flipH ? 3 : 1)) % 4;
        if (s.aspect > 0) s.aspect = 1f / s.aspect;
    }

    /** Mirrors the whole result left-right: the crop mirrors, the straighten angle changes sign. */
    public static void flipHorizontal(EditState s) {
        float l = s.cropL, r = s.cropR;
        s.cropL = 1 - r;
        s.cropR = 1 - l;
        s.flipH = !s.flipH;
        s.straighten = -s.straighten;
    }

    // ---------------------------------------------------------------- dragging

    /**
     * {@code to} moved back toward {@code from} (which fits) until it fits as well: a binary search on the move, so a drag
     * that would leave the photo stops at its edge.
     */
    public static float[] constrain(float[] from, float[] to, double wb, double hb, double degrees) {
        if (fits(to[0], to[1], to[2], to[3], wb, hb, degrees)) return to;
        if (!fits(from[0], from[1], from[2], from[3], wb, hb, degrees)) return from;
        double lo = 0, hi = 1;
        for (int i = 0; i < 24; i++) {
            double m = (lo + hi) / 2;
            if (fits(lerp(from[0], to[0], m), lerp(from[1], to[1], m), lerp(from[2], to[2], m), lerp(from[3], to[3], m), wb, hb, degrees))
                lo = m;
            else hi = m;
        }
        return new float[]{(float) lerp(from[0], to[0], lo), (float) lerp(from[1], to[1], lo), (float) lerp(from[2], to[2], lo),
                (float) lerp(from[3], to[3], lo)};
    }

    static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}

package com.particlesdevs.photoncamera.gallery.editor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.Locale;

/**
 * P367 (owner, 2026-10-27: «В редакторе фото дай возможность кропать фото, при этом оно должно быть в полном разрешении» -
 * «Я имею ввиду кропать предпосмотр во время редактирования»): the editor's view zoom, pure Java (JVM tests). The picture is
 * pinched / double-tapped from the fit up to {@link #MAX_FULL} x the original's pixels and panned; the zoom is a view of the
 * displayed crop in its own frame - the saved copy (EditState) does not change.
 *
 * <p>Model: {@link #scale} view px per original px (1.0 = 100 %: one original pixel on one screen pixel), the displayed crop's
 * top-left corner ({@link #left}, {@link #top}) in view px. The fit is the crop as the editor shows it unzoomed
 * (EditorView.canvas); on an axis where the zoomed picture is narrower than the view it is centred, where it is wider it never
 * leaves a gap at its edges.
 *
 * <p>Full resolution: past the preview decode's resolution ({@link #fullResWanted}) the visible part is rendered from the
 * original through the export's reader (EditSource.Reader) and the export's EditRenderer math: {@link #viewToSource} is
 * EditExporter's output -> source mapping ({@link EditExporter#plan}) with the view in place of the output tile, {@link #region}
 * its tile region (overlap, even CFA alignment), {@link #cacheRegion} the visible area plus a margin bounded by {@link #cap},
 * {@link #covers} the cache test, {@link #level} the decode's reader px a texel (1 at 100 % and above, the screen's resolution
 * below it; coarser only past {@link #ceiling}), {@link #bin} a RAW region's same-colour binning. «100 %» is the copy's own
 * 1:1 when the copy has the crop's pixels ({@link #copySize}, {@link #placement}: its pixels on the screen's, no half-pixel
 * blur, the same samples «Сохранить копию» takes).
 */
final class EditZoom {
    /** The largest magnification: 400 % of the original's pixels (a fit already larger is not zoomed further). */
    static final double MAX_FULL = 4.0;
    /** A scale this close above the fit counts as the fit (no zoomed view, no pan); the model keeps it, so a slow pinch adds up. */
    static final double FIT_EPS = 1e-3;
    /** Full resolution is wanted once a preview pixel covers more than this many screen pixels. */
    static final double PREVIEW_LIMIT = 1.02;
    /** A cached region still serves while its texels are at most this much coarser than the screen wants. */
    static final double LEVEL_SLACK = 1.3;
    /** A region grows (its margin to pan in) up to 2x the view's pixels, never more than this (GPU memory of an EditRenderer chain). */
    static final double CAP_MAX = 4_000_000;
    /**
     * The needed box itself (the visible area's source box + overlap: up to ~2.2x the view's pixels for a straightened crop) is
     * decoded at the screen's level up to this many views' pixels and {@link #NEED_MAX} texels; only past that it gets coarser.
     */
    static final double NEED_VIEWS = 3.0, NEED_MAX = 12_000_000;
    /** The cached region grows around the visible area by at most this much on each side. */
    static final double MAX_GROW = 3.0;

    private double viewW = 1, viewH = 1;
    private double fitL, fitT, fitW = 1, fitH = 1;
    /** The displayed crop in the original's pixels. */
    private double cropW = 1, cropH = 1;
    /** The copy's size (output px, EditExporter.outputSize; 0: unknown) and whether it is the crop's own pixels (rounded). */
    private int copyW, copyH;
    private boolean copyGrid;
    /** View px per original px; the displayed crop's top-left corner in the view. */
    double scale = 1, left, top;

    /**
     * A new frame: the view, the fit rect of the crop in it, the crop in original pixels. With {@code keep} a zoomed view keeps
     * its scale and the crop point at the view's centre (a resized view); else it goes back to the fit.
     */
    void frame(double viewW, double viewH, double fitL, double fitT, double fitW, double fitH, double cropW, double cropH, boolean keep) {
        boolean was = keep && isZoomed();
        double cu = 0.5, cv = 0.5, s = scale;
        if (was) {
            cu = (this.viewW / 2 - left) / displayedW();
            cv = (this.viewH / 2 - top) / displayedH();
        }
        this.viewW = Math.max(1, viewW);
        this.viewH = Math.max(1, viewH);
        this.fitL = fitL;
        this.fitT = fitT;
        this.fitW = Math.max(1e-6, fitW);
        this.fitH = Math.max(1e-6, fitH);
        this.cropW = Math.max(1e-6, cropW);
        this.cropH = Math.max(1e-6, cropH);
        if (was) {
            scale = clampScale(s);
            left = this.viewW / 2 - cu * displayedW();
            top = this.viewH / 2 - cv * displayedH();
            clamp();
        } else reset();
    }

    /**
     * The copy «Сохранить копию» writes of this crop: {@code w x h} output px. When that is the crop's own pixels (within the
     * rounding), «100 %» ({@link #hundred}) is the copy's 1:1 - one output pixel on one screen pixel - else 1:1 of the original.
     */
    void copySize(int w, int h) {
        copyW = w;
        copyH = h;
        copyGrid = w > 0 && h > 0 && Math.abs(w - cropW) <= 1 && Math.abs(h - cropH) <= 1;
    }

    /** The scale of «100 %»: the copy's 1:1 when it has the crop's pixels ({@link #copySize}), else one original px a screen px. */
    double hundred() {
        return copyGrid ? copyW / cropW : 1.0;
    }

    /** The copy's size as {@link #copySize} set it (0 when unknown). */
    int copyW() { return copyW; }

    int copyH() { return copyH; }

    double viewW() { return viewW; }

    double viewH() { return viewH; }

    double fitScale() {
        return fitW / cropW;
    }

    double maxScale() {
        return Math.max(fitScale(), MAX_FULL);
    }

    boolean isZoomed() {
        return scale > fitScale() * (1 + FIT_EPS);
    }

    double displayedW() {
        return cropW * scale;
    }

    double displayedH() {
        return cropH * scale;
    }

    /** The zoom in % of the original's pixels. */
    double percent() {
        return scale * 100;
    }

    double clampScale(double s) {
        return Math.min(maxScale(), Math.max(fitScale(), s));
    }

    void reset() {
        scale = fitScale();
        left = fitL;
        top = fitT;
    }

    /** Raw values (an animation between two clamped states). */
    void set(double scale, double left, double top) {
        this.scale = scale;
        this.left = left;
        this.top = top;
    }

    EditZoom copy() {
        EditZoom z = new EditZoom();
        z.viewW = viewW;
        z.viewH = viewH;
        z.fitL = fitL;
        z.fitT = fitT;
        z.fitW = fitW;
        z.fitH = fitH;
        z.cropW = cropW;
        z.cropH = cropH;
        z.copyW = copyW;
        z.copyH = copyH;
        z.copyGrid = copyGrid;
        z.scale = scale;
        z.left = left;
        z.top = top;
        return z;
    }

    /** Pinch: the scale times {@code factor} (in range), the crop point under (fx, fy) stays there; false when nothing moved. */
    boolean zoomAbout(double factor, double fx, double fy) {
        if (!(factor > 0) || Double.isInfinite(factor)) return false;
        double s2 = clampScale(scale * factor);
        if (s2 == scale) return false;
        left = fx - (fx - left) * s2 / scale;
        top = fy - (fy - top) * s2 / scale;
        scale = s2;
        clamp();
        return true;
    }

    /** One finger (or the fingers' centre) moved by dx, dy view px. */
    void panBy(double dx, double dy) {
        if (!isZoomed()) return;
        left += dx;
        top += dy;
        clamp();
    }

    /** Scale {@code s}, the crop uv (cu, cv) at the view's centre (clamped): the test hook. */
    void zoomTo(double s, double cu, double cv) {
        scale = clampScale(s);
        left = viewW / 2 - clamp01(cu) * displayedW();
        top = viewH / 2 - clamp01(cv) * displayedH();
        clamp();
    }

    /** Scale {@code s}, the crop point under (fx, fy) moved to the view's centre (clamped). */
    void centreOn(double s, double fx, double fy) {
        double u = clamp01((fx - left) / displayedW()), v = clamp01((fy - top) / displayedH());
        zoomTo(s, u, v);
    }

    /** Where a double tap at (fx, fy) goes: zoomed -> the fit; at the fit -> 100 % (2x the fit for a small photo) there. */
    double[] toggleTarget(double fx, double fy) {
        EditZoom t = copy();
        if (isZoomed()) t.reset();
        else {
            double h = hundred();
            double target = h > fitScale() * 1.05 ? h : Math.min(maxScale(), fitScale() * 2);
            if (target > fitScale() * (1 + FIT_EPS)) t.centreOn(target, fx, fy);
            else t.reset();
        }
        return new double[]{t.scale, t.left, t.top};
    }

    /**
     * Centred where the picture is narrower than the view, no gap at the edges where it is wider; at (or under) the fit exactly
     * the fit. Only there: a scale a hair above the fit stays (a slow pinch's small factors add up instead of snapping back).
     */
    void clamp() {
        if (scale <= fitScale() * (1 + 1e-12)) {
            reset();
            return;
        }
        double dw = displayedW(), dh = displayedH();
        left = dw <= viewW ? (viewW - dw) / 2 : Math.min(0, Math.max(viewW - dw, left));
        top = dh <= viewH ? (viewH - dh) / 2 : Math.min(0, Math.max(viewH - dh, top));
    }

    /** View px -> crop uv. */
    CropMath.Affine viewToCrop() {
        return CropMath.Affine.translate(-left, -top).then(CropMath.Affine.scale(1.0 / displayedW(), 1.0 / displayedH()));
    }

    /**
     * Where the displayed crop goes on the screen, {left, top, dw, dh} (what EditorView draws, the preview and the full-resolution
     * region alike): the model's - except at «100 %» of a copy with the crop's pixels ({@link #copySize}), where it is exactly the
     * copy's W x H with its corner on whole view px, at the model's place along its pan range (an edge stays the edge): every
     * screen pixel centre is then an output pixel centre of the copy, sampled as the export samples it - no half-pixel blur, no
     * shift between the two paths.
     */
    double[] placement() {
        if (!copyGrid || Math.abs(scale - hundred()) > 1e-9 * scale) return new double[]{left, top, displayedW(), displayedH()};
        return new double[]{snap(left, viewW, displayedW(), copyW), snap(top, viewH, displayedH(), copyH), copyW, copyH};
    }

    /** One axis of {@link #placement}: centred when the copy fits the view, else the model's share of its pan range, whole px. */
    private static double snap(double pos, double view, double model, int copy) {
        if (copy <= view) return Math.rint((view - copy) / 2);
        double travel = view - model, f = travel < -1e-9 ? clamp01(pos / travel) : 0;
        return Math.rint(f * (view - copy));
    }

    /** The view's rect in crop uv, cut to the crop: {u0, v0, u1, v1}. */
    double[] visibleCrop() {
        double dw = displayedW(), dh = displayedH();
        return new double[]{clamp01(-left / dw), clamp01(-top / dh), clamp01((viewW - left) / dw), clamp01((viewH - top) / dh)};
    }

    // ---------------------------------------------------------------- full resolution

    /** Whether the screen shows more than the preview decode has: the preview has fewer pixels and one covers > ~1 screen px. */
    static boolean fullResWanted(double scale, double previewPerOriginal) {
        return previewPerOriginal < 0.98 && scale / Math.max(1e-9, previewPerOriginal) > PREVIEW_LIMIT;
    }

    /**
     * View px -> reader px, EditExporter's output -> source chain with the view in place of the output: the displayed crop
     * ({@code left, top, dw x dh} in the view) of state {@code s} -> crop uv -> base uv -> oriented uv -> source uv -> px of a
     * reader picture {@code sw x sh} stored with EXIF {@code orientation}.
     */
    static CropMath.Affine viewToSource(EditState s, double left, double top, double dw, double dh, int orientation, int sw, int sh) {
        boolean swap = CropMath.swapsSides(orientation);
        double ow = swap ? sh : sw, oh = swap ? sw : sh;
        return CropMath.Affine.translate(-left, -top).then(CropMath.Affine.scale(1.0 / dw, 1.0 / dh)).then(CropMath.cropToBase(s))
                .then(CropMath.baseToOriented(s, ow, oh)).then(CropMath.orientationToDisplay(orientation).inverse())
                .then(CropMath.Affine.scale(sw, sh));
    }

    /** The part of the view the displayed crop takes: {l, t, r, b} in view px; null when it is off the view. */
    static double[] visibleView(double viewW, double viewH, double left, double top, double dw, double dh) {
        double l = Math.max(0, left), t = Math.max(0, top), r = Math.min(viewW, left + dw), b = Math.min(viewH, top + dh);
        return r - l > 1e-6 && b - t > 1e-6 ? new double[]{l, t, r, b} : null;
    }

    /** The bounding box {x0, y0, x1, y1} of the rect {l, t, r, b} mapped by {@code m}. */
    static double[] box(CropMath.Affine m, double[] r) {
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for (double[] p : new double[][]{{r[0], r[1]}, {r[2], r[1]}, {r[0], r[3]}, {r[2], r[3]}}) {
            double[] q = m.apply(p[0], p[1]);
            x0 = Math.min(x0, q[0]);
            y0 = Math.min(y0, q[1]);
            x1 = Math.max(x1, q[0]);
            y1 = Math.max(y1, q[1]);
        }
        return new double[]{x0, y0, x1, y1};
    }

    /** {@code b} grown by {@code o} on every side (the tile overlap: NR, the Gaussians, the demosaic). */
    static double[] grow(double[] b, double o) {
        return new double[]{b[0] - o, b[1] - o, b[2] + o, b[3] + o};
    }

    /** Reader px a texel for {@code viewPerSource} view px per reader px: 1 at 100 % and above, the screen's resolution below. */
    static double idealLevel(double viewPerSource) {
        return Math.max(1.0, 1.0 / Math.max(1e-9, viewPerSource));
    }

    /**
     * The decode level for the needed box {@code need} (reader px): {@link #idealLevel}, raised only so the box stays within
     * {@code ceiling} texels ({@link #ceiling}: the needed box alone, never the margin - 100 % stays 1:1 for a straightened
     * crop too); a whole number for a RAW ({@code integer}: same-colour binning), rounded down unless that breaks the ceiling.
     */
    static double level(double viewPerSource, boolean integer, double[] need, double ceiling) {
        double area = Math.max(1, (need[2] - need[0]) * (need[3] - need[1]));
        double ideal = idealLevel(viewPerSource);
        if (!integer) return Math.max(ideal, Math.sqrt(area / ceiling));
        int l = Math.max(1, (int) Math.floor(ideal + 1e-9));
        while (area / ((double) l * l) > ceiling && l < 64) l++;
        return l;
    }

    /** Whether a region decoded at {@code regionLevel} is sharp enough for {@code viewPerSource}. */
    static boolean adequate(double regionLevel, double viewPerSource) {
        return regionLevel <= idealLevel(viewPerSource) * LEVEL_SLACK + 1e-6;
    }

    /**
     * Whether a region decoded at {@code regionLevel} is as sharp as this view gets: {@link #adequate}, or no coarser than the
     * {@link #level} a decode for it would take (a needed box past the ceiling: the best there is, not decoded again).
     */
    static boolean fineEnough(double regionLevel, double viewPerSource, boolean integer, double[] need, double ceiling) {
        return adequate(regionLevel, viewPerSource) || regionLevel <= level(viewPerSource, integer, need, ceiling) + 1e-6;
    }

    /** Texels a region may grow to for a view of {@code viewW x viewH}: 2x its pixels, at most {@link #CAP_MAX} (at least 1.25x). */
    static double cap(double viewW, double viewH) {
        double px = Math.max(1, viewW * viewH);
        return Math.max(px * 1.25, Math.min(2 * px, CAP_MAX));
    }

    /**
     * Texels the needed box may have at the screen's level before it gets coarser: {@link #NEED_VIEWS} x the view's pixels (the
     * box of a view turned 45 degrees is ~2.2x), at most {@link #NEED_MAX}, never under {@link #cap}.
     */
    static double ceiling(double viewW, double viewH) {
        double px = Math.max(1, viewW * viewH);
        return Math.max(cap(viewW, viewH), Math.min(NEED_VIEWS * px, NEED_MAX));
    }

    /**
     * The region {x, y, w, h} (reader px) to decode for the needed box {@code need}: grown about it toward {@code cap} texels at
     * {@code level} (at most {@link #MAX_GROW} x on each side: room to pan before the next decode), moved back inside the
     * {@code sw x sh} picture, cut to it, starting on even pixels for a CFA.
     */
    static int[] cacheRegion(double[] need, double level, double cap, int sw, int sh, boolean even) {
        double x0 = need[0], y0 = need[1], x1 = need[2], y1 = need[3];
        double w = Math.max(1, x1 - x0), h = Math.max(1, y1 - y0);
        double g = Math.min(MAX_GROW, Math.sqrt(cap * level * level / (w * h)));
        if (g > 1) {
            double cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
            w *= g;
            h *= g;
            x0 = cx - w / 2;
            x1 = cx + w / 2;
            y0 = cy - h / 2;
            y1 = cy + h / 2;
        }
        double[] xs = inside(x0, x1, sw), ys = inside(y0, y1, sh);
        int ix0 = Math.max(0, (int) Math.floor(xs[0])), iy0 = Math.max(0, (int) Math.floor(ys[0]));
        int ix1 = Math.min(sw, (int) Math.ceil(xs[1])), iy1 = Math.min(sh, (int) Math.ceil(ys[1]));
        if (even) {
            ix0 &= ~1;
            iy0 &= ~1;
        }
        ix1 = Math.max(ix1, Math.min(sw, ix0 + 2));
        iy1 = Math.max(iy1, Math.min(sh, iy0 + 2));
        return new int[]{ix0, iy0, ix1 - ix0, iy1 - iy0};
    }

    /** The span a..b moved back into 0..size (cut when it is longer). */
    private static double[] inside(double a, double b, double size) {
        if (b - a >= size) return new double[]{0, size};
        if (a < 0) {
            b -= a;
            a = 0;
        }
        if (b > size) {
            a -= b - size;
            b = size;
        }
        return new double[]{Math.max(0, a), Math.min(size, b)};
    }

    /**
     * Whether {@code region} {x, y, w, h} holds the needed box {@code need} (cut to the {@code sw x sh} picture); a far region
     * edge (right, bottom) within {@code slack} px of the picture's edge counts as that edge (a binned RAW region loses its last
     * samples there: RawReader trims only x + w, y + h). The near edges get no slack: a region starting inside the picture does
     * not hold its first columns / rows.
     */
    static boolean covers(int[] region, double[] need, int sw, int sh, double slack) {
        double nx0 = Math.max(0, need[0]), ny0 = Math.max(0, need[1]), nx1 = Math.min(sw, need[2]), ny1 = Math.min(sh, need[3]);
        if (nx1 <= nx0 || ny1 <= ny0) return false;
        double rx0 = region[0], ry0 = region[1], rx1 = region[0] + region[2], ry1 = region[1] + region[3];
        if (rx1 >= sw - slack) rx1 = Math.max(rx1, sw);
        if (ry1 >= sh - slack) ry1 = Math.max(ry1, sh);
        return rx0 <= nx0 + 1e-6 && ry0 <= ny0 + 1e-6 && rx1 >= nx1 - 1e-6 && ry1 >= ny1 - 1e-6;
    }

    /** The source rectangle {x, y, w, h} an output tile needs: its corners mapped back, the overlap around, inside the source. */
    static int[] region(CropMath.Affine outToSrcPx, int ox, int oy, int tw, int th, int overlap, int sw, int sh, boolean even) {
        double[] b = box(outToSrcPx, new double[]{ox, oy, ox + tw, oy + th});
        int x0 = Math.max(0, (int) Math.floor(b[0]) - overlap), y0 = Math.max(0, (int) Math.floor(b[1]) - overlap);
        int x1 = Math.min(sw, (int) Math.ceil(b[2]) + overlap), y1 = Math.min(sh, (int) Math.ceil(b[3]) + overlap);
        if (even) {
            x0 &= ~1;
            y0 &= ~1;
        }
        x1 = Math.max(x1, Math.min(sw, x0 + 2));
        y1 = Math.max(y1, Math.min(sh, y0 + 2));
        return new int[]{x0, y0, x1 - x0, y1 - y0};
    }

    /**
     * {@code ow x oh} output samples binned {@code l x l} from 16-bit samples ({@code stride} wide, {@code channels} 1 or 3) at
     * (x, y): a CFA ({@code cfa}, ow / oh even) averages same-colour samples 2 apart, so the 2x2 phase of an even (x, y) holds -
     * DngRaw's binning on a region; RGB averages l x l boxes. {@code l} 1 is a copy. A new direct buffer; {@code src}'s position
     * is not touched.
     */
    static ShortBuffer bin(ShortBuffer src, int stride, int channels, int x, int y, int ow, int oh, int l, boolean cfa) {
        ShortBuffer out = ByteBuffer.allocateDirect(ow * oh * channels * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        ShortBuffer in = src.duplicate();
        int span = ow * l * channels;
        short[] row = new short[span];
        int[] acc = new int[ow * channels];
        short[] o = new short[ow * channels];
        int n = l * l;
        for (int oy = 0; oy < oh; oy++) {
            Arrays.fill(acc, 0);
            for (int j = 0; j < l; j++) {
                int sy = cfa ? y + 2 * ((oy >> 1) * l + j) + (oy & 1) : y + oy * l + j;
                in.position((sy * stride + x) * channels);
                in.get(row, 0, span);
                if (cfa) {
                    for (int ox = 0; ox < ow; ox++) {
                        int base = 2 * (ox >> 1) * l + (ox & 1), s = 0;
                        for (int i = 0; i < l; i++) s += row[base + 2 * i] & 0xFFFF;
                        acc[ox] += s;
                    }
                } else {
                    for (int ox = 0; ox < ow; ox++)
                        for (int ch = 0; ch < channels; ch++) {
                            int s = 0;
                            for (int i = 0; i < l; i++) s += row[(ox * l + i) * channels + ch] & 0xFFFF;
                            acc[ox * channels + ch] += s;
                        }
                }
            }
            for (int k = 0; k < acc.length; k++) o[k] = (short) ((acc[k] + n / 2) / n);
            out.put(o);
        }
        out.rewind();
        return out;
    }

    /** The geometry the zoom lives in (turns, flip, straighten, crop): a change resets the zoom, a region keeps it as its key. */
    static String geometryKey(EditState s) {
        return String.format(Locale.ROOT, "%d/%b/%s/%s,%s,%s,%s", s.quarterTurns, s.flipH, s.straighten, s.cropL, s.cropT, s.cropR, s.cropB);
    }

    static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }
}

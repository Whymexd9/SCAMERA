package com.particlesdevs.photoncamera.gallery.editor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * P367: the editor's view zoom - fit / 100 % / max, the focal pinch, the pan clamp, the double tap, a resized view - and its
 * full-resolution regions: view -> source as the export maps a tile (turned, flipped, straightened, EXIF-oriented), the
 * visible region with the overlap and even alignment, the cache region's bounds, the cache test, the levels, RAW binning.
 */
public class EditZoomTest {
    static final double VW = 1000, VH = 1500;
    static final double SW = 4000, SH = 3000;

    /** A frame as EditorView makes it: the crop of an oriented sw x sh photo fitted with {@code margin}. */
    static EditZoom zoom(EditState s, double ow, double oh, double margin) {
        double[] b = CropMath.baseSize(ow, oh, s.quarterTurns);
        double aw = b[0] * (s.cropR - s.cropL), ah = b[1] * (s.cropB - s.cropT);
        double k = Math.min((VW - 2 * margin) / aw, (VH - 2 * margin) / ah);
        double fw = aw * k, fh = ah * k;
        EditZoom z = new EditZoom();
        double[] crop = CropMath.cropPixels(s, ow, oh);
        z.frame(VW, VH, (VW - fw) / 2, (VH - fh) / 2, fw, fh, crop[0], crop[1], false);
        return z;
    }

    static EditZoom zoom() {
        return zoom(new EditState(), SW, SH, 0);
    }

    /** No gaps: on an axis wider than the view the picture covers it, narrower it is centred. */
    static void assertClamped(EditZoom z) {
        double dw = z.displayedW(), dh = z.displayedH();
        if (dw <= z.viewW()) assertEquals((z.viewW() - dw) / 2, z.left, 1e-6);
        else {
            assertTrue(z.left <= 1e-9);
            assertTrue(z.left + dw >= z.viewW() - 1e-9);
        }
        if (dh <= z.viewH()) assertEquals((z.viewH() - dh) / 2, z.top, 1e-6);
        else {
            assertTrue(z.top <= 1e-9);
            assertTrue(z.top + dh >= z.viewH() - 1e-9);
        }
    }

    // ---------------------------------------------------------------- the zoom

    @Test
    public void fitHundredAndMax() {
        EditZoom z = zoom();
        assertEquals(0.25, z.fitScale(), 1e-12);
        assertEquals(0.25, z.scale, 1e-12);
        assertFalse(z.isZoomed());
        assertEquals(0, z.left, 1e-9);
        assertEquals(375, z.top, 1e-9);
        assertEquals(EditZoom.MAX_FULL, z.maxScale(), 0);
        assertEquals(25, z.percent(), 1e-9);
        // a photo smaller than the view: the fit is above 100 %, the max is 400 % (or the fit when that is larger)
        EditZoom small = zoom(new EditState(), 500, 400, 0);
        assertEquals(2.0, small.fitScale(), 1e-12);
        assertEquals(4.0, small.maxScale(), 0);
        EditZoom tiny = zoom(new EditState(), 100, 80, 0);
        assertEquals(10.0, tiny.maxScale(), 1e-12);
        assertFalse(tiny.zoomAbout(3, 500, 750));
        assertFalse(tiny.isZoomed());
    }

    @Test
    public void pinchKeepsTheFocalPointAndClamps() {
        EditZoom z = zoom();
        double fx = 400, fy = 700;
        double[] before = z.viewToCrop().apply(fx, fy);
        assertTrue(z.zoomAbout(2, fx, fy));
        assertEquals(0.5, z.scale, 1e-12);
        assertTrue(z.isZoomed());
        double[] after = z.viewToCrop().apply(fx, fy);
        // x: 2000 px wide > the view, the point stays; y: 1500 = the view's height, centred
        assertEquals(before[0], after[0], 1e-9);
        assertClamped(z);
        assertTrue(z.zoomAbout(2, fx, fy));
        assertEquals(1.0, z.scale, 1e-12);
        before = after;
        after = z.viewToCrop().apply(fx, fy);
        assertEquals(before[0], after[0], 1e-9);
        assertEquals(before[1], after[1], 1e-9);
        assertClamped(z);
        // the max, then out past the fit: exactly the fit again
        z.zoomAbout(100, fx, fy);
        assertEquals(4.0, z.scale, 1e-12);
        assertClamped(z);
        z.zoomAbout(1e-3, fx, fy);
        assertFalse(z.isZoomed());
        assertEquals(0.25, z.scale, 1e-12);
        assertEquals(375, z.top, 1e-9);
        assertFalse(z.zoomAbout(Double.NaN, fx, fy));
        assertFalse(z.zoomAbout(0, fx, fy));
    }

    @Test
    public void panStopsAtTheEdges() {
        EditZoom z = zoom();
        z.panBy(100, 100); // at the fit nothing moves
        assertEquals(0, z.left, 1e-9);
        assertEquals(375, z.top, 1e-9);
        z.zoomTo(1.0, 0.5, 0.5);
        assertEquals(VW / 2 - 2000, z.left, 1e-9);
        assertEquals(VH / 2 - 1500, z.top, 1e-9);
        z.panBy(1e6, 1e6);
        assertEquals(0, z.left, 1e-9);
        assertEquals(0, z.top, 1e-9);
        z.panBy(-1e7, -1e7);
        assertEquals(VW - 4000, z.left, 1e-9);
        assertEquals(VH - 3000, z.top, 1e-9);
        // wider than the view, lower than it: x pans, y stays centred
        z.zoomTo(0.4, 0.5, 0.5);
        assertEquals(1600, z.displayedW(), 1e-9);
        assertEquals(1200, z.displayedH(), 1e-9);
        assertEquals(150, z.top, 1e-9);
        z.panBy(1e5, 1e5);
        assertEquals(0, z.left, 1e-9);
        assertEquals(150, z.top, 1e-9);
        assertClamped(z);
    }

    @Test
    public void doubleTapTogglesFitAndHundredCentredOnTheTap() {
        EditZoom z = zoom();
        // the tap at crop uv (0.3, 0.4) goes to the view's centre at 100 %
        double tx = 0.3 * 1000, ty = 375 + 0.4 * 750;
        double[] t = z.toggleTarget(tx, ty);
        assertEquals(1.0, t[0], 1e-12);
        EditZoom to = z.copy();
        to.set(t[0], t[1], t[2]);
        double[] centre = to.viewToCrop().apply(VW / 2, VH / 2);
        assertEquals(0.3, centre[0], 1e-9);
        assertEquals(0.4, centre[1], 1e-9);
        // near a corner the clamp wins
        t = z.toggleTarget(1, 376);
        assertEquals(0, t[1], 1e-9);
        assertEquals(0, t[2], 1e-9);
        // zoomed -> back to the fit
        z.set(t[0], t[1], t[2]);
        assertTrue(z.isZoomed());
        t = z.toggleTarget(500, 500);
        assertEquals(0.25, t[0], 1e-12);
        assertEquals(0, t[1], 1e-9);
        assertEquals(375, t[2], 1e-9);
        // a small photo (fit 200 %): to 2x the fit, the max; a tiny one (fit at the max) stays
        EditZoom small = zoom(new EditState(), 500, 400, 0);
        assertEquals(4.0, small.toggleTarget(500, 750)[0], 1e-12);
        EditZoom tiny = zoom(new EditState(), 100, 80, 0);
        assertEquals(tiny.fitScale(), tiny.toggleTarget(500, 750)[0], 1e-12);
    }

    @Test
    public void aResizedViewKeepsTheZoomAndItsCentre() {
        EditZoom z = zoom();
        z.zoomTo(1.0, 0.3, 0.6);
        double[] c0 = z.viewToCrop().apply(VW / 2, VH / 2);
        // the view turns to landscape: the fit changes, the scale and the centre point stay
        z.frame(1500, 1000, 83.3, 0, 1333.3, 1000, 4000, 3000, true);
        assertEquals(1.0, z.scale, 1e-12);
        double[] c1 = z.viewToCrop().apply(750, 500);
        assertEquals(c0[0], c1[0], 1e-9);
        assertEquals(c0[1], c1[1], 1e-9);
        assertClamped(z);
        // keep = false (a geometry change): the fit
        z.frame(1500, 1000, 83.3, 0, 1333.3, 1000, 4000, 3000, false);
        assertFalse(z.isZoomed());
        assertEquals(83.3, z.left, 1e-9);
    }

    @Test
    public void visibleCropOfTheView() {
        EditZoom z = zoom();
        assertArrayEquals(new double[]{0, 0, 1, 1}, z.visibleCrop(), 1e-12);
        z.zoomTo(1.0, 0.5, 0.5);
        double[] v = z.visibleCrop();
        assertEquals(0.5 - 500 / 4000.0, v[0], 1e-12);
        assertEquals(0.5 - 750 / 3000.0, v[1], 1e-12);
        assertEquals(0.5 + 500 / 4000.0, v[2], 1e-12);
        assertEquals(0.5 + 750 / 3000.0, v[3], 1e-12);
        assertArrayEquals(new double[]{z.left > 0 ? z.left : 0, 0, 1000, 1500},
                EditZoom.visibleView(VW, VH, z.left, z.top, z.displayedW(), z.displayedH()), 1e-9);
        assertNull(EditZoom.visibleView(VW, VH, 2000, 0, 100, 100));
    }

    @Test
    public void fullResolutionOnlyPastThePreview() {
        assertFalse(EditZoom.fullResWanted(0.4, 0.5));
        assertFalse(EditZoom.fullResWanted(0.5, 0.5));
        assertTrue(EditZoom.fullResWanted(0.55, 0.5));
        assertTrue(EditZoom.fullResWanted(1.0, 0.5));
        // the preview is the original: nothing more to read
        assertFalse(EditZoom.fullResWanted(4.0, 1.0));
    }

    // ---------------------------------------------------------------- view -> source, as the export

    static EditState[] geometries() {
        EditState a = new EditState();
        EditState b = new EditState();
        b.cropL = 0.1f;
        b.cropT = 0.2f;
        b.cropR = 0.7f;
        b.cropB = 0.9f;
        EditState c = new EditState();
        CropMath.rotateClockwise(c);
        c.cropL = 0.2f;
        c.cropR = 0.9f;
        EditState d = new EditState();
        CropMath.flipHorizontal(d);
        d.straighten = 12;
        CropMath.insetState(d, SW, SH);
        EditState e = new EditState();
        CropMath.rotateClockwise(e);
        CropMath.rotateClockwise(e);
        CropMath.rotateClockwise(e);
        CropMath.flipHorizontal(e);
        e.straighten = -27;
        CropMath.insetState(e, SH, SW);
        return new EditState[]{a, b, c, d, e};
    }

    /**
     * The view's picture is the export's: a view point over output pixel (ox, oy) of the copy (W x H, the crop) maps to the
     * same source pixel as EditExporter's own output -> source chain ({@link EditExporter#plan}, what export() renders with),
     * for every geometry and EXIF orientation; and that plan is the documented chain.
     */
    @Test
    public void viewToSourceIsTheExportsMapping() {
        int sw = 4000, sh = 3000;
        for (int orientation = 1; orientation <= 8; orientation++) {
            boolean swap = CropMath.swapsSides(orientation);
            double ow = swap ? sh : sw, oh = swap ? sw : sh;
            EditState[] all = geometries();
            for (int gi = 0; gi < all.length; gi++) {
                EditState s = all[gi];
                String why = "orientation " + orientation + ", geometry " + gi; // (EditState.toString needs org.json)
                EditExporter.Plan plan = EditExporter.plan(s, sw, sh, orientation, EditSource.OUT_MAX);
                double[] crop = CropMath.cropPixels(s, ow, oh);
                int W = plan.W, H = plan.H;
                assertEquals(why, Math.round(crop[0]), W);
                assertEquals(why, Math.round(crop[1]), H);
                assertEquals(why, ow, plan.ow, 0);
                CropMath.Affine chain = CropMath.Affine.scale(1.0 / W, 1.0 / H).then(CropMath.cropToBase(s))
                        .then(CropMath.baseToOriented(s, ow, oh)).then(CropMath.orientationToDisplay(orientation).inverse())
                        .then(CropMath.Affine.scale(sw, sh));
                EditZoom z = zoom(s, ow, oh, 10);
                z.zoomTo(1.7, 0.4, 0.6);
                CropMath.Affine v2s = EditZoom.viewToSource(s, z.left, z.top, z.displayedW(), z.displayedH(), orientation, sw, sh);
                for (double[] p : new double[][]{{0, 0}, {W, 0}, {0, H}, {W, H}, {0.37 * W, 0.81 * H}}) {
                    double vx = z.left + p[0] / W * z.displayedW(), vy = z.top + p[1] / H * z.displayedH();
                    double[] a = v2s.apply(vx, vy), b = plan.outToSrcPx.apply(p[0], p[1]), c = chain.apply(p[0], p[1]);
                    assertEquals(why, b[0], a[0], 1e-6);
                    assertEquals(why, b[1], a[1], 1e-6);
                    assertEquals(why, c[0], b[0], 1e-9);
                    assertEquals(why, c[1], b[1], 1e-9);
                }
            }
        }
    }

    /** The copy's grain: its long side and lattice aspect are the rounded copy's W x H (export()), not the crop's px ratio. */
    @Test
    public void planGrainIsTheRoundedCopy() {
        EditState s = new EditState();
        s.cropL = 0.1f;
        s.cropR = 0.1f + 3152.8f / 4000f;
        s.cropT = 0.05f;
        s.cropB = 0.05f + 2586.6f / 3000f;
        double[] crop = CropMath.cropPixels(s, 4000, 3000);
        EditExporter.Plan plan = EditExporter.plan(s, 4000, 3000, 1, EditSource.OUT_MAX);
        int[] out = EditExporter.outputSize(crop[0], crop[1], EditSource.OUT_MAX);
        assertEquals(out[0], plan.W);
        assertEquals(out[1], plan.H);
        assertEquals(out[0] / (float) out[1], plan.latticeAspect(), 0f);
        assertEquals(Math.max(out[0], out[1]), plan.longSide());
        // a copy limited by memory: its own (smaller) size
        EditExporter.Plan small = EditExporter.plan(new EditState(), 16320, 12240, 6, 50_000_000L);
        int[] o2 = EditExporter.outputSize(12240, 16320, 50_000_000L);
        assertEquals(o2[0], small.W);
        assertEquals(o2[1], small.H);
        assertEquals(o2[0] / (float) o2[1], small.latticeAspect(), 0f);
    }

    /** The region of the visible view holds every visible point's source pixel with the overlap, inside the picture, even. */
    @Test
    public void visibleRegionCoversTheViewWithItsOverlap() {
        int sw = 4000, sh = 3000;
        int overlap = EditMath.overlapPx(Math.max(sw, sh)) + 2;
        for (int orientation : new int[]{1, 3, 6, 8}) {
            boolean swap = CropMath.swapsSides(orientation);
            double ow = swap ? sh : sw, oh = swap ? sw : sh;
            for (EditState s : geometries()) {
                for (double scale : new double[]{0.3, 1.0, 2.5}) {
                    EditZoom z = zoom(s, ow, oh, 10);
                    z.zoomTo(scale, 0.2, 0.7);
                    double[] vis = EditZoom.visibleView(VW, VH, z.left, z.top, z.displayedW(), z.displayedH());
                    assertNotNull(vis);
                    CropMath.Affine v2s = EditZoom.viewToSource(s, z.left, z.top, z.displayedW(), z.displayedH(), orientation, sw, sh);
                    double[] need = EditZoom.grow(EditZoom.box(v2s, vis), overlap);
                    // the tile region of the export for the same view rect agrees with the box + overlap
                    int[] tile = EditZoom.region(v2s, (int) Math.floor(vis[0]), (int) Math.floor(vis[1]),
                            (int) Math.ceil(vis[2] - Math.floor(vis[0])), (int) Math.ceil(vis[3] - Math.floor(vis[1])), overlap, sw, sh, true);
                    assertEquals(0, tile[0] & 1);
                    assertEquals(0, tile[1] & 1);
                    assertTrue(EditZoom.covers(tile, need, sw, sh, 0));
                    double cap = EditZoom.cap(VW, VH);
                    double vps = scale; // reader px = original px here
                    double level = EditZoom.level(vps, false, need, cap);
                    int[] reg = EditZoom.cacheRegion(need, level, cap, sw, sh, true);
                    assertEquals(0, reg[0] & 1);
                    assertEquals(0, reg[1] & 1);
                    assertTrue(reg[0] >= 0 && reg[1] >= 0 && reg[0] + reg[2] <= sw && reg[1] + reg[3] <= sh);
                    assertTrue(EditZoom.covers(reg, need, sw, sh, 0));
                    // its texels stay within the cap (2x the view, 4 MP at most)
                    assertTrue(reg[2] * (double) reg[3] / (level * level) <= cap * 1.02 + 4 * (reg[2] + reg[3]) / level);
                    for (double fy = 0; fy <= 1.0001; fy += 0.125)
                        for (double fx = 0; fx <= 1.0001; fx += 0.125) {
                            double[] q = v2s.apply(vis[0] + (vis[2] - vis[0]) * fx, vis[1] + (vis[3] - vis[1]) * fy);
                            assertTrue(q[0] - overlap >= reg[0] - 1e-6 || reg[0] == 0);
                            assertTrue(q[0] + overlap <= reg[0] + reg[2] + 1e-6 || reg[0] + reg[2] == sw);
                            assertTrue(q[1] - overlap >= reg[1] - 1e-6 || reg[1] == 0);
                            assertTrue(q[1] + overlap <= reg[1] + reg[3] + 1e-6 || reg[1] + reg[3] == sh);
                        }
                }
            }
        }
    }

    /**
     * At «100 %» (the copy's 1:1) the placement puts the copy's output pixels on the screen's: every screen pixel centre maps to
     * the source point the export samples for that output pixel centre (any turn, flip, straighten, EXIF orientation, a
     * fractional crop) - no half-pixel blur, no shift between the preview and the region; uncropped and unstraightened, that is
     * a reader pixel centre. Off 100 % (or a copy smaller than the crop) the placement is the model's.
     */
    @Test
    public void placementAtHundredIsTheCopysPixelGrid() {
        int sw = 4000, sh = 3000;
        EditState frac = new EditState();
        frac.cropL = 0.1f;
        frac.cropR = 0.1f + 3152.8f / 4000f;
        frac.cropT = 0.05f;
        frac.cropB = 0.05f + 2586.6f / 3000f;
        EditState[] all = geometries();
        EditState[] states = {all[0], all[1], all[2], all[3], all[4], frac};
        for (int orientation : new int[]{1, 2, 3, 5, 6, 8}) {
            boolean swap = CropMath.swapsSides(orientation);
            double ow = swap ? sh : sw, oh = swap ? sw : sh;
            for (int gi = 0; gi < states.length; gi++) {
                EditState s = states[gi];
                String why = "orientation " + orientation + ", geometry " + gi;
                EditExporter.Plan plan = EditExporter.plan(s, sw, sh, orientation, EditSource.OUT_MAX);
                EditZoom z = zoom(s, ow, oh, 10);
                z.copySize(plan.W, plan.H);
                double[] crop = CropMath.cropPixels(s, ow, oh);
                assertEquals(why, plan.W / crop[0], z.hundred(), 1e-12);
                assertEquals(why, 1.0, z.hundred(), 1e-3);
                z.zoomTo(z.hundred(), 0.4137, 0.5821);
                double[] p = z.placement();
                assertEquals(why, Math.rint(p[0]), p[0], 0);
                assertEquals(why, Math.rint(p[1]), p[1], 0);
                assertEquals(why, plan.W, p[2], 0);
                assertEquals(why, plan.H, p[3], 0);
                assertTrue(why, Math.abs(p[0] - z.left) <= 1.5 && Math.abs(p[1] - z.top) <= 1.5);
                CropMath.Affine v2s = EditZoom.viewToSource(s, p[0], p[1], p[2], p[3], orientation, sw, sh);
                for (int[] px : new int[][]{{123, 456}, {0, 0}, {999, 1499}, {517, 3}}) {
                    double ox = px[0] + 0.5 - p[0], oy = px[1] + 0.5 - p[1]; // the output pixel centre under it
                    assertEquals(why, 0.5, ox - Math.floor(ox), 1e-9);
                    double[] a = v2s.apply(px[0] + 0.5, px[1] + 0.5), b = plan.outToSrcPx.apply(ox, oy);
                    assertEquals(why, b[0], a[0], 1e-6);
                    assertEquals(why, b[1], a[1], 1e-6);
                    if (gi == 0) { // uncropped: whole reader pixels
                        assertEquals(why, 0.5, a[0] - Math.floor(a[0]), 1e-6);
                        assertEquals(why, 0.5, a[1] - Math.floor(a[1]), 1e-6);
                    }
                }
                // at an edge the placement keeps the clamp: no gap
                z.zoomTo(z.hundred(), 0, 0);
                p = z.placement();
                assertEquals(why, 0, p[0], 0);
                assertEquals(why, 0, p[1], 0);
                z.zoomTo(z.hundred(), 1, 1);
                p = z.placement();
                assertEquals(why, VW, p[0] + p[2], 1e-9);
                assertEquals(why, VH, p[1] + p[3], 1e-9);
                // off 100 %: the model's placement
                z.zoomTo(1.7, 0.4, 0.6);
                assertArrayEquals(why, new double[]{z.left, z.top, z.displayedW(), z.displayedH()}, z.placement(), 0);
            }
        }
        // a copy smaller than the crop (memory): «100 %» is the original's 1:1, nothing snapped
        EditZoom z = zoom();
        int[] small = EditExporter.outputSize(SW, SH, 4_000_000L);
        z.copySize(small[0], small[1]);
        assertEquals(1.0, z.hundred(), 0);
        z.zoomTo(1.0, 0.4137, 0.5821);
        assertArrayEquals(new double[]{z.left, z.top, z.displayedW(), z.displayedH()}, z.placement(), 0);
        // the double tap goes to «100 %»
        z = zoom(frac, SW, SH, 0);
        double[] c = CropMath.cropPixels(frac, SW, SH);
        int[] out = EditExporter.outputSize(c[0], c[1], EditSource.OUT_MAX);
        z.copySize(out[0], out[1]);
        assertEquals(z.hundred(), z.toggleTarget(500, 750)[0], 0);
    }

    /** A slow pinch (factors just above 1, as ScaleGestureDetector gives them event by event) adds up from the fit. */
    @Test
    public void aSlowPinchAddsUp() {
        EditZoom z = zoom();
        double fit = z.fitScale();
        for (int i = 0; i < 50; i++) z.zoomAbout(1.0005, 400, 700);
        assertTrue(z.scale > fit * 1.02);
        assertTrue(z.isZoomed());
        assertClamped(z);
        // and back under the fit: exactly the fit
        for (int i = 0; i < 100; i++) z.zoomAbout(0.999, 400, 700);
        assertEquals(fit, z.scale, 0);
        assertEquals(375, z.top, 1e-9);
        assertFalse(z.isZoomed());
        assertFalse(z.zoomAbout(0.999, 400, 700));
    }

    /**
     * At 100 % and above a straightened view gets the original's pixels: the needed box (the turned view's bounding box +
     * overlap, up to ~2.2x the view) stays at level 1 under the ceiling - for a 50 MP RAW and JPEG, a phone-sized view, the
     * geometries d, e and 45 degrees. Only the margin is bounded by the cap.
     */
    @Test
    public void hundredPercentStaysFullResolutionWhenStraightened() {
        int sw = 8160, sh = 6120;
        int overlap = EditMath.overlapPx(Math.max(sw, sh)) + 2;
        EditState t45 = new EditState();
        t45.straighten = 45;
        CropMath.insetState(t45, sw, sh);
        EditState[] all = geometries();
        double[][] views = {{1080, 1800}, {1600, 2400}};
        for (double[] view : views) {
            double vw = view[0], vh = view[1];
            for (EditState s : new EditState[]{all[3], all[4], t45}) {
                double[] b = CropMath.baseSize(sw, sh, s.quarterTurns);
                double aw = b[0] * (s.cropR - s.cropL), ah = b[1] * (s.cropB - s.cropT);
                double k = Math.min((vw - 20) / aw, (vh - 20) / ah);
                EditZoom z = new EditZoom();
                double[] crop = CropMath.cropPixels(s, sw, sh);
                z.frame(vw, vh, (vw - aw * k) / 2, (vh - ah * k) / 2, aw * k, ah * k, crop[0], crop[1], false);
                for (double scale : new double[]{1.0, 2.0}) {
                    z.zoomTo(scale, 0.5, 0.5);
                    double[] vis = EditZoom.visibleView(vw, vh, z.left, z.top, z.displayedW(), z.displayedH());
                    CropMath.Affine v2s = EditZoom.viewToSource(s, z.left, z.top, z.displayedW(), z.displayedH(), 1, sw, sh);
                    double[] need = EditZoom.grow(EditZoom.box(v2s, vis), overlap);
                    double ceiling = EditZoom.ceiling(vw, vh);
                    for (boolean integer : new boolean[]{true, false}) {
                        double level = EditZoom.level(scale, integer, need, ceiling);
                        assertEquals("view " + vw + "x" + vh + ", straighten " + s.straighten + ", scale " + scale, 1.0, level, 0);
                        assertTrue(EditZoom.fineEnough(level, scale, integer, need, ceiling));
                        int[] reg = EditZoom.cacheRegion(need, level, EditZoom.cap(vw, vh), sw, sh, integer);
                        assertTrue(EditZoom.covers(reg, need, sw, sh, 0));
                        // the margin stays within the cap; the needed box alone may be over it
                        double needArea = (need[2] - need[0]) * (need[3] - need[1]);
                        assertTrue(reg[2] * (double) reg[3] <= Math.max(EditZoom.cap(vw, vh) * 1.02, needArea * 1.02) + 8 * (reg[2] + reg[3]));
                    }
                }
            }
        }
        // past the ceiling the level rises - and that best level counts as fine (no decode again and again)
        double[] huge = {0, 0, 8000, 6000};
        double l = EditZoom.level(1.0, false, huge, 4_000_000);
        assertTrue(l > 3);
        assertTrue(EditZoom.fineEnough(l, 1.0, false, huge, 4_000_000));
        assertFalse(EditZoom.adequate(l, 1.0));
        assertFalse(EditZoom.fineEnough(l * 1.5, 1.0, false, huge, 4_000_000));
        // the ceiling: 3x the view, at most 12 MP, never under the cap
        assertEquals(3 * 1080 * 1800, EditZoom.ceiling(1080, 1800), 1e-6);
        assertEquals(EditZoom.NEED_MAX, EditZoom.ceiling(3000, 2000), 0);
        assertTrue(EditZoom.ceiling(100, 100) >= EditZoom.cap(100, 100));
    }

    @Test
    public void cacheRegionGrowsMovesInsideAndCuts() {
        // a small need in the middle grows (at most 3x a side), centred on it
        double[] need = {1900, 1400, 2100, 1600};
        int[] r = EditZoom.cacheRegion(need, 1, 1e9, 4000, 3000, false);
        assertEquals(600, r[2]);
        assertEquals(600, r[3]);
        assertEquals(1700, r[0]);
        assertEquals(1200, r[1]);
        // the cap limits the growth: 200 x 200 -> 400 x 400 for 160000 texels
        r = EditZoom.cacheRegion(need, 1, 160_000, 4000, 3000, false);
        assertEquals(400, r[2]);
        assertEquals(400, r[3]);
        // at level 2 the same cap holds 4x the pixels
        r = EditZoom.cacheRegion(need, 2, 160_000, 4000, 3000, false);
        assertEquals(600, r[2]);
        // at the corner it moves back inside (still 600 x 600), then is cut by the picture
        r = EditZoom.cacheRegion(new double[]{-30, -30, 170, 170}, 1, 1e9, 4000, 3000, true);
        assertArrayEquals(new int[]{0, 0, 600, 600}, r);
        r = EditZoom.cacheRegion(new double[]{-100, -100, 5000, 3500}, 1, 1e9, 4000, 3000, true);
        assertArrayEquals(new int[]{0, 0, 4000, 3000}, r);
        // even alignment for a CFA
        r = EditZoom.cacheRegion(new double[]{1001.5, 777.2, 1201.5, 977.2}, 1, 40_000, 4000, 3000, true);
        assertEquals(0, r[0] & 1);
        assertEquals(0, r[1] & 1);
        assertTrue(r[0] <= 1001.5 && r[0] + r[2] >= 1201.5);
    }

    @Test
    public void coversIsTheCacheTest() {
        int[] reg = {1000, 800, 1200, 900};
        assertTrue(EditZoom.covers(reg, new double[]{1000, 800, 2200, 1700}, 4000, 3000, 0));
        assertTrue(EditZoom.covers(reg, new double[]{1100, 900, 2000, 1600}, 4000, 3000, 0));
        assertFalse(EditZoom.covers(reg, new double[]{999, 900, 2000, 1600}, 4000, 3000, 0));
        assertFalse(EditZoom.covers(reg, new double[]{1100, 900, 2201, 1600}, 4000, 3000, 0));
        assertFalse(EditZoom.covers(reg, new double[]{1100, 900, 2000, 1701}, 4000, 3000, 0));
        // a need past the picture's edge is cut to it
        int[] edge = {3000, 0, 1000, 1000};
        assertTrue(EditZoom.covers(edge, new double[]{3100, -50, 4050, 900}, 4000, 3000, 0));
        // a binned RAW region a few samples short of the edge still counts with its slack
        int[] shortRaw = {3000, 0, 996, 1000};
        assertFalse(EditZoom.covers(shortRaw, new double[]{3100, 0, 4000, 900}, 4000, 3000, 0));
        assertTrue(EditZoom.covers(shortRaw, new double[]{3100, 0, 4000, 900}, 4000, 3000, EditZoomLoader.slack(2)));
        // but not inside the picture
        assertFalse(EditZoom.covers(new int[]{1000, 0, 996, 1000}, new double[]{1100, 0, 2000, 900}, 4000, 3000, EditZoomLoader.slack(2)));
        // and never at the near edges: a region starting inside the picture does not hold its first columns / rows
        assertFalse(EditZoom.covers(new int[]{2, 0, 996, 1000}, new double[]{0, 0, 900, 900}, 4000, 3000, EditZoomLoader.slack(1)));
        assertFalse(EditZoom.covers(new int[]{8, 0, 996, 1000}, new double[]{-60, 0, 900, 900}, 4000, 3000, EditZoomLoader.slack(4)));
        assertFalse(EditZoom.covers(new int[]{0, 6, 996, 1000}, new double[]{0, -10, 900, 900}, 4000, 3000, EditZoomLoader.slack(4)));
        assertTrue(EditZoom.covers(new int[]{0, 0, 996, 1000}, new double[]{-60, -60, 900, 900}, 4000, 3000, 0));
        // nothing to cover
        assertFalse(EditZoom.covers(reg, new double[]{5000, 0, 6000, 900}, 4000, 3000, 0));
    }

    @Test
    public void levels() {
        double[] need = {0, 0, 1000, 1000};
        assertEquals(1.0, EditZoom.idealLevel(1.0), 0);
        assertEquals(1.0, EditZoom.idealLevel(3.0), 0);
        assertEquals(2.5, EditZoom.idealLevel(0.4), 1e-12);
        assertEquals(2.5, EditZoom.level(0.4, false, need, 1e7), 1e-12);
        assertEquals(1.0, EditZoom.level(2.0, false, need, 1e7), 0);
        // the cap raises the level: 1e6 px into 250000 texels -> 2
        assertEquals(2.0, EditZoom.level(1.0, false, need, 250_000), 1e-12);
        // RAW: whole numbers, rounded down unless the cap says more
        assertEquals(2.0, EditZoom.level(0.4, true, need, 1e7), 0);
        assertEquals(1.0, EditZoom.level(0.6, true, need, 1e7), 0);
        assertEquals(3.0, EditZoom.level(0.6, true, need, 120_000), 0);
        // adequacy: finer is always fine, coarser by up to 30 %
        assertTrue(EditZoom.adequate(1, 0.3));
        assertTrue(EditZoom.adequate(1.3, 1.0));
        assertFalse(EditZoom.adequate(2, 1.0));
        assertTrue(EditZoom.adequate(3, 0.3));
        // the cap: 2x the view, at most 4 MP, at least 1.25x
        assertEquals(2 * VW * VH, EditZoom.cap(VW, VH), 0);
        assertEquals(EditZoom.CAP_MAX, EditZoom.cap(1600, 2000), 0);
        assertEquals(1.25 * 2000 * 2500, EditZoom.cap(2000, 2500), 1e-6);
    }

    // ---------------------------------------------------------------- RAW binning

    static ShortBuffer buffer(short[] v) {
        ShortBuffer b = ByteBuffer.allocateDirect(v.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        b.put(v).rewind();
        return b;
    }

    @Test
    public void binningKeepsTheBayerPhase() {
        int w = 16, h = 12;
        short[] v = new short[w * h];
        // a CFA whose colour sits in the value: R 10000+, G1 20000+, G2 30000+, B 40000+ (40000 > 32767: unsigned)
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int c = (y & 1) * 2 + (x & 1);
                v[y * w + x] = (short) (10000 * (c + 1) + x + 100 * y);
            }
        ShortBuffer src = buffer(v);
        src.position(5);
        // level 1 is a copy
        ShortBuffer copy = EditZoom.bin(src, w, 1, 2, 2, 8, 6, 1, true);
        assertEquals(5, src.position());
        for (int y = 0; y < 6; y++)
            for (int x = 0; x < 8; x++) assertEquals(v[(y + 2) * w + x + 2], copy.get(y * 8 + x));
        // level 2 from (2, 2): out (ox, oy) = the mean of the 4 same-colour samples of its 4 x 4 cell
        int ow = 6, oh = 4;
        ShortBuffer out = EditZoom.bin(src, w, 1, 2, 2, ow, oh, 2, true);
        for (int oy = 0; oy < oh; oy++)
            for (int ox = 0; ox < ow; ox++) {
                int sum = 0;
                for (int j = 0; j < 2; j++)
                    for (int i = 0; i < 2; i++) {
                        int sx = 2 + 2 * ((ox >> 1) * 2 + i) + (ox & 1), sy = 2 + 2 * ((oy >> 1) * 2 + j) + (oy & 1);
                        sum += v[sy * w + sx] & 0xFFFF;
                    }
                int got = out.get(oy * ow + ox) & 0xFFFF;
                assertEquals((sum + 2) / 4, got);
                int colour = (oy & 1) * 2 + (ox & 1);
                assertEquals(colour + 1, got / 10000); // the phase held
            }
    }

    @Test
    public void binningRgbAveragesBoxes() {
        int w = 6, h = 4;
        short[] v = new short[w * h * 3];
        for (int i = 0; i < v.length; i++) v[i] = (short) (i * 1000);
        ShortBuffer out = EditZoom.bin(buffer(v), w, 3, 0, 0, 3, 2, 2, false);
        for (int oy = 0; oy < 2; oy++)
            for (int ox = 0; ox < 3; ox++)
                for (int ch = 0; ch < 3; ch++) {
                    int sum = 0;
                    for (int j = 0; j < 2; j++)
                        for (int i = 0; i < 2; i++) sum += v[((oy * 2 + j) * w + ox * 2 + i) * 3 + ch] & 0xFFFF;
                    assertEquals((sum + 2) / 4, out.get((oy * 3 + ox) * 3 + ch) & 0xFFFF);
                }
    }

    // ---------------------------------------------------------------- keys, export parity

    @Test
    public void geometryKeyFollowsTheGeometryOnly() {
        EditState s = new EditState();
        String k = EditZoom.geometryKey(s);
        EditState t = s.copy();
        t.set(EditState.Param.EXPOSURE, 1.5f);
        t.set(EditState.Param.GRAIN, 40);
        assertEquals(k, EditZoom.geometryKey(t));
        t.cropR = 0.9f;
        assertNotEquals(k, EditZoom.geometryKey(t));
        EditState u = s.copy();
        CropMath.rotateClockwise(u);
        assertNotEquals(k, EditZoom.geometryKey(u));
        EditState f = s.copy();
        CropMath.flipHorizontal(f);
        assertNotEquals(k, EditZoom.geometryKey(f));
        EditState g = s.copy();
        g.straighten = 0.5f;
        assertNotEquals(k, EditZoom.geometryKey(g));
    }

    @Test
    public void exportSizeAndRegionAreShared() {
        // the copy's size: the crop, scaled down to outMax pixels
        assertArrayEquals(new int[]{4000, 3000}, EditExporter.outputSize(4000, 3000, 64_000_000L));
        int[] big = EditExporter.outputSize(16000, 12000, 64_000_000L);
        assertTrue((long) big[0] * big[1] <= 64_000_000L + 16000);
        assertEquals(16000 / 12000.0, big[0] / (double) big[1], 1e-3);
        assertEquals(4_000_000L, EditExporter.outMax(1L << 20));
        assertEquals(EditSource.OUT_MAX, EditExporter.outMax(1L << 40));
        // EditExporter.region is EditZoom.region
        CropMath.Affine m = CropMath.Affine.scale(2, 2).then(CropMath.Affine.translate(13, 7));
        assertArrayEquals(EditZoom.region(m, 10, 20, 100, 50, 9, 4000, 3000, true), EditExporter.region(m, 10, 20, 100, 50, 9, 4000, 3000, true));
    }

    /** The JPEG reader's sample (the export's and the zoom's): tiles at most 2x the output on each side, a crop needs less. */
    @Test
    public void regionSampleFollowsTheCrop() {
        int w = 16320, h = 12240; // 200 MP
        assertEquals(1, EditSource.regionSample(w, h, 1.0, 64_000_000L));
        assertEquals(2, EditSource.regionSample(w, h, 1.0, 40_000_000L));
        assertEquals(1, EditSource.regionSample(w, h, 0.25, 40_000_000L));
        assertEquals(4, EditSource.regionSample(w, h, 1.0, 4_000_000L));
        assertEquals(2, EditSource.regionSample(w, h, 0.25, 4_000_000L));
    }

    /** The heap guard of the zoom's RAW reader: what RawReader allocates on the Java heap (RGB 3 samples, binned, LibRaw RGB native). */
    @Test
    public void rawHeapBytesMirrorRawReader() {
        long gb = 1L << 30;
        // a 50 MP Bayer DNG, plenty of memory: 2 bytes a sample
        assertEquals(50_000_000L * 2, EditZoomLoader.rawHeapBytes(50_000_000L, false, false, 2 * gb));
        // a 50 MP linear (RGB) DNG: 3 samples a pixel - 300 MB, not 100
        assertEquals(50_000_000L * 3 * 2, EditZoomLoader.rawHeapBytes(50_000_000L, true, false, 2 * gb));
        // over RAW_MAX (200 MP) or budget / 6: binned 2x2
        assertEquals(200_000_000L / 4 * 2, EditZoomLoader.rawHeapBytes(200_000_000L, false, false, 2 * gb));
        assertEquals(50_000_000L / 4 * 3 * 2, EditZoomLoader.rawHeapBytes(50_000_000L, true, false, 200L << 20));
        // a LibRaw Bayer mosaic is on the heap too; LibRaw's developed RGB is native
        assertEquals(24_000_000L * 2, EditZoomLoader.rawHeapBytes(24_000_000L, false, true, 2 * gb));
        assertEquals(0, EditZoomLoader.rawHeapBytes(24_000_000L, true, true, 2 * gb));
    }
}

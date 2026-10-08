package com.particlesdevs.photoncamera.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.particlesdevs.photoncamera.control.subject.ViewfinderMapping;

import org.junit.Test;

/**
 * P42: view <-> Camera2 region mapping of the face boxes and the tracking AF. Lives in the control package to check it
 * against TouchFocus.mapTapToCrop, the tap focus mapping it must agree with.
 */
public class SubjectMappingTest {
    private static final float EPS = 1e-3f;
    private static final int PORTRAIT = 90; // gravity scale: natural portrait

    private static ViewfinderMapping mapping(int sensorOrientation, boolean mirror, float[] window) {
        return new ViewfinderMapping().set(ViewfinderMapping.rotationFor(sensorOrientation, PORTRAIT), mirror, window);
    }

    @Test
    public void rotationMatchesTouchFocus() {
        assertEquals(270, ViewfinderMapping.rotationFor(90, PORTRAIT));
        assertEquals(90, ViewfinderMapping.rotationFor(270, PORTRAIT));
        assertEquals(0, ViewfinderMapping.rotationFor(0, PORTRAIT));
        assertEquals(180, ViewfinderMapping.rotationFor(90, 180)); // display ROTATION_90 (gravity 180)
        assertEquals(0, ViewfinderMapping.rotationFor(90, 360)); // display ROTATION_270 (gravity 360)
    }

    @Test
    public void backCameraPortraitCorners() {
        // Back camera, sensor orientation 90: the sensor's top-left shows at the view's top-right.
        ViewfinderMapping m = mapping(90, false, new float[]{0, 0, 4000, 3000});
        float[] p = new float[2];
        m.viewToSensor(1f, 0f, p);
        assertEquals(0f, p[0], EPS);
        assertEquals(0f, p[1], EPS);
        m.viewToSensor(0f, 1f, p);
        assertEquals(4000f, p[0], EPS);
        assertEquals(3000f, p[1], EPS);
        m.sensorToView(4000f, 0f, p); // sensor top-right -> view bottom-right
        assertEquals(1f, p[0], EPS);
        assertEquals(1f, p[1], EPS);
    }

    @Test
    public void frontCameraMirroredCorners() {
        // Front camera, sensor orientation 270, mirrored viewfinder: the sensor's top-left shows at the bottom-right.
        ViewfinderMapping m = mapping(270, true, new float[]{0, 0, 4000, 3000});
        float[] p = new float[2];
        m.sensorToView(0f, 0f, p);
        assertEquals(1f, p[0], EPS);
        assertEquals(1f, p[1], EPS);
        m.sensorToView(4000f, 3000f, p);
        assertEquals(0f, p[0], EPS);
        assertEquals(0f, p[1], EPS);
        m.sensorToView(4000f, 0f, p); // sensor top-right -> view top-right (mirror of the unmirrored top-left)
        assertEquals(1f, p[0], EPS);
        assertEquals(0f, p[1], EPS);
    }

    @Test
    public void roundTripEveryRotationAndMirror() {
        float[] window = {300, 200, 3400, 2550};
        float[] s = new float[2], v = new float[2];
        for (int so : new int[]{0, 90, 180, 270}) {
            for (boolean mirror : new boolean[]{false, true}) {
                ViewfinderMapping m = mapping(so, mirror, window);
                for (float u = 0.05f; u < 1f; u += 0.3f) {
                    for (float w = 0.1f; w < 1f; w += 0.25f) {
                        m.viewToSensor(u, w, s);
                        m.sensorToView(s[0], s[1], v);
                        assertEquals(so + "/" + mirror, u, v[0], EPS);
                        assertEquals(so + "/" + mirror, w, v[1], EPS);
                    }
                }
            }
        }
    }

    @Test
    public void agreesWithTapFocusMapping() {
        // Square tap regions of TouchFocus are centred where ViewfinderMapping puts the view point (away from the
        // clamping at the crop border), for back / front, zoomed crops, both orientations.
        int[][] crops = {{0, 0, 4000, 3000}, {1000, 750, 2000, 1500}, {1500, 1125, 1000, 750}};
        int viewW = 1080, viewH = 1440; // 3:4 portrait: same aspect as the 4:3 crops, so the window is the crop
        float[] s = new float[2];
        for (int[] c : crops) {
            for (int so : new int[]{90, 270}) {
                for (boolean mirror : new boolean[]{false, true}) {
                    int rotation = ViewfinderMapping.rotationFor(so, PORTRAIT);
                    float[] window = ViewfinderMapping.ispWindow(c[0], c[1], c[2], c[3], viewW, viewH, rotation, new float[4]);
                    assertEquals(c[2], window[2], 0.5f);
                    assertEquals(c[3], window[3], 0.5f);
                    ViewfinderMapping m = new ViewfinderMapping().set(rotation, mirror, window);
                    for (float fx : new float[]{300, 540, 800}) {
                        for (float fy : new float[]{400, 720, 1100}) {
                            int[] tap = TouchFocus.mapTapToCrop(fx, fy, viewW, viewH, c[0], c[1], c[2], c[3], so, PORTRAIT, mirror);
                            m.viewToSensor(fx / viewW, fy / viewH, s);
                            float half = tap[2] / 2f;
                            assertEquals("x so=" + so + " mirror=" + mirror, tap[0] + half, s[0], 2f);
                            assertEquals("y so=" + so + " mirror=" + mirror, tap[1] + half, s[1], 2f);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void ispWindowCentreCropsToTheViewAspect() {
        int rotation = ViewfinderMapping.rotationFor(90, PORTRAIT);
        // 9:16 portrait view -> 16:9 in sensor orientation: the 4:3 crop loses top and bottom.
        float[] w = ViewfinderMapping.ispWindow(0, 0, 4000, 3000, 1080, 1920, rotation, new float[4]);
        assertEquals(0f, w[0], EPS);
        assertEquals(4000f, w[2], EPS);
        assertEquals(2250f, w[3], 0.5f);
        assertEquals(375f, w[1], 0.5f);
        // 1:1 view: the sides go.
        w = ViewfinderMapping.ispWindow(0, 0, 4000, 3000, 1080, 1080, rotation, new float[4]);
        assertEquals(500f, w[0], EPS);
        assertEquals(3000f, w[2], EPS);
        assertEquals(0f, w[1], EPS);
        // A zoom crop region keeps its centre.
        w = ViewfinderMapping.ispWindow(1000, 750, 2000, 1500, 1080, 1920, rotation, new float[4]);
        assertEquals(2000f, w[0] + w[2] / 2f, EPS);
        assertEquals(1500f, w[1] + w[3] / 2f, EPS);
    }

    @Test
    public void faceBoxFollowsZoomCrop() {
        // With a 2x crop region the same sensor face appears twice as large in the view.
        int rotation = ViewfinderMapping.rotationFor(90, PORTRAIT);
        float[] tmp = new float[2], full = new float[4], zoomed = new float[4];
        new ViewfinderMapping().set(rotation, false, ViewfinderMapping.ispWindow(0, 0, 4000, 3000, 1080, 1440, rotation, new float[4]))
                .sensorRectToView(1800, 1300, 2200, 1700, tmp, full);
        new ViewfinderMapping().set(rotation, false, ViewfinderMapping.ispWindow(1000, 750, 2000, 1500, 1080, 1440, rotation, new float[4]))
                .sensorRectToView(1800, 1300, 2200, 1700, tmp, zoomed);
        assertEquals(2f * (full[2] - full[0]), zoomed[2] - zoomed[0], EPS);
        assertEquals(2f * (full[3] - full[1]), zoomed[3] - zoomed[1], EPS);
        assertEquals(0.5f, (zoomed[0] + zoomed[2]) / 2f, EPS);
        assertEquals(0.5f, (zoomed[1] + zoomed[3]) / 2f, EPS);
    }

    @Test
    public void rawViewfinderWindowWithZoomRatio() {
        // The developed RAW viewfinder shows the unzoomed array; with CONTROL_ZOOM_RATIO 2 the Camera2 system covers
        // only its central half, so a face filling the zoomed field shows in the central half of the view.
        int rotation = ViewfinderMapping.rotationFor(90, PORTRAIT);
        float[] window = ViewfinderMapping.rawWindow(4000, 3000, new float[]{0, 0, 1, 1}, 2f, 1080, 1440, rotation, new float[4]);
        assertEquals(-2000f, window[0], EPS);
        assertEquals(8000f, window[2], EPS);
        float[] tmp = new float[2], view = new float[4];
        new ViewfinderMapping().set(rotation, false, window).sensorRectToView(0, 0, 4000, 3000, tmp, view);
        assertEquals(0.25f, view[0], EPS);
        assertEquals(0.75f, view[2], EPS);
        assertEquals(0.25f, view[1], EPS);
        assertEquals(0.75f, view[3], EPS);
        // Without a zoom ratio it is the ISP window.
        float[] raw = ViewfinderMapping.rawWindow(4000, 3000, new float[]{0.25f, 0.25f, 0.5f, 0.5f}, 1f, 1080, 1440, rotation, new float[4]);
        float[] isp = ViewfinderMapping.ispWindow(1000, 750, 2000, 1500, 1080, 1440, rotation, new float[4]);
        for (int i = 0; i < 4; i++) assertEquals(isp[i], raw[i], EPS);
    }

    @Test
    public void meteringRectStaysInsideTheArray() {
        int[] r = new int[4];
        assertTrue(ViewfinderMapping.meteringRect(3900, 2900, 4100, 3100, 1f, 80, 4000, 3000, r));
        assertTrue(r[0] >= 0 && r[1] >= 0 && r[0] + r[2] <= 4000 && r[1] + r[3] <= 3000);
        assertTrue(ViewfinderMapping.meteringRect(1000, 1000, 1010, 1010, 1f, 80, 4000, 3000, r));
        assertEquals(80, r[2]); // minimum side
        assertTrue(ViewfinderMapping.meteringRect(1000, 1000, 1200, 1200, 1.3f, 8, 4000, 3000, r));
        assertEquals(260, r[2]);
        assertEquals(1100, r[0] + r[2] / 2);
        assertFalse("centre outside the array", ViewfinderMapping.meteringRect(-500, -500, -100, -100, 1f, 8, 4000, 3000, r));
    }
}

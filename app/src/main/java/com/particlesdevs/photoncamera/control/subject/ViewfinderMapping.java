package com.particlesdevs.photoncamera.control.subject;

/**
 * Geometry between the viewfinder view and Camera2's region coordinates (P42).
 * <p>
 * Camera2 face rectangles, metering regions and the crop region share one coordinate system: the active pixel array,
 * (0, 0) at its top-left, already "zoomed" when CONTROL_ZOOM_RATIO is in use. The viewfinder shows a <em>window</em>
 * of that system:
 * <ul>
 *   <li>ISP preview: the crop region, centre-cropped by the HAL to the stream's aspect ratio (which is the view's
 *       aspect turned into sensor orientation) — {@link #ispWindow};</li>
 *   <li>developed RAW viewfinder (LiveRawRenderer): the RAW crop it computes itself, in <em>unzoomed</em> active-array
 *       coordinates, then fitted to the view; turned into the zoomed system by the zoom ratio — {@link #rawWindow}.</li>
 * </ul>
 * The window is then rotated into the view by the same rotation {@code TouchFocus.mapTapToCrop} inverts — the sensor
 * image is turned {@code (sensorOrientation + gravityRotation + 270) % 360} clockwise to appear upright — and
 * mirrored for the front camera after the rotation.
 * <p>
 * View coordinates are normalized: (0, 0) top-left, (1, 1) bottom-right of the preview view. Pure Java, mutable and
 * allocation-free once set (callers pass output arrays).
 */
public final class ViewfinderMapping {
    private int rotation;          // degrees the view coordinates are turned to reach sensor coordinates (0/90/180/270)
    private boolean mirrored;
    private float winL, winT, winW = 1, winH = 1;
    private boolean valid;

    /** Same convention as TouchFocus: {@code (90 - sensorOrientation - gravityRotation)} normalized to 0..359. */
    public static int rotationFor(int sensorOrientation, int gravityRotation) {
        int r = ((90 - sensorOrientation - gravityRotation) % 360 + 360) % 360;
        return (r + 45) / 90 % 4 * 90; // snap to a quarter turn
    }

    /** True when the view's x axis runs along the sensor's y axis. */
    public static boolean quarterTurn(int rotation) {
        return rotation == 90 || rotation == 270;
    }

    /**
     * Sets the mapping.
     *
     * @param window {left, top, width, height} of the visible window in Camera2 coordinates
     */
    public ViewfinderMapping set(int rotation, boolean mirrored, float[] window) {
        this.rotation = ((rotation % 360) + 360) % 360;
        this.mirrored = mirrored;
        winL = window[0];
        winT = window[1];
        winW = window[2];
        winH = window[3];
        valid = winW > 0 && winH > 0;
        return this;
    }

    public boolean isValid() {
        return valid;
    }

    public int rotation() {
        return rotation;
    }

    public boolean mirrored() {
        return mirrored;
    }

    /** Window width / height in Camera2 units (for converting normalized sizes). */
    public float windowWidth() {
        return winW;
    }

    public float windowHeight() {
        return winH;
    }

    /**
     * Visible window of the ISP preview: the crop region centre-cropped to the view's aspect (turned into sensor
     * orientation). {@code out} = {left, top, width, height}.
     */
    public static float[] ispWindow(float cropL, float cropT, float cropW, float cropH, int viewW, int viewH,
                                    int rotation, float[] out) {
        fit(cropL, cropT, cropW, cropH, viewW, viewH, rotation, out);
        return out;
    }

    /**
     * Visible window of the developed RAW viewfinder, in the (zoomed) Camera2 system.
     *
     * @param activeW     active array width (RAW buffer = active array, as LiveRawRenderer assumes)
     * @param cropNorm    the RAW crop LiveRawRenderer uses: {left, top, width, height} as fractions of the active
     *                    array (SCALER_CROP_REGION of the result, clipped; Xiaomi tele crop already applied)
     * @param zoomRatio   CONTROL_ZOOM_RATIO of the result (1 when unused)
     */
    public static float[] rawWindow(int activeW, int activeH, float[] cropNorm, float zoomRatio, int viewW, int viewH,
                                    int rotation, float[] out) {
        fit(cropNorm[0] * activeW, cropNorm[1] * activeH, cropNorm[2] * activeW, cropNorm[3] * activeH,
                viewW, viewH, rotation, out);
        float z = zoomRatio > 0 ? zoomRatio : 1f;
        if (Math.abs(z - 1f) > 1e-4f) {
            // Unzoomed U -> zoomed A about the array centre: A = c + (U - c) * z.
            float cx = activeW * 0.5f, cy = activeH * 0.5f;
            out[0] = cx + (out[0] - cx) * z;
            out[1] = cy + (out[1] - cy) * z;
            out[2] *= z;
            out[3] *= z;
        }
        return out;
    }

    /** Largest centred sub-rectangle of the crop with the view's aspect in sensor orientation. */
    static void fit(float l, float t, float w, float h, int viewW, int viewH, int rotation, float[] out) {
        out[0] = l;
        out[1] = t;
        out[2] = w;
        out[3] = h;
        if (viewW <= 0 || viewH <= 0 || w <= 0 || h <= 0) return;
        float aspect = quarterTurn(rotation) ? viewH / (float) viewW : viewW / (float) viewH; // sensor-axis w / h
        if (w / h > aspect) {
            float fitted = h * aspect;
            out[0] = l + (w - fitted) * 0.5f;
            out[2] = fitted;
        } else {
            float fitted = w / aspect;
            out[1] = t + (h - fitted) * 0.5f;
            out[3] = fitted;
        }
    }

    /** Normalized view point -> Camera2 point. {@code out} = {x, y}. */
    public void viewToSensor(float u, float v, float[] out) {
        if (mirrored) u = 1f - u;
        float dx = u - 0.5f, dy = v - 0.5f, rx, ry;
        // Visually-clockwise rotation by `rotation` in a y-down system (TouchFocus.mapTapToCrop).
        switch (rotation) {
            case 90: rx = -dy; ry = dx; break;
            case 180: rx = -dx; ry = -dy; break;
            case 270: rx = dy; ry = -dx; break;
            default: rx = dx; ry = dy; break;
        }
        out[0] = winL + (rx + 0.5f) * winW;
        out[1] = winT + (ry + 0.5f) * winH;
    }

    /** Camera2 point -> normalized view point (inverse of {@link #viewToSensor}). {@code out} = {u, v}. */
    public void sensorToView(float x, float y, float[] out) {
        float rx = (x - winL) / winW - 0.5f, ry = (y - winT) / winH - 0.5f, dx, dy;
        switch (rotation) {
            case 90: dx = ry; dy = -rx; break;
            case 180: dx = -rx; dy = -ry; break;
            case 270: dx = -ry; dy = rx; break;
            default: dx = rx; dy = ry; break;
        }
        float u = dx + 0.5f;
        out[0] = mirrored ? 1f - u : u;
        out[1] = dy + 0.5f;
    }

    /** Camera2 rectangle (l, t, r, b) -> normalized view rectangle {l, t, r, b} (ordered). */
    public void sensorRectToView(float l, float t, float r, float b, float[] tmp2, float[] out) {
        sensorToView(l, t, tmp2);
        float u0 = tmp2[0], v0 = tmp2[1];
        sensorToView(r, b, tmp2);
        out[0] = Math.min(u0, tmp2[0]);
        out[1] = Math.min(v0, tmp2[1]);
        out[2] = Math.max(u0, tmp2[0]);
        out[3] = Math.max(v0, tmp2[1]);
    }

    /** Normalized view rectangle (l, t, r, b) -> Camera2 rectangle {l, t, r, b} (ordered). */
    public void viewRectToSensor(float l, float t, float r, float b, float[] tmp2, float[] out) {
        viewToSensor(l, t, tmp2);
        float x0 = tmp2[0], y0 = tmp2[1];
        viewToSensor(r, b, tmp2);
        out[0] = Math.min(x0, tmp2[0]);
        out[1] = Math.min(y0, tmp2[1]);
        out[2] = Math.max(x0, tmp2[0]);
        out[3] = Math.max(y0, tmp2[1]);
    }

    /**
     * A metering rectangle around a Camera2 rectangle: scaled about its centre, at least {@code minSide} on each side,
     * clamped inside the active array {@code [0, activeW) x [0, activeH)}. {@code out} = {x, y, w, h} (integers).
     *
     * @return false when nothing of it lies inside the array
     */
    public static boolean meteringRect(float l, float t, float r, float b, float scale, int minSide,
                                       int activeW, int activeH, int[] out) {
        if (activeW <= 0 || activeH <= 0) return false;
        float cx = (l + r) * 0.5f, cy = (t + b) * 0.5f;
        float w = Math.max(minSide, (r - l) * scale), h = Math.max(minSide, (b - t) * scale);
        w = Math.min(w, activeW);
        h = Math.min(h, activeH);
        if (cx < 0 || cy < 0 || cx > activeW || cy > activeH) return false;
        int x = Math.round(cx - w * 0.5f), y = Math.round(cy - h * 0.5f);
        int iw = Math.max(1, Math.round(w)), ih = Math.max(1, Math.round(h));
        x = Math.max(0, Math.min(x, activeW - iw));
        y = Math.max(0, Math.min(y, activeH - ih));
        out[0] = x;
        out[1] = y;
        out[2] = iw;
        out[3] = ih;
        return true;
    }
}

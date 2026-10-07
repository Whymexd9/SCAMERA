package com.particlesdevs.photoncamera.control;

import android.graphics.Bitmap;

import com.particlesdevs.photoncamera.settings.ModuleRegistry;
import com.particlesdevs.photoncamera.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Continuous zoom over the configured lens buttons (modules).
 * <p>
 * Each visible module (slot) has a zoom ratio. The active module is the one with the largest
 * ratio not above the requested zoom; when it changes the camera session is restarted on that
 * module's Camera ID and profile. The rest of the zoom is a centre crop: {@code residual =
 * max(1, zoom / nativeRatio)} goes to the camera as CONTROL_ZOOM_RATIO (preview) and the finished
 * image is cropped by the same factor (RAW output is not cropped by the camera).
 */
public final class ZoomController {
    private static final String TAG = "ZoomController";
    /** A new module is not selected earlier than this after the previous switch (session restart). */
    private static final long SWITCH_INTERVAL_MS = 700;
    private static final float ROUNDING = 0.05f;

    private static volatile float zoom = 1f;
    private static volatile float residual = 1f;
    private static volatile float shotResidual = 1f;
    private static boolean initialized;
    private static long lastSwitch;
    /** P37: the module the zoom and residual were last set for (a different active module means it changed from outside). */
    private static volatile String zoomSlot;

    private ZoomController() {}

    public static float zoom() { ensureInitialized(); return zoom; }
    public static float residual() { ensureInitialized(); return residual; }
    /** Centre-crop factor of the frame being processed (recorded at the shutter). */
    public static float shotResidual() { return shotResidual; }
    public static void markShot() { shotResidual = residual; }
    /**
     * The crop the camera does not do itself, when a module zooms optically (Xiaomi 17 Ultra tele, XiaomiTeleZoom): set after
     * every zoom change of that module, in place of zoom / nativeRatio.
     */
    public static void overrideResidual(float crop) { residual = Math.max(1f, crop); }

    /** Visible modules of the active side, ascending by zoom ratio. */
    public static List<String> lenses() {
        String active = ModuleRegistry.active();
        String side = active.startsWith("front") ? "front" : "back";
        List<String> out = new ArrayList<>();
        for (String slot : ModuleRegistry.slots())
            if (slot.startsWith(side) && ModuleRegistry.visible(slot)) out.add(slot);
        Collections.sort(out, Comparator.comparingDouble(ModuleRegistry::zoom));
        return out;
    }

    /** Module for a zoom value: largest ratio <= zoom (+ rounding), else the widest one. */
    public static String pick(float z) {
        List<String> slots = lenses();
        if (slots.isEmpty()) return null;
        String best = slots.get(0);
        for (String slot : slots) if (ModuleRegistry.zoom(slot) <= z + ROUNDING) best = slot;
        return best;
    }

    public static float minZoom() {
        List<String> slots = lenses();
        return slots.isEmpty() ? 1f : ModuleRegistry.zoom(slots.get(0));
    }

    public static float maxZoom() {
        List<String> slots = lenses();
        if (slots.isEmpty()) return 4f;
        float top = ModuleRegistry.zoom(slots.get(slots.size() - 1));
        // P41: the Xiaomi 17 Ultra tele goes to 400 mm (17.2×) like the stock dial
        float xiaomi = com.particlesdevs.photoncamera.capture.XiaomiTeleZoom.maxZoom(top);
        return xiaomi > 0f ? xiaomi : Math.min(30f, top * 4f);
    }

    private static void ensureInitialized() {
        if (initialized) return;
        try {
            String active = ModuleRegistry.active();
            zoom = ModuleRegistry.zoom(active);
            residual = Math.max(1f, zoom / ModuleRegistry.nativeRatio(active));
            zoomSlot = active;
            initialized = true;
            Log.d(TAG, "init slot=" + active + " zoom=" + zoom + " native=" + ModuleRegistry.nativeRatio(active) + " residual=" + residual);
        } catch (RuntimeException notReady) {
            // Settings not available yet: neutral zoom until the first use.
        }
    }

    /**
     * P37: the active module changed without a button tap or a zoom step (the camera was changed from outside, the
     * module restored on start, the other side's camera): the zoom goes to the module's own ratio. For the same module
     * (e.g. its ratio edited in the settings) only a zoom outside the module's range [own ratio, next module's ratio) goes
     * there. So the ruler, the residual crop and the next pinch / drag start from the module and not at 1x (a UW module
     * kept the main camera's 1x and residual: label 1x, uncropped 0.6x picture). Not within {@link #SWITCH_INTERVAL_MS}
     * of a switch, when a throttled zoom may legitimately run ahead of the module.
     */
    public static void syncToActive() {
        ensureInitialized();
        if (android.os.SystemClock.elapsedRealtime() - lastSwitch < SWITCH_INTERVAL_MS) return;
        try {
            String active = ModuleRegistry.active();
            if (!ModuleRegistry.slots().contains(active)) return;
            float base = ModuleRegistry.zoom(active);
            float upper = Float.MAX_VALUE;
            for (String slot : lenses()) {
                float r = ModuleRegistry.zoom(slot);
                if (r > base + ROUNDING) upper = Math.min(upper, r);
            }
            float z = zoom;
            if (needsSync(active, zoomSlot, z, base, upper)) {
                zoom = base;
                residual = Math.max(1f, base / ModuleRegistry.nativeRatio(active));
                Log.d(TAG, "sync slot=" + active + " (was " + zoomSlot + ") zoom " + z + " -> " + base + " residual=" + residual);
            }
            zoomSlot = active;
        } catch (RuntimeException notReady) {
            // Settings not available yet.
        }
    }

    /** P37: the zoom moves to the active module's own ratio: another module than the zoom was set for, or out of range. */
    static boolean needsSync(String active, String zoomSlot, float z, float base, float upper) {
        return !active.equals(zoomSlot) || !inRange(z, base, upper);
    }

    /** P37: a zoom belongs to a module with ratio {@code base} when below the next module's ratio {@code upper}. */
    static boolean inRange(float z, float base, float upper) {
        return z >= base - ROUNDING && z < upper + ROUNDING;
    }

    /** A lens button was tapped: zoom goes to that module's own ratio. */
    public static void onButton(String slot) {
        initialized = true;
        zoom = ModuleRegistry.zoom(slot);
        residual = Math.max(1f, zoom / ModuleRegistry.nativeRatio(slot));
        zoomSlot = slot;
        lastSwitch = android.os.SystemClock.elapsedRealtime();
    }

    /**
     * Zoom changed (pinch / dial). Returns the module to switch to, or null when the current
     * module stays; the residual for the module in effect is updated either way.
     */
    public static String onZoomChanged(float requested) {
        ensureInitialized();
        float z = Math.max(minZoom(), Math.min(maxZoom(), requested));
        zoom = z;
        String active = ModuleRegistry.active();
        String target = pick(z);
        String result = null;
        long now = android.os.SystemClock.elapsedRealtime();
        if (target != null && !target.equals(active) && now - lastSwitch >= SWITCH_INTERVAL_MS) {
            result = target;
            lastSwitch = now;
            active = target;
            Log.d(TAG, "zoom " + z + " -> module " + target);
        }
        String inEffect = ModuleRegistry.slots().contains(active) ? active : ModuleRegistry.active();
        residual = Math.max(1f, z / ModuleRegistry.nativeRatio(inEffect));
        zoomSlot = inEffect;
        return result;
    }

    /** Centre crop of a finished image by the zoom factor (aspect ratio kept). */
    public static Bitmap crop(Bitmap image, float factor) {
        if (image == null || factor <= 1.005f) return image;
        int w = Math.max(2, Math.round(image.getWidth() / factor) & ~1);
        int h = Math.max(2, Math.round(image.getHeight() / factor) & ~1);
        int x = (image.getWidth() - w) / 2, y = (image.getHeight() - h) / 2;
        Bitmap out = Bitmap.createBitmap(image, x, y, w, h);
        if (out != image) image.recycle();
        return out;
    }
}

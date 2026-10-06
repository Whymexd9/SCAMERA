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
        return Math.min(30f, ModuleRegistry.zoom(slots.get(slots.size() - 1)) * 4f);
    }

    private static void ensureInitialized() {
        if (initialized) return;
        try {
            String active = ModuleRegistry.active();
            zoom = ModuleRegistry.zoom(active);
            residual = Math.max(1f, zoom / ModuleRegistry.nativeRatio(active));
            initialized = true;
            Log.d(TAG, "init slot=" + active + " zoom=" + zoom + " native=" + ModuleRegistry.nativeRatio(active) + " residual=" + residual);
        } catch (RuntimeException notReady) {
            // Settings not available yet: neutral zoom until the first use.
        }
    }

    /** A lens button was tapped: zoom goes to that module's own ratio. */
    public static void onButton(String slot) {
        initialized = true;
        zoom = ModuleRegistry.zoom(slot);
        residual = Math.max(1f, zoom / ModuleRegistry.nativeRatio(slot));
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
        residual = Math.max(1f, z / ModuleRegistry.nativeRatio(ModuleRegistry.slots().contains(active) ? active : ModuleRegistry.active()));
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

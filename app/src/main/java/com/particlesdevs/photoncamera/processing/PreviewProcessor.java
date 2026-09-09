package com.particlesdevs.photoncamera.processing;

import android.graphics.Bitmap;

import com.particlesdevs.photoncamera.processing.opengl.postpipeline.PostPipeline;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Live viewfinder: runs a single raw frame through the short preview pipeline and
 * hands back a bitmap showing what the colour and tone processing will do.
 *
 * <p>This is not the saved photo. Everything that needs a burst - alignment,
 * merging, MFSR, multi-frame denoise - is absent, so the preview is noisier and
 * softer than the result. It shows the look, not the final detail.
 *
 * <p>Frames are dropped rather than queued. The pipeline takes tens of
 * milliseconds and the camera delivers faster than that, so anything else would
 * build a backlog and show the viewfinder further and further behind reality.
 * One frame in flight at a time; whatever arrives meanwhile is discarded.
 */
public class PreviewProcessor {

    private static final String TAG = "PreviewProcessor";

    /** Guards the single in-flight frame. */
    private final AtomicBoolean busy = new AtomicBoolean(false);

    /** Rolling average of pipeline time, for the debug log. */
    private double avgMs = 0.0;
    private long frames = 0;

    public interface ResultListener {
        void onPreviewReady(Bitmap bitmap);
    }

    private volatile ResultListener listener;

    public void setListener(ResultListener l) {
        this.listener = l;
    }

    /** True while a frame is being processed, so callers can drop instead of queueing. */
    public boolean isBusy() {
        return busy.get();
    }

    /**
     * Process one raw frame. Returns immediately and does nothing if a frame is
     * already in flight.
     *
     * @param raw        the raw frame; must stay valid until this call returns
     * @param parameters sensor and shot parameters for this frame
     */
    public void submit(ByteBuffer raw, Parameters parameters) {
        if (raw == null || parameters == null) return;
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        try {
            long t0 = System.nanoTime();
            PostPipeline pipeline = new PostPipeline();
            pipeline.previewMode = true;
            Bitmap out = pipeline.Run(raw, parameters);
            double ms = (System.nanoTime() - t0) / 1e6;

            frames++;
            avgMs += (ms - avgMs) / frames;
            if (frames % 30 == 0) {
                Log.d(TAG, "preview pipeline " + String.format("%.1f", ms) + " ms"
                        + " (avg " + String.format("%.1f", avgMs) + " ms over "
                        + frames + " frames)");
            }

            ResultListener l = listener;
            if (l != null && out != null) {
                l.onPreviewReady(out);
            }
        } catch (Throwable t) {
            // A failed preview frame must never take the camera down: drop it,
            // log once, and let the next one try.
            Log.e(TAG, "preview frame failed: " + t);
        } finally {
            busy.set(false);
        }
    }

    public void reset() {
        listener = null;
        avgMs = 0.0;
        frames = 0;
    }
}

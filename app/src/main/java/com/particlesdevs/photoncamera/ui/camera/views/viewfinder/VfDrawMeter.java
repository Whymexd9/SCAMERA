package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import android.os.SystemClock;

import com.particlesdevs.photoncamera.util.Log;

import java.util.Locale;

/**
 * P57: what the user sees of the viewfinder while a shot is processed. The camera keeps delivering ~30 fps after the shutter
 * (STAB_TRACE, X300U log 2026-10-08), so a stutter is in drawing: this counts the frames the viewfinder renderer DRAWS, per
 * 2 s, from the shutter press for {@link #WINDOW_MS} (one line per period: "VF_DRAW t=+4.0s drawn=58 maxGap=41ms
 * draw=1.2/6.8ms"). Next to the shot timeline it shows which stage costs frames. Called on the GL thread only.
 */
public final class VfDrawMeter {
    private static final String TAG = "VF_DRAW";
    static final long PERIOD_MS = 2000, WINDOW_MS = 30000;

    private static volatile long shotAtMs = -1;
    private static long periodStartMs = -1, lastFrameMs = -1;
    private static int drawn;
    private static long maxGapMs;
    private static double drawSumMs, drawMaxMs;

    private VfDrawMeter() {}

    /** The shutter was pressed: the next {@link #WINDOW_MS} are measured. */
    public static void shot() {
        shotAtMs = SystemClock.elapsedRealtime();
    }

    /** One drawn frame ({@code drawNs}: time spent in onDrawFrame). */
    static void frame(long drawNs) {
        final long shot = shotAtMs;
        if (shot < 0) return;
        final long now = SystemClock.elapsedRealtime();
        if (now - shot > WINDOW_MS + PERIOD_MS) {
            if (periodStartMs >= 0) flush(now, shot);
            shotAtMs = -1;
            periodStartMs = -1;
            lastFrameMs = -1;
            return;
        }
        if (periodStartMs < 0 || periodStartMs < shot) { // a new shot restarts the periods
            reset(shot);
        }
        if (lastFrameMs >= 0) maxGapMs = Math.max(maxGapMs, now - lastFrameMs);
        lastFrameMs = now;
        ++drawn;
        final double ms = drawNs / 1e6;
        drawSumMs += ms;
        drawMaxMs = Math.max(drawMaxMs, ms);
        if (now - periodStartMs >= PERIOD_MS) {
            flush(now, shot);
            periodStartMs = now;
        }
    }

    private static void reset(long start) {
        periodStartMs = start;
        lastFrameMs = -1;
        drawn = 0;
        maxGapMs = 0;
        drawSumMs = drawMaxMs = 0;
    }

    private static void flush(long now, long shot) {
        Log.i(TAG, line(now - shot, now - periodStartMs, drawn, maxGapMs, drawn > 0 ? drawSumMs / drawn : 0, drawMaxMs));
        drawn = 0;
        maxGapMs = 0;
        drawSumMs = drawMaxMs = 0;
    }

    static String line(long sinceShotMs, long periodMs, int frames, long maxGap, double drawMean, double drawMax) {
        final double fps = periodMs > 0 ? frames * 1000.0 / periodMs : 0;
        return String.format(Locale.ROOT, "t=+%.1fs drawn=%d (%.1f fps) maxGap=%dms draw=%.1f/%.1fms",
                sinceShotMs / 1000.0, frames, fps, maxGap, drawMean, drawMax);
    }
}

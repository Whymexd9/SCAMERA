package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import android.opengl.GLES20;

import com.particlesdevs.photoncamera.processing.opengl.GLContext;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * P312 (owner, 2026-10-21, a video of the camera's start: «вот такие лаги ловлю при запуске» - the viewfinder stood still for
 * 0.4 s at every start, 2.1 s at a cold one, just after it appeared): the developed-RAW viewfinder compiled its programs from
 * source on the viewfinder's GL thread at the first RAW frame (rawdevelop and the ten programs of the photo's ARK tone) while
 * no ISP frame could be drawn. Their driver binaries are now kept with the post pipeline's (GLProg), so a start links them in a
 * few ms; MainRenderer keeps the ISP frame until they are linked. A binary that is missing (the first start after an install or
 * a driver update) is compiled here, in a context of its own on a background thread, while the ISP viewfinder keeps running.
 */
final class ViewfinderPrograms {
    private static final String TAG = "ViewfinderPrograms";
    private static final Object LOCK = new Object();
    /** Keys being compiled in the background now; keys sent there once in this process. */
    private static final Set<String> PENDING = new HashSet<>(), TRIED = new HashSet<>();

    private ViewfinderPrograms() {
    }

    /**
     * Compiles the keys whose driver binary is not kept, on a background thread (once per key at a time). Called on the
     * viewfinder's GL thread (a current context: the driver string of the binaries' names); never throws.
     */
    static void prepare(List<String> keys) {
        final List<String> missing = new ArrayList<>();
        try {
            for (String key : keys) if (!GLProg.hasBinary(key)) missing.add(key);
        } catch (RuntimeException e) {
            Log.w(TAG, "binary check: " + e);
            return;
        }
        synchronized (LOCK) {
            TRIED.addAll(missing);   // sent to the background (a key whose binary was there is not)
            missing.removeAll(PENDING);
            if (missing.isEmpty()) return;
            PENDING.addAll(missing);
        }
        try {
            final Thread t = new Thread(() -> compile(missing), "vf-programs");
            t.setDaemon(true);
            t.start();
        } catch (Throwable e) {
            synchronized (LOCK) {
                PENDING.removeAll(missing);
            }
            Log.w(TAG, "background compile not started: " + e);
        }
    }

    /** Whether the background thread is linking now (the GL thread then loads no binary: a driver's compiler lock would hold it). */
    static boolean busy() {
        synchronized (LOCK) {
            return !PENDING.isEmpty();
        }
    }

    /** Whether {@code key} was sent to the background in this process already (a binary refused or never kept: compiled in place). */
    static boolean tried(String key) {
        synchronized (LOCK) {
            return TRIED.contains(key);
        }
    }

    /** Whether {@code key} is being compiled in the background (the GL thread then waits for its binary instead of compiling). */
    static boolean pending(String key) {
        synchronized (LOCK) {
            return PENDING.contains(key);
        }
    }

    private static void compile(List<String> keys) {
        final long t0 = android.os.SystemClock.elapsedRealtime();
        GLContext context = null;
        int done = 0;
        try {
            // the compile it replaces ran on the viewfinder's GL thread, at its display priority
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
            context = new GLContext(1, 1);
            for (String key : keys) {
                final int program = GLProg.linkViewfinderProgram(key);
                if (program != 0) {
                    GLES20.glDeleteProgram(program);
                    done++;
                }
                synchronized (LOCK) {
                    PENDING.remove(key);
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "background compile: " + e);
        } finally {
            synchronized (LOCK) {
                PENDING.removeAll(keys);
            }
            if (context != null) try {
                context.close();
            } catch (RuntimeException ignored) {
            }
        }
        Log.i(TAG, "viewfinder programs: " + done + " of " + keys.size() + " compiled in the background in "
                + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
    }
}

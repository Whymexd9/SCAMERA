package com.particlesdevs.photoncamera.capture;

import android.content.Context;
import android.os.Handler;
import android.util.Log;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

final class VivoVcf2Root implements AutoCloseable {
    interface Listener {
        void onJpeg(long id, byte[] bytes);
        void onFinal(long id, long timestamp);
        void onFailure(long id, String reason);
    }

    private final Handler handler;
    private final Listener listener;
    private final Runnable openTimeout;
    private DataOutputStream commands;
    private boolean ready, closed;
    private long pending;
    private Runnable armedCallback;

    VivoVcf2Root(Context context, Handler handler, Listener listener) {
        this.handler = handler;
        this.listener = listener;
        String apk = context.getApplicationInfo().sourceDir;
        String libraryPath = context.getApplicationInfo().nativeLibraryDir
                + ":" + apk + "!/lib/arm64-v8a";
        String command = "export CLASSPATH=" + quote(apk)
                + "; exec /system/bin/app_process64 " + quote("-Djava.library.path=" + libraryPath)
                + " /system/bin " + VivoVcf2RootWorker.class.getName() + " " + quote(context.getPackageName());
        openTimeout = () -> fatal("VCF2 root connection timed out");
        if (!handler.postDelayed(openTimeout, 30_000L))
            throw new IllegalStateException("VCF2 handler stopped");
        Thread reader = new Thread(() -> run(command), "SCAMERA-VCF2-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private static String quote(String text) { return "'" + text.replace("'", "'\\''") + "'"; }

    synchronized void arm(long id, Runnable callback) throws IOException {
        if (closed || pending != 0 || id <= 0) throw new IOException("VCF2 root unavailable");
        pending = id;
        armedCallback = callback;
        if (ready) command(VivoVcf2Wire.ARM, id);
    }

    synchronized void retire(long id) {
        if (pending != id) return;
        pending = 0;
        armedCallback = null;
        if (ready && !closed) {
            try { command(VivoVcf2Wire.RETIRE, id); }
            catch (IOException failure) { fatal(failure.toString()); }
        }
    }

    private void command(int type, long id) throws IOException {
        commands.writeInt(type);
        commands.writeLong(id);
        commands.flush();
    }

    private void run(String command) {
        Process child = null;
        try {
            synchronized (this) { if (closed) return; }
            child = new ProcessBuilder("su", "-c", command).start();
            synchronized (this) {
                commands = new DataOutputStream(child.getOutputStream());
                if (closed) { commands.close(); return; }
            }
            final InputStream errors = child.getErrorStream();
            Thread stderr = new Thread(() -> drainErrors(errors), "SCAMERA-VCF2-stderr");
            stderr.setDaemon(true);
            stderr.start();
            try (DataInputStream input = new DataInputStream(child.getInputStream())) {
                if (input.readInt() != VivoVcf2Wire.MAGIC || input.readInt() != VivoVcf2Wire.VERSION)
                    throw new IOException("VCF2 root protocol mismatch");
                while (true) {
                    int type = input.readInt();
                    long id = input.readLong();
                    switch (type) {
                        case VivoVcf2Wire.READY:
                            synchronized (this) {
                                if (closed) return;
                                if (ready || id != 0) throw new IOException("Invalid VCF2 readiness");
                                ready = true;
                                handler.removeCallbacks(openTimeout);
                                if (pending != 0) command(VivoVcf2Wire.ARM, pending);
                            }
                            Log.i("NICE_CAPTURE", "VCF2 root connected; firmware verified; RAW-series delivery=false");
                            break;
                        case VivoVcf2Wire.ARMED:
                            post(() -> {
                                Runnable callback;
                                synchronized (this) {
                                    if (pending != id) return;
                                    callback = armedCallback;
                                    armedCallback = null;
                                }
                                if (callback != null) callback.run();
                            });
                            break;
                        case VivoVcf2Wire.JPEG:
                            byte[] bytes = VivoVcf2Wire.readJpeg(input);
                            post(() -> { if (accepts(id)) listener.onJpeg(id, bytes); });
                            break;
                        case VivoVcf2Wire.RESULT:
                            long timestamp = input.readLong();
                            if (timestamp <= 0) throw new IOException("Invalid VCF2 final timestamp");
                            post(() -> { if (accepts(id)) listener.onFinal(id, timestamp); });
                            break;
                        case VivoVcf2Wire.ERROR:
                            String reason = input.readUTF();
                            if (id == 0) { fatal(reason); return; }
                            post(() -> { if (accepts(id)) listener.onFailure(id, reason); });
                            break;
                        default: throw new IOException("Unknown VCF2 root event " + type);
                    }
                }
            }
        } catch (IOException | RuntimeException failure) { fatal("VCF2 root: " + failure); }
        finally {
            if (child != null) {
                try { child.getOutputStream().close(); } catch (IOException ignored) { }
                try { if (!child.waitFor(5, TimeUnit.SECONDS)) child.destroyForcibly(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); child.destroyForcibly(); }
            }
        }
    }

    private void drainErrors(InputStream errors) {
        try (InputStream stream = errors) {
            byte[] chunk = new byte[1024];
            int remaining = 8192, count;
            while ((count = stream.read(chunk)) != -1) {
                if (remaining > 0) {
                    int length = Math.min(count, remaining);
                    Log.e("NICE_CAPTURE", new String(chunk, 0, length, java.nio.charset.StandardCharsets.UTF_8));
                    remaining -= length;
                }
            }
        } catch (IOException ignored) { }
    }

    private synchronized boolean accepts(long id) { return !closed && id > 0 && pending == id; }

    private void post(Runnable callback) {
        if (!handler.post(() -> { synchronized (this) { if (closed) return; } callback.run(); }))
            close();
    }

    private void fatal(String reason) {
        synchronized (this) {
            if (closed) return;
            close();
        }
        Log.e("NICE_CAPTURE", reason);
        handler.post(() -> listener.onFailure(0, reason));
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        ready = false;
        pending = 0;
        armedCallback = null;
        handler.removeCallbacks(openTimeout);
        if (commands != null) try { commands.close(); } catch (IOException ignored) { }
        // Closing stdin also terminates a worker stuck in initialization via
        // its independent command reader and three-second shutdown deadline.
    }
}

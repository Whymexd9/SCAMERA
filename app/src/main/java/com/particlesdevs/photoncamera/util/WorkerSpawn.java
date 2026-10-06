package com.particlesdevs.photoncamera.util;

import android.content.Context;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Native worker without root: the executable ships in the APK as
 * lib/arm64-v8a/libscamera_worker.so (installed executable in nativeLibraryDir),
 * next to the QNN / CRE runtime libraries, and runs as a child of the app.
 */
public final class WorkerSpawn {
    static { System.loadLibrary("allocator"); }
    public static final String WORKER = "libscamera_worker.so";

    private static native int[] spawn(String[] argv, String[] env, int[] fds);
    private static native int waitFor(int pid, long timeoutMs);

    public static File nativeDir(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir);
    }
    /** Worker and runtime installed as native libraries (full build with extracted libs). */
    public static boolean available(Context context) {
        return new File(nativeDir(context), WORKER).canExecute();
    }
    /** A runtime library installed next to the worker, or null. */
    public static File library(Context context, String name) {
        File file = new File(nativeDir(context), name);
        return file.isFile() ? file : null;
    }
    /** Library search paths for the worker: its runtime first, then the job directory. */
    public static String[] environment(Context context, File jobDir) {
        String libs = nativeDir(context).getAbsolutePath(), job = jobDir.getAbsolutePath();
        return new String[]{
                "LD_LIBRARY_PATH=/system/lib64:/system_ext/lib64:" + libs + ":" + job + ":/vendor/lib64",
                "ADSP_LIBRARY_PATH=" + libs + ";" + job + ";/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/vendor/dsp;/system/lib/rfsa/adsp",
                "PATH=/system/bin",
                "TMPDIR=" + jobDir.getAbsolutePath()};
    }

    /** A running worker: spawned directly, or a root (su) process. */
    public static final class Child {
        private final Process process;
        private final int pid;
        public final InputStream output;
        private int exit = -1;
        private Child(int pid, int fd) {
            process = null;
            this.pid = pid;
            output = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(fd));
        }
        private Child(Process process) throws IOException {
            this.process = process;
            pid = -1;
            output = process.getInputStream();
            process.getOutputStream().close();
        }
        public static Child of(Process process) throws IOException { return new Child(process); }
        /** True when the worker ended within the timeout. */
        public boolean waitFor(long timeoutMs) throws InterruptedException {
            if (process != null) return process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (exit >= 0) return true;
            int r = WorkerSpawn.waitFor(pid, timeoutMs);
            if (r >= 0) exit = r;
            return r >= 0;
        }
        public int exitValue() { return process != null ? process.exitValue() : exit; }
        public boolean isAlive() { return process != null ? process.isAlive() : exit < 0; }
        public void destroy() {
            if (process != null) { process.destroyForcibly(); return; }
            if (exit >= 0) return;
            try { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL); } catch (Exception ignored) {}
            try { waitFor(5000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    /** Starts nativeDir/libscamera_worker.so with args; fds become 3, 4, ... in the child. */
    public static Child start(Context context, List<String> args, String[] env, ParcelFileDescriptor... fds) throws IOException {
        String[] argv = new String[args.size() + 1];
        argv[0] = new File(nativeDir(context), WORKER).getAbsolutePath();
        for (int i = 0; i < args.size(); i++) argv[i + 1] = args.get(i);
        int[] raw = new int[fds.length];
        for (int i = 0; i < fds.length; i++) raw[i] = fds[i].getFd();
        int[] r = spawn(argv, env, raw);
        if (r == null) throw new IOException(Lang.t("Не удалось запустить обработчик", "Could not start the processor"));
        return new Child(r[0], r[1]);
    }
}

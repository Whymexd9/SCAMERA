package com.particlesdevs.photoncamera.util;

/**
 * The merge worker's environment (WorkerSpawn.environment), apart from WorkerSpawn's native library so it can be unit tested.
 */
public final class WorkerEnv {
    private WorkerEnv() {}

    /**
     * P242 (speed-ux B6): {@code normalPriority} adds SCAM_GPU_NORMAL_PRIORITY=1, so the worker's GL contexts keep the normal GPU
     * priority instead of P57's low one (scam-superres-gpu.h scamProcessingContext) - the roadmap's A/B: the merge took 0.86-1.15 s
     * a frame in the app against ~0.63 s in the P204 replay while the viewfinder was the cheap ISP one, so the low priority may
     * only slow the merge down. Results are unchanged either way (a scheduling hint).
     */
    public static String[] build(String libs, String job, boolean normalPriority) {
        return build(libs, job, normalPriority, KEEP_NICE);
    }

    /** P371: no SCAM_WORKER_NICE line: the worker keeps the nice value of the thread that spawns it. */
    public static final int KEEP_NICE = Integer.MIN_VALUE;

    /**
     * P371 (speed): {@code nice} (-8..19) adds SCAM_WORKER_NICE, which worker-spawn.cpp sets in the child before execve: the
     * worker forked from the processing thread (Java priority 4: nice 10) ran its whole CPU-bound front end (homographies'
     * RANSAC, the ultrashort checks, Shasta / Bento) below every nice-0 thread of the app. Results are unchanged (scheduling only).
     */
    public static String[] build(String libs, String job, boolean normalPriority, int nice) {
        final java.util.List<String> env = new java.util.ArrayList<>(java.util.Arrays.asList(
                "LD_LIBRARY_PATH=/system/lib64:/system_ext/lib64:" + libs + ":" + job + ":/vendor/lib64",
                "ADSP_LIBRARY_PATH=" + libs + ";" + job + ";/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/vendor/dsp;/system/lib/rfsa/adsp",
                "PATH=/system/bin",
                "TMPDIR=" + job));
        if (normalPriority) env.add("SCAM_GPU_NORMAL_PRIORITY=1");
        if (nice >= -8 && nice <= 19) env.add("SCAM_WORKER_NICE=" + nice);
        return env.toArray(new String[0]);
    }

    /**
     * P371: the worker's nice from scam_dev worker_nice: default 10, the processing thread's (Java priority 4), as before P371 -
     * review M1: the camera thread, the viewfinder and the worker's own driver compiles run beside it; 0 is the phone A/B. Out of
     * -8..19: the spawning thread's.
     */
    public static final int DEFAULT_NICE = 10;

    public static int workerNice() {
        try {
            final float v = com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevNumber("worker_nice", DEFAULT_NICE);
            return v >= -8f && v <= 19f ? Math.round(v) : KEEP_NICE;
        } catch (RuntimeException | LinkageError e) {
            return DEFAULT_NICE;
        }
    }

    /** P242: scam_dev worker_gpu_normal_priority 1 - the merge worker's GPU contexts at the normal priority (default 0: medium, P364). */
    public static boolean gpuNormalPriority() {
        try {
            return com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevSwitch("worker_gpu_normal_priority", false);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}

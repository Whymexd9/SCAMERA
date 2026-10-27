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
        final java.util.List<String> env = new java.util.ArrayList<>(java.util.Arrays.asList(
                "LD_LIBRARY_PATH=/system/lib64:/system_ext/lib64:" + libs + ":" + job + ":/vendor/lib64",
                "ADSP_LIBRARY_PATH=" + libs + ";" + job + ";/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/vendor/dsp;/system/lib/rfsa/adsp",
                "PATH=/system/bin",
                "TMPDIR=" + job));
        if (normalPriority) env.add("SCAM_GPU_NORMAL_PRIORITY=1");
        return env.toArray(new String[0]);
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

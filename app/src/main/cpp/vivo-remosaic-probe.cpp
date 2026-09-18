#include <jni.h>
#include <dlfcn.h>
#include <string>

// Keep successful handles until process exit. No guessed vendor function
// signature, calibration buffer or remosaic mode is passed to these libraries.
extern "C" JNIEXPORT jstring JNICALL
Java_com_particlesdevs_photoncamera_processing_opengl_postpipeline_VivoRemosaicAvailability_nativeProbe(
        JNIEnv* env, jclass) {
#if defined(__aarch64__)
    static void* handles[2] = {};
    const char* paths[] = {"/vendor/lib64/libremosaic_wrapper.so",
                           "/vendor/lib64/libremosaiclib_s5khp3.so"};
    std::string report;
    for (int i = 0; i < 2; ++i) {
        if (!handles[i]) handles[i] = dlopen(paths[i], RTLD_NOW | RTLD_LOCAL);
        report += paths[i];
        if (!handles[i]) {
            const char* error = dlerror();
            report += "\nLOAD FAILED: ";
            report += error ? error : "unknown linker error";
            report += "\n";
        } else {
            report += "\nLOAD OK\n";
        }
    }
    if (handles[0]) {
        const char* symbols[] = {"remosaic_init", "remosaic_gainmap_gen",
            "remosaic_process_param_set", "remosaic_process_gain_set",
            "remosaic_process", "remosaic_deinit"};
        for (const char* symbol : symbols) {
            report += symbol;
            report += dlsym(handles[0], symbol) ? ": FOUND\n" : ": MISSING\n";
        }
    }
    report += "No remosaic functions called. Calibration, HP9 4x ISZ mode and processing ABI remain unverified.";
    return env->NewStringUTF(report.c_str());
#else
    return env->NewStringUTF("Stock Vivo probe requires an ARM64 process. No vendor library loaded.");
#endif
}

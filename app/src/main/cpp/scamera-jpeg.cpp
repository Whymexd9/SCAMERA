// JNI of JpegliEncoder: an RGBA_8888 Bitmap to a 4:4:4 JPEG written into a java.io.OutputStream (scamera-jpeg-core.h).
#include <android/bitmap.h>
#include <jni.h>

#include <algorithm>
#include <exception>
#include <string>

#include "scamera-jpeg-core.h"
#include "scamera-jpeg-parallel.h"

namespace {
struct PixelLock {
    JNIEnv* env;
    jobject bitmap;
    void* pixels = nullptr;
    PixelLock(JNIEnv* e, jobject b) : env(e), bitmap(b) {
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) pixels = nullptr;
    }
    ~PixelLock() { if (pixels) AndroidBitmap_unlockPixels(env, bitmap); }
};
}  // namespace

// Returns null on success, else the reason (nothing usable was written when it fails before the first chunk).
// P147: threads > 1 and at least 4 MP: strips on that many threads (scamera-jpeg-parallel.h); when the strips cannot be
// joined (nothing written yet) the one-thread progressive encode runs. *modeOut: "strips N" / "one thread".
extern "C" JNIEXPORT jstring JNICALL
Java_com_particlesdevs_photoncamera_processing_JpegliEncoder_encode(JNIEnv* env, jclass, jobject bitmap, jint quality,
                                                                    jobject out, jint threads, jintArray modeOut) {
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS)
        return env->NewStringUTF("bitmap info unavailable");
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return env->NewStringUTF("bitmap is not RGBA_8888");
    jclass streamClass = env->GetObjectClass(out);
    jmethodID write = env->GetMethodID(streamClass, "write", "([BII)V");
    env->DeleteLocalRef(streamClass);
    if (write == nullptr) { env->ExceptionClear(); return env->NewStringUTF("OutputStream.write missing"); }
    jbyteArray chunk = env->NewByteArray(1 << 20);
    if (chunk == nullptr) { env->ExceptionClear(); return env->NewStringUTF("no memory for the output chunk"); }
    std::string error;
    {
        PixelLock lock(env, bitmap);
        if (lock.pixels == nullptr) {
            error = "bitmap pixels unavailable";
        } else {
            const scamera_jpeg::Writer writer = [&](const uint8_t* data, size_t size) {
                for (size_t at = 0; at < size;) {
                    const jsize n = jsize(std::min<size_t>(size - at, size_t(1) << 20));
                    env->SetByteArrayRegion(chunk, 0, n, reinterpret_cast<const jbyte*>(data + at));
                    env->CallVoidMethod(out, write, chunk, 0, n);
                    if (env->ExceptionCheck()) return false;
                    at += size_t(n);
                }
                return true;
            };
            bool written = false;
            bool started = false;
            const scamera_jpeg::Writer counting = [&](const uint8_t* data, size_t size) {
                started = true;
                return writer(data, size);
            };
            jint mode = 1;
            if (threads > 1 && uint64_t(info.width) * info.height >= 4000000ull) {
                try {
                    scamera_jpeg::parallel::encodeRgba(static_cast<const uint8_t*>(lock.pixels), int(info.width), int(info.height),
                                                       info.stride, quality, int(threads), 0, counting);
                    written = true;
                    mode = threads;
                } catch (const std::exception& e) {
                    if (started) error = e.what();   // part of a file is out: the caller starts again with Android's encoder
                }
            }
            if (!written && error.empty()) {
                try {
                    scamera_jpeg::encodeRgba(static_cast<const uint8_t*>(lock.pixels), int(info.width), int(info.height),
                                             info.stride, quality, writer);
                } catch (const std::exception& e) {
                    error = e.what();
                }
            }
            if (modeOut != nullptr && env->GetArrayLength(modeOut) > 0) env->SetIntArrayRegion(modeOut, 0, 1, &mode);
        }
    }
    env->DeleteLocalRef(chunk);
    if (env->ExceptionCheck()) {
        // The Java stream threw (disk full, closed): report it as the failure, the caller decides.
        env->ExceptionClear();
        if (error.empty()) error = "JPEG output stream failed";
    }
    return error.empty() ? nullptr : env->NewStringUTF(error.c_str());
}

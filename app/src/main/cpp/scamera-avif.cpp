// JNI of processing/avif/AvifEncoder: an RGBA_8888 or RGBA_1010102 Bitmap to an AVIF file (scamera-avif-core.h).
// Never throws into Java: every failure comes back as a reason string, the caller saves the photo as JPEG instead.
#include <android/bitmap.h>
#include <jni.h>

#include <exception>
#include <new>
#include <string>
#include <vector>

#include "scamera-avif-core.h"

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

// ANDROID_BITMAP_FORMAT_RGBA_1010102 (API 30 headers); the value is fixed by the NDK ABI.
constexpr int32_t kFormatRgba1010102 = 10;

std::string encode(JNIEnv* env, jobject bitmap, jstring path, const scamera_avif::Options& options, jbyteArray exif,
                   scamera_avif::Stats* stats) {
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return "bitmap info unavailable";
    scamera_avif::Layout layout;
    if (info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
        layout = scamera_avif::Layout::Rgba8888;
    } else if (int32_t(info.format) == kFormatRgba1010102) {
        layout = scamera_avif::Layout::Rgba1010102;
    } else {
        return "bitmap format " + std::to_string(info.format) + " is neither RGBA_8888 nor RGBA_1010102";
    }
    std::vector<uint8_t> exifBytes;
    if (exif != nullptr) {
        const jsize n = env->GetArrayLength(exif);
        exifBytes.resize(size_t(n));
        if (n > 0) env->GetByteArrayRegion(exif, 0, n, reinterpret_cast<jbyte*>(exifBytes.data()));
        if (env->ExceptionCheck()) return "EXIF block unreadable";
    }
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return "path unavailable";
    const std::string file(chars);
    env->ReleaseStringUTFChars(path, chars);

    std::string error;
    scamera_avif::ImagePtr image;
    {
        // The pixels are only needed for the conversion: the lock ends before the (long) AV1 encode.
        PixelLock lock(env, bitmap);
        if (lock.pixels == nullptr) return "bitmap pixels unavailable";
        image = scamera_avif::toYuv(static_cast<const uint8_t*>(lock.pixels), int(info.width), int(info.height),
                                    info.stride, layout, options, stats, &error);
    }
    if (!image) return error.empty() ? "RGB to YUV failed" : error;
    return scamera_avif::encodeImage(image.get(), options, exifBytes.empty() ? nullptr : exifBytes.data(),
                                     exifBytes.size(), file, stats);
}
}  // namespace

// Returns null on success, else the reason (no file is left behind). primaries / transfer / matrix: the H.273 colour of
// the file (1 / 13 / 1 by default), icc: an ICC profile or null (P46). stats (long[6] or null) receives: bit depth,
// 1 when 4:4:4, conversion ms, encode ms, file bytes, 1 when the EXIF block was stored.
extern "C" JNIEXPORT jstring JNICALL
Java_com_particlesdevs_photoncamera_processing_avif_AvifEncoder_encode(JNIEnv* env, jclass, jobject bitmap, jstring path,
                                                                       jint quality, jboolean lossless, jint depth,
                                                                       jboolean yuv444, jint speed, jint threads,
                                                                       jbyteArray exif, jint primaries, jint transfer,
                                                                       jint matrix, jbyteArray icc, jlongArray stats) {
    scamera_avif::Options options;
    options.quality = quality;
    options.lossless = lossless == JNI_TRUE;
    options.depth = depth;
    options.yuv444 = yuv444 == JNI_TRUE;
    options.speed = speed;
    options.threads = threads;
    options.primaries = primaries;
    options.transfer = transfer;
    options.matrix = matrix;
    scamera_avif::Stats result;
    std::string error;
    try {
        if (icc != nullptr) {
            const jsize n = env->GetArrayLength(icc);
            options.icc.resize(size_t(n));
            if (n > 0) env->GetByteArrayRegion(icc, 0, n, reinterpret_cast<jbyte*>(options.icc.data()));
            if (env->ExceptionCheck()) error = "ICC profile unreadable";
        }
        if (error.empty()) error = encode(env, bitmap, path, options, exif, &result);
    } catch (const std::bad_alloc&) {
        error = "out of memory";
    } catch (const std::exception& e) {
        error = e.what();
    } catch (...) {
        error = "unknown native error";
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        if (error.empty()) error = "JNI exception";
    }
    if (error.empty() && stats != nullptr && env->GetArrayLength(stats) >= 6) {
        const jlong values[6] = {result.depth, result.yuv444 ? 1 : 0, jlong(result.convertMs + 0.5),
                                 jlong(result.encodeMs + 0.5), jlong(result.bytes), result.exif ? 1 : 0};
        env->SetLongArrayRegion(stats, 0, 6, values);
    }
    return error.empty() ? nullptr : env->NewStringUTF(error.c_str());
}

// P65: processing/avif/AvifDecoder.decode: the first image of an AVIF file (bytes) as RGBA_8888 rows (no padding) of at most
// maxSide px on the long side; size (int[4] or null) receives width, height, source width, source height. Returns null on
// failure (the reason goes to error[0] when error is a String[1]); never throws into Java.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_particlesdevs_photoncamera_processing_avif_AvifDecoder_decode(JNIEnv* env, jclass, jbyteArray data, jint maxSide,
                                                                       jint threads, jintArray size, jobjectArray error) {
    std::string reason;
    jbyteArray result = nullptr;
    try {
        const jsize n = data == nullptr ? 0 : env->GetArrayLength(data);
        std::vector<uint8_t> bytes(static_cast<size_t>(n));
        if (n > 0) env->GetByteArrayRegion(data, 0, n, reinterpret_cast<jbyte*>(bytes.data()));
        if (env->ExceptionCheck()) reason = "data unreadable";
        scamera_avif::Decoded decoded;
        if (reason.empty()) reason = scamera_avif::decodeToRgba8(bytes.data(), bytes.size(), maxSide, threads, &decoded);
        if (reason.empty()) {
            std::vector<uint8_t>().swap(bytes);
            result = env->NewByteArray(jsize(decoded.rgba.size()));
            if (result == nullptr) reason = "out of memory";
            else env->SetByteArrayRegion(result, 0, jsize(decoded.rgba.size()), reinterpret_cast<const jbyte*>(decoded.rgba.data()));
            if (size != nullptr && env->GetArrayLength(size) >= 4) {
                const jint dims[4] = {jint(decoded.width), jint(decoded.height), jint(decoded.sourceWidth), jint(decoded.sourceHeight)};
                env->SetIntArrayRegion(size, 0, 4, dims);
            }
        }
    } catch (const std::bad_alloc&) {
        reason = "out of memory";
    } catch (const std::exception& e) {
        reason = e.what();
    } catch (...) {
        reason = "unknown native error";
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        if (reason.empty()) reason = "JNI exception";
    }
    if (!reason.empty()) {
        if (error != nullptr && env->GetArrayLength(error) >= 1) {
            jstring s = env->NewStringUTF(reason.c_str());
            if (s != nullptr) env->SetObjectArrayElement(error, 0, s);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
        return nullptr;
    }
    return result;
}

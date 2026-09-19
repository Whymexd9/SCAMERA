#include <jni.h>
#include <android/bitmap.h>
#include "lanczos-downscale.h"

class BitmapPixels {
    JNIEnv* env;
    jobject bitmap;
public:
    void* data=nullptr;
    BitmapPixels(JNIEnv* e,jobject b):env(e),bitmap(b) {
        if (AndroidBitmap_lockPixels(e,b,&data)!=ANDROID_BITMAP_RESULT_SUCCESS)
            throw std::runtime_error("Cannot lock bitmap pixels");
    }
    ~BitmapPixels() { AndroidBitmap_unlockPixels(env,bitmap); }
};
extern "C" JNIEXPORT jboolean JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_VivoPostDownscale_nativeResize(
        JNIEnv* env,jclass,jobject source,jobject destination,jint lobes) {
    AndroidBitmapInfo src{},dst{};
    if (AndroidBitmap_getInfo(env,source,&src)!=ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env,destination,&dst)!=ANDROID_BITMAP_RESULT_SUCCESS ||
        src.format!=ANDROID_BITMAP_FORMAT_RGBA_8888 || dst.format!=ANDROID_BITMAP_FORMAT_RGBA_8888)
        return JNI_FALSE;
    try {
        BitmapPixels input(env,source),output(env,destination);
        scamera_lanczos::resize(static_cast<const uint8_t*>(input.data),src.width,src.height,src.stride,
                static_cast<uint8_t*>(output.data),dst.width,dst.height,dst.stride,lobes);
        return JNI_TRUE;
    } catch (const std::exception&) {
        return JNI_FALSE;
    }
}

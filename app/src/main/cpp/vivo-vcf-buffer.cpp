#include <jni.h>
#include <sys/mman.h>
#include <sys/stat.h>

namespace {
jbyteArray fail(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/io/IOException");
    if (type) env->ThrowNew(type, message);
    return nullptr;
}
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_particlesdevs_photoncamera_capture_VivoVcf2Device_00024NativeReader_copy(
        JNIEnv* env, jclass, jint fd, jint size) {
    if (fd < 0 || size < 4 || size > 256 * 1024 * 1024)
        return fail(env, "Invalid VCF2 JPEG descriptor or byte count");
    struct stat info {};
    if (fstat(fd, &info) != 0)
        return fail(env, "Cannot inspect VCF2 JPEG descriptor");
    // Some shared-buffer descriptors do not report their allocation length.
    if (info.st_size > 0 && info.st_size < size)
        return fail(env, "VCF2 JPEG byte count exceeds descriptor size");
    void* address = mmap(nullptr, static_cast<size_t>(size), PROT_READ, MAP_SHARED, fd, 0);
    if (address == MAP_FAILED)
        return fail(env, "Cannot map VCF2 JPEG buffer");
    const auto* bytes = static_cast<const unsigned char*>(address);
    if (bytes[0] != 0xff || bytes[1] != 0xd8 || bytes[2] != 0xff) {
        munmap(address, static_cast<size_t>(size));
        return fail(env, "VCF2 BLOB does not start with a JPEG stream");
    }
    jbyteArray result = env->NewByteArray(size);
    if (result) env->SetByteArrayRegion(result, 0, size, static_cast<const jbyte*>(address));
    munmap(address, static_cast<size_t>(size));
    // Keep every supplied byte, including EXIF, gain maps and trailing payloads.
    return result;
}

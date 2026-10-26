// P352 (owner, 2026-10-26: «галерея должна работать с любыми Рав форматами»): the gallery's camera RAW reader on LibRaw 0.21.3
// (third_party/libraw, LGPL-2.1 / CDDL). Kept small: open a file (a /proc/self/fd path of the content URI's descriptor),
// metadata, the embedded thumbnail, the visible CFA mosaic of a Bayer sensor as the editor's 16-bit fixed point (1.0 = 16384,
// same as DngRaw) - the GPU demosaics it - and, for everything else (X-Trans, Foveon, sRAW, 4-colour), LibRaw's own demosaic as
// linear 16-bit camera RGB without white balance, flip or brightening (the editor applies WB, colour and tone); plus an 8-bit
// sRGB render with camera WB for the gallery when a file has no usable thumbnail.
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>

#include "libraw/libraw.h"

#define TAG "ScameraRaw"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

struct Handle {
    LibRaw raw;
    bool unpacked = false;
    libraw_processed_image_t *image = nullptr;

    ~Handle() {
        if (image) LibRaw::dcraw_clear_mem(image);
        raw.recycle();
    }
};

Handle *of(jlong h) { return reinterpret_cast<Handle *>(h); }

bool unpack(Handle *h) {
    if (h->unpacked) return true;
    int r = h->raw.unpack();
    if (r != LIBRAW_SUCCESS) {
        LOGW("unpack: %s", libraw_strerror(r));
        return false;
    }
    h->unpacked = true;
    return true;
}

// colour 0..2 of the visible pixel (row, col); the second green (3) is green
int colourAt(LibRaw &raw, int row, int col) {
    int c = raw.COLOR(row, col);
    return c == 3 ? 1 : c;
}

// the Bayer phase code of DngRaw (0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR) when the CFA repeats every 2x2, else -1
int bayerPhase(LibRaw &raw) {
    const libraw_iparams_t &id = raw.imgdata.idata;
    if (id.filters == 0 || id.filters == 9 || id.filters < 1000 || id.colors < 3 || id.is_foveon) return -1;
    for (int r = 0; r < 16; ++r)
        for (int c = 0; c < 16; ++c)
            if (colourAt(raw, r, c) != colourAt(raw, r & 1, c & 1)) return -1;
    int c00 = colourAt(raw, 0, 0), c10 = colourAt(raw, 0, 1), c01 = colourAt(raw, 1, 0), c11 = colourAt(raw, 1, 1);
    if (c00 == 0 && c10 == 1 && c01 == 1 && c11 == 2) return 0;
    if (c00 == 1 && c10 == 0 && c01 == 2 && c11 == 1) return 1;
    if (c00 == 1 && c10 == 2 && c01 == 0 && c11 == 1) return 2;
    if (c00 == 2 && c10 == 1 && c01 == 1 && c11 == 0) return 3;
    return -1;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_particlesdevs_photoncamera_gallery_raw_LibRawBridge_nOpen(JNIEnv *env, jclass, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    auto *h = new (std::nothrow) Handle();
    if (!h) {
        env->ReleaseStringUTFChars(path, p);
        return 0;
    }
    int r = h->raw.open_file(p);
    env->ReleaseStringUTFChars(path, p);
    if (r != LIBRAW_SUCCESS) {
        LOGW("open: %s", libraw_strerror(r));
        delete h;
        return 0;
    }
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_particlesdevs_photoncamera_gallery_raw_LibRawBridge_nClose(JNIEnv *, jclass, jlong h) {
    delete of(h);
}

// {width, height, flip, iso, shutter, aperture, focal, timestamp, black, maximum, colors, filters, bayer phase (-1 none),
//  cam_mul[4], pre_mul[4], cam_xyz[9] (XYZ -> camera, first three rows), thumb format, thumb w, thumb h, is DNG}
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_particlesdevs_photoncamera_gallery_raw_LibRawBridge_nInfo(JNIEnv *env, jclass, jlong hh) {
    Handle *h = of(hh);
    const libraw_data_t &d = h->raw.imgdata;
    double v[40] = {0};
    int i = 0;
    v[i++] = d.sizes.width;
    v[i++] = d.sizes.height;
    v[i++] = d.sizes.flip;
    v[i++] = d.other.iso_speed;
    v[i++] = d.other.shutter;
    v[i++] = d.other.aperture;
    v[i++] = d.other.focal_len;
    v[i++] = (double) d.other.timestamp;
    v[i++] = d.color.black;
    v[i++] = d.color.maximum;
    v[i++] = d.idata.colors;
    v[i++] = (double) d.idata.filters;
    v[i++] = bayerPhase(h->raw);
    for (int c = 0; c < 4; ++c) v[i++] = d.color.cam_mul[c];
    for (int c = 0; c < 4; ++c) v[i++] = d.color.pre_mul[c];
    for (int r = 0; r < 3; ++r)
        for (int c = 0; c < 3; ++c) v[i++] = d.color.cam_xyz[r][c];
    v[i++] = d.thumbnail.tformat;
    v[i++] = d.thumbnail.twidth;
    v[i++] = d.thumbnail.theight;
    v[i++] = d.idata.dng_version != 0 ? 1 : 0;
    jdoubleArray out = env->NewDoubleArray(i);
    env->SetDoubleArrayRegion(out, 0, i, v);
    return out;
}

// "make\nmodel\nlens"
extern "C" JNIEXPORT jstring JNICALL
Java_com_particlesdevs_photoncamera_gallery_raw_LibRawBridge_nText(JNIEnv *env, jclass, jlong hh) {
    const libraw_data_t &d = of(hh)->raw.imgdata;
    char buf[400];
    snprintf(buf, sizeof buf, "%s\n%s\n%s", d.idata.make, d.idata.model, d.lens.Lens);
    for (char *c = buf; *c; ++c)
        if ((unsigned char) *c >= 0x80) *c = '?';  // NewStringUTF needs modified UTF-8
    return env->NewStringUTF(buf);
}

// the embedded thumbnail: JPEG bytes (info {1, w, h, 0}) or 8-bit RGB (info {2, w, h, colors}); null when there is none usable
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_particlesdevs_photoncamera_gallery_raw_LibRawBridge_nThumb(JNIEnv *env, jclass, jlong hh, jintArray info) {
    Handle *h = of(hh);
    int r = h->raw.unpack_thumb();
    if (r != LIBRAW_SUCCESS) return nullptr;
    const libraw_thumbnail_t &t = h->raw.imgdata.thumbnail;
    if (!t.thumb || t.tlength == 0) return nullptr;
    int kind = t.tformat == LIBRAW_THUMBNAIL_JPEG ? 1 : (t.tformat == LIBRAW_THUMBNAIL_BITMAP ? 2 : 0);
    if (kind == 0) return nullptr;
    if (kind == 2 && (unsigned) t.twidth * t.theight * t.tcolors > t.tlength) return nullptr;
    jint in[4] = {kind, t.twidth, t.theight, t.tcolors};
    env->SetIntArrayRegion(info, 0, 4, in);
    jbyteArray out = env->NewByteArray((jsize) t.tlength);
    if (!out) return nullptr;
    env->SetByteArrayRegion(out, 0, (jsize) t.tlength, reinterpret_cast<const jbyte *>(t.thumb));
    return out;
}

// The visible Bayer mosaic into out (direct, uint16, outW x outH): (v - black(x, y)) / (maximum - black) x 16384, bin x bin
// same-colour samples averaged (DngRaw's layout: output (2X + px, 2Y + py) <- block X, Y of 2 bin x 2 bin, phase px, py).
// Returns the Bayer phase, -1 when this is not a 2x2 Bayer raw (or the unpack failed).
extern "C" JNIEXPORT jint JNICALL
Java_com_particlesdevs_photoncamera_gallery_raw_LibRawBridge_nMosaic(JNIEnv *env, jclass, jlong hh, jobject out, jint bin,
                                                                       jint outW, jint outH) {
    Handle *h = of(hh);
    int phase = bayerPhase(h->raw);
    if (phase < 0 || !unpack(h)) return -1;
    libraw_data_t &d = h->raw.imgdata;
    if (!d.rawdata.raw_image) return -1;
    auto *dst = static_cast<uint16_t *>(env->GetDirectBufferAddress(out));
    jlong cap = env->GetDirectBufferCapacity(out);
    if (!dst || cap < (jlong) outW * outH * 2) return -1;
    std::memset(dst, 0, (size_t) outW * outH * 2);
    const int W = d.sizes.width, H = d.sizes.height, pitch = d.sizes.raw_pitch / 2;
    const int top = d.sizes.top_margin, left = d.sizes.left_margin;
    const unsigned *cb = d.color.cblack;
    const int cbRows = (int) cb[4], cbCols = (int) cb[5];
    const int step = 2 * bin;
    const float perOut = (float) (bin * bin);
    for (int y = 0; y < H; ++y) {
        int oy = 2 * (y / step) + (y & 1);
        if (oy >= outH) break;
        const uint16_t *row = d.rawdata.raw_image + (size_t) (y + top) * pitch + left;
        for (int x = 0; x < W; ++x) {
            int ox = 2 * (x / step) + (x & 1);
            if (ox >= outW) break;
            int c = h->raw.COLOR(y, x);
            float black = (float) d.color.black + (float) cb[c & 3];
            if (cbRows > 0 && cbCols > 0) black += (float) cb[6 + (y % cbRows) * cbCols + (x % cbCols)];
            float range = std::max(1.0f, (float) d.color.maximum - black);
            float s = ((float) row[x] - black) / range;
            if (s <= 0) continue;
            size_t i = (size_t) oy * outW + ox;
            int acc = dst[i] + (int) std::lround(s * 16384.0f / perOut);
            dst[i] = (uint16_t) std::min(65535, acc);
        }
    }
    return phase;
}

// LibRaw's own development into a buffer it owns until close: display = false: linear 16-bit camera RGB, no white balance
// (multipliers 1), no flip, no auto-bright, highlights clipped per channel (each channel's white = 65535), raw colour;
// display = true: 8-bit sRGB with the camera's WB, flipped, auto-bright (the gallery's fallback picture). half: 2x2 binned.
// dims gets {width, height, colours, bits}; null on failure.
extern "C" JNIEXPORT jobject JNICALL
Java_com_particlesdevs_photoncamera_gallery_raw_LibRawBridge_nProcess(JNIEnv *env, jclass, jlong hh, jboolean half,
                                                                        jboolean display, jintArray dims) {
    Handle *h = of(hh);
    if (!unpack(h)) return nullptr;
    libraw_output_params_t &p = h->raw.imgdata.params;
    p.half_size = half ? 1 : 0;
    if (display) {
        p.use_camera_wb = 1;
        p.output_color = 1;
        p.output_bps = 8;
        p.user_flip = -1;
        p.no_auto_bright = 0;
    } else {
        p.use_camera_wb = 0;
        p.use_auto_wb = 0;
        for (int c = 0; c < 4; ++c) p.user_mul[c] = 1.0f;
        p.output_color = 0;
        p.output_bps = 16;
        p.gamm[0] = 1.0;
        p.gamm[1] = 1.0;
        p.no_auto_bright = 1;
        p.highlight = 0;
        p.user_flip = 0;
    }
    int r = h->raw.dcraw_process();
    if (r != LIBRAW_SUCCESS) {
        LOGW("dcraw_process: %s", libraw_strerror(r));
        return nullptr;
    }
    if (h->image) LibRaw::dcraw_clear_mem(h->image);
    int err = 0;
    h->image = h->raw.dcraw_make_mem_image(&err);
    if (!h->image || h->image->type != LIBRAW_IMAGE_BITMAP) {
        LOGW("mem image: %d", err);
        return nullptr;
    }
    jint dv[4] = {h->image->width, h->image->height, h->image->colors, h->image->bits};
    env->SetIntArrayRegion(dims, 0, 4, dv);
    return env->NewDirectByteBuffer(h->image->data, (jlong) h->image->data_size);
}

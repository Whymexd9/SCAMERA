#pragma once
// AVIF photo output (processing/avif/AvifEncoder.java): libavif + libaom (AV1 still image, AOM_USAGE_ALL_INTRA),
// 8 / 10 / 12-bit, YCbCr 4:4:4 or 4:2:0, or lossless (identity matrix, 4:4:4, the source's own bit depth). Pixels come
// from an Android Bitmap: RGBA_8888 or RGBA_1010102 (a 10-bit processing output keeps its 10 bits; an 8-bit one is
// expanded when a higher depth is chosen). Colour: BT.709 primaries, sRGB transfer (13), BT.709 matrix (identity when
// lossless), full range - unless the options name another colour (P46: Display P3 12/13/1 with its ICC profile, HDR HLG
// 9/18/9; the matrix also drives libavif's RGB -> YCbCr conversion). Shared by the JNI library (scamera-avif.cpp) and the
// host check (tools/check_avif.py), so both encode the same way. Dependencies: scamera-avif-deps.cmake.
#include <avif/avif.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace scamera_avif {

// Memory layout of the source pixels (Android's ARGB_8888 is R, G, B, A bytes; RGBA_1010102 is a little-endian 32-bit
// word with R in bits 0-9, G in 10-19, B in 20-29 and alpha in 30-31).
enum class Layout : int { Rgba8888 = 0, Rgba1010102 = 1 };

struct Options {
    int quality = 90;       // 1..100, libavif's quality scale (ignored when lossless)
    bool lossless = false;  // identity matrix, 4:4:4, the source's bit depth; quality, depth and chroma are not used
    int depth = 10;         // 8, 10 or 12 bit
    bool yuv444 = true;     // full-resolution chroma, else 4:2:0
    int speed = 6;          // libavif speed 0 (slowest, smallest) .. 10; libaom cpu-used = min(speed, 9)
    int threads = 1;        // conversion and encoder threads
    // Extra libaom options (aomenc names, e.g. {"tune", "ssim"}): only the host check's tuning runs set them.
    std::vector<std::pair<std::string, std::string>> codecOptions;
    // P46: H.273 colour of the file (CICP / nclx) and an optional ICC profile. The defaults are the pre-P46 colour.
    int primaries = 1;      // 1 BT.709, 12 Display P3 (SMPTE EG 432-1), 9 BT.2020
    int transfer = 13;      // 13 sRGB, 18 HLG, 16 PQ
    int matrix = 1;         // 1 BT.709, 9 BT.2020 non-constant luminance (identity when lossless)
    std::vector<uint8_t> icc;
};

struct Stats {
    int depth = 0;
    bool yuv444 = true;
    bool lossless = false;
    bool exif = false;      // the EXIF block is in the file
    double convertMs = 0;   // RGB -> YCbCr
    double encodeMs = 0;    // AV1 + container
    size_t bytes = 0;
};

inline int sourceDepth(Layout layout) { return layout == Layout::Rgba1010102 ? 10 : 8; }

// The bit depth a shot is written with: the source's depth when lossless (more bits would add nothing), else the choice.
inline int effectiveDepth(const Options& o, Layout layout) {
    if (o.lossless) return sourceDepth(layout);
    return o.depth == 8 || o.depth == 12 ? o.depth : 10;
}

namespace detail {
struct ImageDeleter {
    void operator()(avifImage* i) const { if (i) avifImageDestroy(i); }
};
struct EncoderDeleter {
    void operator()(avifEncoder* e) const { if (e) avifEncoderDestroy(e); }
};
struct OutputData {
    avifRWData data = AVIF_DATA_EMPTY;
    ~OutputData() { avifRWDataFree(&data); }
};
using Clock = std::chrono::steady_clock;
inline double msSince(Clock::time_point t) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t).count();
}

// Rows [y0, y1) of the pixels into the YCbCr planes of image, through a view of those rows (libavif's RGB -> YUV).
// y0 is even, so a 4:2:0 chroma row never straddles two calls.
inline avifResult convertRows(avifImage* image, const uint8_t* pixels, size_t stride, Layout layout, uint32_t y0,
                              uint32_t y1, std::vector<uint16_t>& scratch) {
    std::unique_ptr<avifImage, ImageDeleter> view(avifImageCreateEmpty());
    if (!view) return AVIF_RESULT_OUT_OF_MEMORY;
    const avifCropRect rect{0, y0, image->width, y1 - y0};
    avifResult r = avifImageSetViewRect(view.get(), image, &rect);
    if (r != AVIF_RESULT_OK) return r;
    avifRGBImage rgb;
    avifRGBImageSetDefaults(&rgb, view.get());
    rgb.ignoreAlpha = AVIF_TRUE;
    if (layout == Layout::Rgba8888) {
        rgb.format = AVIF_RGB_FORMAT_RGBA;
        rgb.depth = 8;
        rgb.pixels = const_cast<uint8_t*>(pixels + size_t(y0) * stride); // only read by avifImageRGBToYUV
        rgb.rowBytes = uint32_t(stride);
    } else {
        const size_t w = image->width;
        scratch.resize(w * 3 * (y1 - y0));
        for (uint32_t y = y0; y < y1; ++y) {
            const uint8_t* src = pixels + size_t(y) * stride;
            uint16_t* dst = scratch.data() + size_t(y - y0) * w * 3;
            for (size_t x = 0; x < w; ++x) {
                const uint32_t v = uint32_t(src[4 * x]) | uint32_t(src[4 * x + 1]) << 8 | uint32_t(src[4 * x + 2]) << 16 |
                                   uint32_t(src[4 * x + 3]) << 24;
                dst[3 * x] = uint16_t(v & 0x3FF);
                dst[3 * x + 1] = uint16_t(v >> 10 & 0x3FF);
                dst[3 * x + 2] = uint16_t(v >> 20 & 0x3FF);
            }
        }
        rgb.format = AVIF_RGB_FORMAT_RGB;
        rgb.depth = 10;
        rgb.pixels = reinterpret_cast<uint8_t*>(scratch.data());
        rgb.rowBytes = uint32_t(w * 3 * sizeof(uint16_t));
    }
    r = avifImageRGBToYUV(view.get(), &rgb);
    // avifImageRGBToYUV marks the planes it found as owned by the view: they belong to image (no double free).
    view->imageOwnsYUVPlanes = AVIF_FALSE;
    view->imageOwnsAlphaPlane = AVIF_FALSE;
    return r;
}
}  // namespace detail

using ImagePtr = std::unique_ptr<avifImage, detail::ImageDeleter>;

// Step 1: the pixels as the YCbCr image to encode (colour signalling set). Null with *error set on failure. The pixels
// are only read here, so a caller holding a lock on them (AndroidBitmap_lockPixels) can release it afterwards.
inline ImagePtr toYuv(const uint8_t* pixels, int width, int height, size_t stride, Layout layout, const Options& o,
                      Stats* stats, std::string* error) {
    if (pixels == nullptr || width <= 0 || height <= 0 || stride < size_t(width) * 4) {
        *error = "bad image geometry";
        return nullptr;
    }
    const auto start = detail::Clock::now();
    const int depth = effectiveDepth(o, layout);
    const bool yuv444 = o.lossless || o.yuv444;
    ImagePtr image(avifImageCreate(uint32_t(width), uint32_t(height), uint32_t(depth),
                                   yuv444 ? AVIF_PIXEL_FORMAT_YUV444 : AVIF_PIXEL_FORMAT_YUV420));
    if (!image) {
        *error = "out of memory (image)";
        return nullptr;
    }
    // Defaults 1 / 13 / 1: AVIF_COLOR_PRIMARIES_BT709, AVIF_TRANSFER_CHARACTERISTICS_SRGB, AVIF_MATRIX_COEFFICIENTS_BT709.
    image->colorPrimaries = avifColorPrimaries(o.primaries);
    image->transferCharacteristics = avifTransferCharacteristics(o.transfer);
    image->matrixCoefficients = o.lossless ? AVIF_MATRIX_COEFFICIENTS_IDENTITY : avifMatrixCoefficients(o.matrix);
    image->yuvRange = AVIF_RANGE_FULL;
    avifResult r = avifImageAllocatePlanes(image.get(), AVIF_PLANES_YUV);
    if (r != AVIF_RESULT_OK) {
        *error = std::string("planes: ") + avifResultToString(r);
        return nullptr;
    }
    // Strips of 64 rows over all threads; the calling thread works too, so the conversion completes even when no extra
    // thread can be started.
    constexpr uint32_t kStrip = 64;
    const uint32_t h = uint32_t(height);
    const uint32_t strips = (h + kStrip - 1) / kStrip;
    std::atomic<uint32_t> next{0};
    std::atomic<int> failure{int(AVIF_RESULT_OK)};
    auto work = [&]() {
        std::vector<uint16_t> scratch;
        for (uint32_t s = next++; s < strips && failure.load() == int(AVIF_RESULT_OK); s = next++) {
            const uint32_t y0 = s * kStrip, y1 = std::min(h, y0 + kStrip);
            const avifResult res = detail::convertRows(image.get(), pixels, stride, layout, y0, y1, scratch);
            if (res != AVIF_RESULT_OK) failure.store(int(res));
        }
    };
    std::vector<std::thread> pool;
    const int extra = std::max(0, std::min(o.threads, int(strips)) - 1);
    try {
        for (int i = 0; i < extra; ++i) pool.emplace_back(work);
    } catch (...) {
        // no more threads: the ones started and this one finish the strips
    }
    try {
        work();
    } catch (...) {
        failure.store(int(AVIF_RESULT_OUT_OF_MEMORY));
    }
    for (std::thread& t : pool) t.join();
    if (failure.load() != int(AVIF_RESULT_OK)) {
        *error = std::string("RGB to YUV: ") + avifResultToString(avifResult(failure.load()));
        return nullptr;
    }
    if (stats) {
        stats->depth = depth;
        stats->yuv444 = yuv444;
        stats->lossless = o.lossless;
        stats->convertMs = detail::msSince(start);
    }
    return image;
}

// Step 2: encodes the image (with the EXIF block, "Exif\0\0" + TIFF header as in a JPEG APP1, or none) and writes the
// file. Returns "" on success, else the reason; a partial file is removed.
inline std::string encodeImage(avifImage* image, const Options& o, const uint8_t* exif, size_t exifSize,
                               const std::string& path, Stats* stats) {
    const auto start = detail::Clock::now();
    bool exifStored = false;
    if (!o.icc.empty()) {
        // P46: the ICC profile (colr 'prof', written next to the nclx box). A colour the file cannot declare fails the
        // encode: the photo is then saved as a JPEG with the same profile instead of an AVIF with the wrong colours.
        const avifResult iccResult = avifImageSetProfileICC(image, o.icc.data(), o.icc.size());
        if (iccResult != AVIF_RESULT_OK) return std::string("ICC profile: ") + avifResultToString(iccResult);
    }
    if (exif != nullptr && exifSize > 0) {
        // A block libavif cannot parse costs the EXIF, never the photo.
        exifStored = avifImageSetMetadataExif(image, exif, exifSize) == AVIF_RESULT_OK;
    }
    std::unique_ptr<avifEncoder, detail::EncoderDeleter> encoder(avifEncoderCreate());
    if (!encoder) return "out of memory (encoder)";
    encoder->codecChoice = AVIF_CODEC_CHOICE_AOM;
    encoder->maxThreads = std::max(1, o.threads);
    encoder->speed = std::max(0, std::min(10, o.speed));
    encoder->quality = o.lossless ? AVIF_QUALITY_LOSSLESS : std::max(1, std::min(100, o.quality));
    encoder->qualityAlpha = AVIF_QUALITY_LOSSLESS;
    encoder->autoTiling = AVIF_TRUE; // tiles by image size and thread count: the threads encode in parallel
    for (const auto& option : o.codecOptions) {
        if (avifEncoderSetCodecSpecificOption(encoder.get(), option.first.c_str(), option.second.c_str()) != AVIF_RESULT_OK)
            return "codec option " + option.first;
    }
    detail::OutputData out;
    const avifResult r = avifEncoderWrite(encoder.get(), image, &out.data);
    if (r != AVIF_RESULT_OK) {
        std::string reason = std::string("encode: ") + avifResultToString(r);
        if (encoder->diag.error[0] != '\0') reason += std::string(" (") + encoder->diag.error + ")";
        return reason;
    }
    FILE* f = std::fopen(path.c_str(), "wb");
    if (f == nullptr) return "cannot open " + path;
    const bool written = std::fwrite(out.data.data, 1, out.data.size, f) == out.data.size;
    const bool closed = std::fclose(f) == 0;
    if (!written || !closed) {
        std::remove(path.c_str());
        return "write failed: " + path;
    }
    if (stats) {
        stats->exif = exifStored;
        stats->bytes = out.data.size;
        stats->encodeMs = detail::msSince(start);
    }
    return "";
}

// Both steps (the host check's entry point).
inline std::string encodeToFile(const uint8_t* pixels, int width, int height, size_t stride, Layout layout,
                                const Options& o, const uint8_t* exif, size_t exifSize, const std::string& path,
                                Stats* stats) {
    std::string error;
    ImagePtr image = toYuv(pixels, width, height, stride, layout, o, stats, &error);
    if (!image) return error;
    return encodeImage(image.get(), o, exif, exifSize, path, stats);
}

}  // namespace scamera_avif

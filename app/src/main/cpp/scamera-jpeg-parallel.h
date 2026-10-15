#pragma once
// P147: a JPEG encoded on several threads. jpegli has no threads of its own: the photo took 0.6-1.0 s at 20 MP and 3.8-4.8 s
// at 50 MP on one core of the X200 Ultra. The image is cut into strips of whole MCU rows; every strip is encoded on its own
// thread as a baseline JPEG and the strips' entropy-coded data are joined with restart markers into one standard baseline
// JPEG (DRI = the MCUs of a strip; each strip starts with fresh DC predictors, exactly what a restart interval means).
//  - Pass 1 (parallel): each strip with optimised Huffman tables; only its code lengths are kept.
//  - The code lengths of all strips merge into shared optimal tables (every valid symbol keeps a code, so no strip can
//    meet a symbol without one).
//  - Pass 2 (parallel): each strip with the shared tables; DQT / DHT / SOS of every strip must equal strip 0's (else the
//    caller encodes on one thread as before).
// The pixels are those of a one-thread baseline encode with the same tables (same quantisation; jpegli's adaptive
// quantisation looks only inside a strip, so the blocks at a strip's edge may differ in the last bits); the file is a
// little larger than the progressive one-thread JPEG (no progressive scans).
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstddef>
#include <cstring>
#include <exception>
#include <functional>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#include "lib/jpegli/common.h"
#include "lib/jpegli/encode.h"

namespace scamera_jpeg {
namespace parallel {

struct Table {
    uint8_t bits[17] = {};   // bits[1..16]: codes of each length
    uint8_t vals[256] = {};
    int count = 0;
};

/** The parsed parts of a strip's JPEG. */
struct Parts {
    std::vector<uint8_t> bytes;
    size_t sof = 0, sofLen = 0;           // the SOF0 segment (marker included)
    size_t sos = 0, sosLen = 0;           // the SOS segment
    std::vector<std::pair<size_t, size_t>> before;   // segments between SOI and SOS except SOF / DHT / DRI
    std::vector<std::pair<size_t, size_t>> dht;      // DHT segments
    size_t entropy = 0, entropyEnd = 0;   // the entropy-coded data
};

inline uint16_t be16(const uint8_t* p) { return uint16_t((p[0] << 8) | p[1]); }

inline Parts parse(std::vector<uint8_t>&& jpeg) {
    Parts p;
    p.bytes = std::move(jpeg);
    const std::vector<uint8_t>& b = p.bytes;
    if (b.size() < 4 || b[0] != 0xFF || b[1] != 0xD8) throw std::runtime_error("strip: no SOI");
    size_t pos = 2;
    int scans = 0;
    while (pos + 4 <= b.size()) {
        if (b[pos] != 0xFF) throw std::runtime_error("strip: bad marker");
        const uint8_t m = b[pos + 1];
        const size_t len = be16(&b[pos + 2]);
        if (len < 2 || pos + 2 + len > b.size()) throw std::runtime_error("strip: bad segment");
        if (m == 0xDA) {
            p.sos = pos;
            p.sosLen = 2 + len;
            p.entropy = pos + 2 + len;
            ++scans;
            break;
        }
        if (m == 0xC0) { p.sof = pos; p.sofLen = 2 + len; }
        else if (m == 0xC4) p.dht.emplace_back(pos, 2 + len);
        else if (m == 0xC1 || m == 0xC2 || m == 0xC9 || m == 0xCA) throw std::runtime_error("strip: not baseline");
        else if (m != 0xDD) p.before.emplace_back(pos, 2 + len);
        pos += 2 + len;
    }
    if (scans != 1 || p.sofLen == 0) throw std::runtime_error("strip: no single baseline scan");
    if (b.size() < p.entropy + 2 || b[b.size() - 2] != 0xFF || b[b.size() - 1] != 0xD9)
        throw std::runtime_error("strip: no EOI at the end");
    p.entropyEnd = b.size() - 2;
    // one scan only: a second SOS after the entropy data would show as a marker other than RSTn / stuffing
    for (size_t i = p.entropy; i + 1 < p.entropyEnd; ++i) {
        if (b[i] == 0xFF && b[i + 1] != 0x00 && !(b[i + 1] >= 0xD0 && b[i + 1] <= 0xD7) && b[i + 1] != 0xFF)
            throw std::runtime_error("strip: more than one scan");
    }
    return p;
}

/** The Huffman tables of a parsed strip: [class 0 dc / 1 ac][id 0..3]. */
inline void tablesOf(const Parts& p, Table out[2][4], bool present[2][4]) {
    for (int c = 0; c < 2; ++c) for (int i = 0; i < 4; ++i) present[c][i] = false;
    for (const auto& seg : p.dht) {
        const uint8_t* s = &p.bytes[seg.first];
        size_t pos = 4, end = seg.second;
        while (pos < end) {
            const int tc = s[pos] >> 4, th = s[pos] & 15;
            if (tc > 1 || th > 3 || pos + 17 > end) throw std::runtime_error("strip: bad DHT");
            Table& t = out[tc][th];
            t.count = 0;
            t.bits[0] = 0;
            for (int l = 1; l <= 16; ++l) { t.bits[l] = s[pos + l]; t.count += s[pos + l]; }
            if (t.count > 256 || pos + 17 + size_t(t.count) > end) throw std::runtime_error("strip: bad DHT counts");
            std::memcpy(t.vals, s + pos + 17, size_t(t.count));
            present[tc][th] = true;
            pos += 17 + size_t(t.count);
        }
    }
}

/** For component c (0 Y, 1 Cb, 2 Cr) of the scan: its DC and AC table ids. */
inline void scanTables(const Parts& p, int dc[3], int ac[3]) {
    const uint8_t* s = &p.bytes[p.sos];
    const int n = s[4];
    if (n != 3) throw std::runtime_error("strip: scan is not Y, Cb, Cr interleaved");
    for (int j = 0; j < 3; ++j) {
        const uint8_t t = s[6 + 2 * j];
        dc[j] = t >> 4;
        ac[j] = t & 15;
    }
}

/** Every symbol a baseline scan may need (DC sizes 0..11; AC EOB, ZRL and run/size 0..15 / 1..10). */
inline bool validSymbol(bool isAc, int s) {
    if (!isAc) return s <= 11;
    if (s == 0x00 || s == 0xF0) return true;
    const int size = s & 15;
    return size >= 1 && size <= 10;
}

/**
 * The optimal Huffman table of JPEG Annex K.2 (libjpeg's jpeg_gen_optimal_table): code lengths at most 16, the all-ones
 * code left unused. {@code freq}: 256 counts (0 = no code).
 */
inline Table optimal(const std::vector<int64_t>& counts) {
    std::vector<int64_t> freq(257, 0);
    for (int i = 0; i < 256; ++i) freq[size_t(i)] = counts[size_t(i)];
    freq[256] = 1;   // reserves the all-ones code
    int codesize[257] = {}, others[257];
    for (int& o : others) o = -1;
    for (;;) {
        int c1 = -1, c2 = -1;
        int64_t v = INT64_MAX;
        for (int i = 0; i <= 256; ++i) if (freq[size_t(i)] && freq[size_t(i)] <= v) { v = freq[size_t(i)]; c1 = i; }
        v = INT64_MAX;
        for (int i = 0; i <= 256; ++i) if (freq[size_t(i)] && freq[size_t(i)] <= v && i != c1) { v = freq[size_t(i)]; c2 = i; }
        if (c2 < 0) break;
        freq[size_t(c1)] += freq[size_t(c2)];
        freq[size_t(c2)] = 0;
        ++codesize[c1];
        while (others[c1] >= 0) { c1 = others[c1]; ++codesize[c1]; }
        others[c1] = c2;
        ++codesize[c2];
        while (others[c2] >= 0) { c2 = others[c2]; ++codesize[c2]; }
    }
    int bits[33] = {};
    for (int i = 0; i <= 256; ++i) {
        if (codesize[i]) {
            if (codesize[i] > 32) throw std::runtime_error("Huffman code size overflow");
            ++bits[codesize[i]];
        }
    }
    for (int i = 32; i > 16; --i) {
        while (bits[i] > 0) {
            int j = i - 2;
            while (bits[j] == 0) --j;
            bits[i] -= 2;
            ++bits[i - 1];
            bits[j + 1] += 2;
            --bits[j];
        }
    }
    int i = 16;
    while (bits[i] == 0) --i;
    --bits[i];   // drop the reserved code
    Table t;
    for (int l = 1; l <= 16; ++l) { t.bits[l] = uint8_t(bits[l]); t.count += bits[l]; }
    int p = 0;
    for (int l = 1; l <= 32; ++l)
        for (int s = 0; s < 256; ++s)
            if (codesize[s] == l) t.vals[p++] = uint8_t(s);
    return t;
}

/** Encodes rows [y0, y0 + h) as a baseline JPEG into memory; {@code tables}: null = optimised, else [dc0, ac0, dc1, ac1]. */
inline std::vector<uint8_t> encodeStrip(const uint8_t* pixels, int width, int h, size_t stride, int quality, const Table* tables) {
    std::vector<uint8_t> out;
    out.reserve(size_t(width) * size_t(h));
    struct Dest {
        jpeg_destination_mgr pub{};
        std::vector<uint8_t>* out = nullptr;
        std::vector<JOCTET> buffer = std::vector<JOCTET>(1 << 18);
    } dest;
    dest.out = &out;
    dest.pub.init_destination = [](j_compress_ptr c) {
        auto* d = reinterpret_cast<Dest*>(c->dest);
        d->pub.next_output_byte = d->buffer.data();
        d->pub.free_in_buffer = d->buffer.size();
    };
    dest.pub.empty_output_buffer = [](j_compress_ptr c) -> boolean {
        auto* d = reinterpret_cast<Dest*>(c->dest);
        d->out->insert(d->out->end(), d->buffer.begin(), d->buffer.end());
        d->pub.next_output_byte = d->buffer.data();
        d->pub.free_in_buffer = d->buffer.size();
        return TRUE;
    };
    dest.pub.term_destination = [](j_compress_ptr c) {
        auto* d = reinterpret_cast<Dest*>(c->dest);
        d->out->insert(d->out->end(), d->buffer.begin(), d->buffer.begin() + std::ptrdiff_t(d->buffer.size() - d->pub.free_in_buffer));
    };
    jpeg_compress_struct cinfo{};
    jpeg_error_mgr jerr{};
    cinfo.err = jpegli_std_error(&jerr);
    jerr.error_exit = [](j_common_ptr c) {
        char message[JMSG_LENGTH_MAX]{};
        (*c->err->format_message)(c, message);
        throw std::runtime_error(std::string("jpegli strip: ") + message);
    };
    try {
        jpegli_create_compress(&cinfo);
        cinfo.dest = &dest.pub;
        cinfo.image_width = JDIMENSION(width);
        cinfo.image_height = JDIMENSION(h);
        cinfo.input_components = 4;
        cinfo.in_color_space = JCS_EXT_RGBA;
        jpegli_set_defaults(&cinfo);
        jpegli_set_quality(&cinfo, quality < 1 ? 1 : quality > 100 ? 100 : quality, TRUE);
        jpegli_set_progressive_level(&cinfo, 0);   // one sequential scan
        for (int c = 0; c < cinfo.num_components; ++c) {
            cinfo.comp_info[c].h_samp_factor = 1;
            cinfo.comp_info[c].v_samp_factor = 1;
            cinfo.comp_info[c].dc_tbl_no = c == 0 ? 0 : 1;
            cinfo.comp_info[c].ac_tbl_no = c == 0 ? 0 : 1;
        }
        if (tables == nullptr) {
            cinfo.optimize_coding = TRUE;
        } else {
            cinfo.optimize_coding = FALSE;
            for (int t = 0; t < 2; ++t) {
                JHUFF_TBL* dc = cinfo.dc_huff_tbl_ptrs[t];
                if (dc == nullptr) dc = cinfo.dc_huff_tbl_ptrs[t] = jpegli_alloc_huff_table(reinterpret_cast<j_common_ptr>(&cinfo));
                JHUFF_TBL* ac = cinfo.ac_huff_tbl_ptrs[t];
                if (ac == nullptr) ac = cinfo.ac_huff_tbl_ptrs[t] = jpegli_alloc_huff_table(reinterpret_cast<j_common_ptr>(&cinfo));
                std::memcpy(dc->bits, tables[2 * t].bits, 17);
                std::memcpy(dc->huffval, tables[2 * t].vals, 256);
                std::memcpy(ac->bits, tables[2 * t + 1].bits, 17);
                std::memcpy(ac->huffval, tables[2 * t + 1].vals, 256);
                dc->sent_table = FALSE;
                ac->sent_table = FALSE;
            }
        }
        jpegli_start_compress(&cinfo, TRUE);
        constexpr int kRows = 16;
        JSAMPROW rows[kRows];
        while (cinfo.next_scanline < cinfo.image_height) {
            const int n = int(std::min<JDIMENSION>(kRows, cinfo.image_height - cinfo.next_scanline));
            for (int i = 0; i < n; ++i) rows[i] = const_cast<JSAMPROW>(pixels + size_t(cinfo.next_scanline + JDIMENSION(i)) * stride);
            jpegli_write_scanlines(&cinfo, rows, JDIMENSION(n));
        }
        jpegli_finish_compress(&cinfo);
    } catch (...) {
        jpegli_destroy_compress(&cinfo);
        throw;
    }
    jpegli_destroy_compress(&cinfo);
    return out;
}

/** Runs job(i) for i in [0, n) on {@code threads} threads; the first exception is rethrown. */
inline void forEach(int n, int threads, const std::function<void(int)>& job) {
    std::atomic<int> next{0};
    std::exception_ptr failure;
    std::atomic<bool> failed{false};
    auto worker = [&]() {
        for (;;) {
            const int i = next.fetch_add(1);
            if (i >= n || failed.load()) return;
            try {
                job(i);
            } catch (...) {
                if (!failed.exchange(true)) failure = std::current_exception();
                return;
            }
        }
    };
    std::vector<std::thread> pool;
    const int t = std::max(1, std::min(threads, n));
    for (int k = 1; k < t; ++k) pool.emplace_back(worker);
    worker();
    for (auto& th : pool) th.join();
    if (failure) std::rethrow_exception(failure);
}

/** Rows a strip: whole MCU rows (8), a restart interval of at most 65535 MCUs, at least about two strips a thread. */
inline int stripRows(int width, int height, int threads, int wanted) {
    const int mcusPerRow = (width + 7) / 8;
    const int maxMcuRows = std::max(1, 65535 / mcusPerRow);
    int rows = wanted > 0 ? wanted : std::max(64, (height / std::max(1, 2 * threads) + 7) / 8 * 8);
    rows = std::min(rows, maxMcuRows * 8);
    return std::max(8, rows / 8 * 8);
}

/**
 * Encodes RGBA rows as one baseline JPEG on {@code threads} threads (see the file comment). Throws std::runtime_error when the
 * strips cannot be joined; the caller then encodes on one thread. {@code wantedRows}: the strip height (0 = automatic, a
 * multiple of 8 otherwise; the check of HalfJpeg uses short strips on a small image).
 */
inline void encodeRgba(const uint8_t* pixels, int width, int height, size_t stride, int quality, int threads, int wantedRows,
                       const std::function<bool(const uint8_t*, size_t)>& writer, int sampleEvery = 0) {
    if (pixels == nullptr || width <= 0 || height <= 0 || stride < size_t(width) * 4) throw std::runtime_error("JPEG: bad image geometry");
    const int rows = stripRows(width, height, threads, wantedRows);
    const int strips = (height + rows - 1) / rows;
    if (strips < 2) throw std::runtime_error("one strip: no gain");
    const int mcusPerRow = (width + 7) / 8;
    const int interval = mcusPerRow * (rows / 8);
    if (interval > 65535) throw std::runtime_error("restart interval too long");
    auto stripOf = [&](int i, int& y0, int& h) {
        y0 = i * rows;
        h = std::min(rows, height - y0);
    };
    // pass 1: the code lengths of the optimised tables of every k-th strip (the statistics of a photo hardly change from
    // strip to strip; every valid symbol keeps a code anyway) -> merged counts per (luma / chroma, dc / ac)
    const int k = sampleEvery > 0 ? sampleEvery : std::max(1, strips / std::max(1, threads));
    const int sampled = (strips + k - 1) / k;
    std::vector<std::vector<int64_t>> counts(4, std::vector<int64_t>(256, 0));
    std::vector<std::vector<std::vector<int64_t>>> perStrip(size_t(sampled), counts);
    forEach(sampled, threads, [&](int j) {
        const int i = j * k;
        int y0, h;
        stripOf(i, y0, h);
        Parts p = parse(encodeStrip(pixels + size_t(y0) * stride, width, h, stride, quality, nullptr));
        Table t[2][4];
        bool present[2][4];
        tablesOf(p, t, present);
        int dc[3], ac[3];
        scanTables(p, dc, ac);
        const int64_t blocks = int64_t(mcusPerRow) * ((h + 7) / 8);
        auto& mine = perStrip[size_t(j)];
        for (int c = 0; c < 3; ++c) {
            const int slot = c == 0 ? 0 : 1;
            for (int cls = 0; cls < 2; ++cls) {
                const int id = cls == 0 ? dc[c] : ac[c];
                if (!present[cls][id]) throw std::runtime_error("strip: table missing");
                const Table& tb = t[cls][id];
                int k = 0;
                for (int l = 1; l <= 16; ++l) {
                    for (int n = 0; n < tb.bits[l]; ++n, ++k) {
                        // a code of length l stands for about 2^-l of the table's symbols
                        mine[size_t(2 * slot + cls)][tb.vals[k]] += std::max<int64_t>(1, (blocks << 16) >> l);
                    }
                }
            }
        }
    });
    for (const auto& m : perStrip)
        for (size_t t = 0; t < 4; ++t)
            for (size_t s = 0; s < 256; ++s) counts[t][s] += m[t][s];
    Table shared[4];   // dc0, ac0, dc1, ac1
    for (int t = 0; t < 4; ++t) {
        const bool isAc = (t & 1) != 0;
        std::vector<int64_t> c = counts[size_t(t)];
        for (int s = 0; s < 256; ++s) {
            if (!validSymbol(isAc, s)) { c[size_t(s)] = 0; continue; }
            if (c[size_t(s)] == 0) c[size_t(s)] = 1;   // every valid symbol keeps a (long) code
        }
        shared[t] = optimal(c);
    }
    const Table order[4] = {shared[0], shared[1], shared[2], shared[3]};
    // pass 2: the strips with the shared tables
    std::vector<Parts> parts(static_cast<size_t>(strips));
    forEach(strips, threads, [&](int i) {
        int y0, h;
        stripOf(i, y0, h);
        parts[size_t(i)] = parse(encodeStrip(pixels + size_t(y0) * stride, width, h, stride, quality, order));
    });
    // every strip must carry the same tables and scan header as strip 0
    const Parts& first = parts[0];
    auto same = [](const Parts& a, std::pair<size_t, size_t> x, const Parts& b, std::pair<size_t, size_t> y) {
        return x.second == y.second && std::memcmp(&a.bytes[x.first], &b.bytes[y.first], x.second) == 0;
    };
    for (int i = 1; i < strips; ++i) {
        const Parts& p = parts[size_t(i)];
        if (p.dht.size() != first.dht.size() || p.before.size() != first.before.size())
            throw std::runtime_error("strips differ in their headers");
        for (size_t k = 0; k < p.dht.size(); ++k)
            if (!same(first, first.dht[k], p, p.dht[k])) throw std::runtime_error("strips differ in their Huffman tables");
        for (size_t k = 0; k < p.before.size(); ++k)
            if (!same(first, first.before[k], p, p.before[k])) throw std::runtime_error("strips differ in their quantisation");
        if (!same(first, {first.sos, first.sosLen}, p, {p.sos, p.sosLen})) throw std::runtime_error("strips differ in their scan");
        // SOF: the same apart from the height (bytes 5..6)
        if (p.sofLen != first.sofLen || std::memcmp(&p.bytes[p.sof], &first.bytes[first.sof], 5) != 0
            || std::memcmp(&p.bytes[p.sof + 7], &first.bytes[first.sof + 7], first.sofLen - 7) != 0)
            throw std::runtime_error("strips differ in their frame");
    }
    // the file: SOI, strip 0's leading segments, SOF with the full height, DHT, DRI, SOS, the strips with RSTn between, EOI
    std::vector<uint8_t> head{0xFF, 0xD8};
    for (const auto& seg : first.before) head.insert(head.end(), first.bytes.begin() + std::ptrdiff_t(seg.first),
                                                     first.bytes.begin() + std::ptrdiff_t(seg.first + seg.second));
    std::vector<uint8_t> sof(first.bytes.begin() + std::ptrdiff_t(first.sof), first.bytes.begin() + std::ptrdiff_t(first.sof + first.sofLen));
    sof[5] = uint8_t(height >> 8);
    sof[6] = uint8_t(height & 0xFF);
    head.insert(head.end(), sof.begin(), sof.end());
    for (const auto& seg : first.dht) head.insert(head.end(), first.bytes.begin() + std::ptrdiff_t(seg.first),
                                                  first.bytes.begin() + std::ptrdiff_t(seg.first + seg.second));
    const uint8_t dri[6] = {0xFF, 0xDD, 0x00, 0x04, uint8_t(interval >> 8), uint8_t(interval & 0xFF)};
    head.insert(head.end(), dri, dri + 6);
    head.insert(head.end(), first.bytes.begin() + std::ptrdiff_t(first.sos), first.bytes.begin() + std::ptrdiff_t(first.sos + first.sosLen));
    if (!writer(head.data(), head.size())) throw std::runtime_error("JPEG output stream failed");
    for (int i = 0; i < strips; ++i) {
        const Parts& p = parts[size_t(i)];
        if (!writer(&p.bytes[p.entropy], p.entropyEnd - p.entropy)) throw std::runtime_error("JPEG output stream failed");
        if (i + 1 < strips) {
            const uint8_t rst[2] = {0xFF, uint8_t(0xD0 + (i & 7))};
            if (!writer(rst, 2)) throw std::runtime_error("JPEG output stream failed");
        }
    }
    const uint8_t eoi[2] = {0xFF, 0xD9};
    if (!writer(eoi, 2)) throw std::runtime_error("JPEG output stream failed");
}

}  // namespace parallel
}  // namespace scamera_jpeg

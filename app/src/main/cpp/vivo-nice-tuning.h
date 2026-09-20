#pragma once
#include <cmath>
#include <cstddef>
#include <cstring>
#include <stdexcept>

namespace vivo_nice::tuning {
struct ZoomRow { float lower, upper; int normal, mode2, mode2Special; };
struct EffectRow { float upper; const char* filename; };
struct LensTable {
    const ZoomRow* zoom; size_t zoomSize;
    const EffectRow* effects; size_t effectSize;
};
// readNiceHDRFrameInfo (1a3fd0) copies two integers and the entire EV vector.
// No assumption here that each vector element describes an independent RAW.
struct HdrRow {
    bool dual; int lens; const char* name;
    int count0, count1; size_t evSize; int ev[32];
};
#include "vivo-nice-tuning-data.h"

// 16a710 / 16a7c8. +4 is a zoom-domain value: the jump-table relocations
// resolve to g_zoom_trigger_{m,u,t,p}, NOT g_lux_trigger_*.
// mode and special retain donor enum values; the caller supplies them.
// Reading seamlessMode does not request any sensor-mode transition.
inline int normalFrameCount(int lens, float zoom, int mode, int special, int seamlessMode) {
    if (seamlessMode == 6 || seamlessMode == 0x502) return staggerCount;
    if (!std::isfinite(zoom)) throw std::invalid_argument("NICE zoom is nonfinite");
    const auto& table = lenses[lens >= 0 && lens <= 3 ? lens : 0];
    size_t index = 0;
    for (size_t i = 0; i < table.zoomSize; ++i) {
        if (zoom >= table.zoom[i].lower && zoom < table.zoom[i].upper) { index = i; break; }
    }
    const auto& row = table.zoom[index];
    // Unknown camera enum uses normal wide counts even if mode==2.
    const int n = lens < 0 || lens > 3 || mode != 2 ? row.normal
        : lens == 2 && special == 1 ? row.mode2Special : row.mode2;
    return n == 14 ? 10 : n;
}

struct EffectSelection { const char* filename; bool matched; };
// getTceXmlPath 170078: first strictly-greater threshold wins. At or beyond
// the last threshold the donor writes the base effect, not the last entry.
inline EffectSelection selectTceEffect(const EffectRow* rows, size_t size, float zoom) {
    if (!std::isfinite(zoom) || (size && !rows))
        throw std::invalid_argument("Invalid NICE effect table input");
    for (size_t i = 0; i < size; ++i)
        if (zoom < rows[i].upper) return {rows[i].filename, true};
    return {"NiceTceEffect.xml", false};
}
inline EffectSelection selectTceEffect(int lens, float zoom) {
    if (lens < 0 || lens > 3) throw std::invalid_argument("Unknown NICE effect camera");
    const auto& table = lenses[lens];
    return selectTceEffect(table.effects, table.effectSize, zoom);
}
// getNiceHDRFrameNum 16b284 chooses a group, not a scene. This uses the
// already-existing sensor state only; it never changes that state.
inline bool usesDualHdrGroup(int seamlessMode, int previewHdrVersion) {
    return (seamlessMode == 4 || seamlessMode == 0x500) && previewHdrVersion >= 2;
}
inline const HdrRow* findHdrRow(int lens, bool dual, const char* name) {
    if (!name) throw std::invalid_argument("Missing NICE HDR variant");
    bool hasLens = false;
    for (const auto& row : hdrRows) if (row.lens == lens && row.dual == dual) hasLens = true;
    const int group = hasLens ? lens : 0; // Native map lookup falls back to wide.
    for (const auto& row : hdrRows)
        if (row.lens == group && row.dual == dual && std::strcmp(row.name,name) == 0) return &row;
    // Missing config members preserve caller defaults in native cJSON loading.
    // They must not silently become a zero-frame schedule in this adapter.
    return nullptr;
}
} // namespace vivo_nice::tuning

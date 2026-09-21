#pragma once
#include <cstdint>
#include <stdexcept>

namespace vivo_aec {
// Pinned com.vivo.stats.aec.so b2e3e124..., offsets 0x177b38,
// 0x1782a0, 0x177d14 and 0x177dbc. These are vendor context values,
// NOT Android scene enums, Camera2 ISO, or sensor-mode requests.
struct NiceCaptureFlags {
    uint32_t base, alternate, tableVariant, decrease, special, family;
    uint32_t modeOverride;
};

inline NiceCaptureFlags captureFlags(uint64_t packed, uint32_t overrideWord,
                                    int runMode, bool disableBase) {
    NiceCaptureFlags f{};
    // In this donor the nonzero packed-word branch outside modes 9..13 clears
    // six fields, but retains the previous modeOverride. The caller must
    // supply that prior value separately; see updateCaptureFlags below.
    f.base = packed & 15;
    f.alternate = (packed >> 4) & 15;
    f.tableVariant = (packed >> 8) & 15;
    f.decrease = (packed >> 12) & 15;
    f.special = (packed >> 16) & 15;
    f.family = (packed >> 56) & 3;
    f.modeOverride = overrideWord & 15;
    if (packed && !(runMode >= 9 && runMode <= 13))
        throw std::invalid_argument("Suppressed flags require prior modeOverride");
    if (disableBase) f.base = 0;
    return f;
}

inline NiceCaptureFlags updateCaptureFlags(uint64_t packed, uint32_t overrideWord,
        int runMode, bool disableBase, uint32_t priorModeOverride) {
    if (packed && !(runMode >= 9 && runMode <= 13))
        return {0,0,0,0,0,0,priorModeOverride};
    return captureFlags(packed, overrideWord, runMode, disableBase);
}

inline int hdrRunMode(int64_t scene, uint32_t modeOverride) {
    switch (scene) {
        case 0x80000: case 0x100000: case 0x380000: case 0x1100000: return 0;
        case 0x200000: return 1;
        case 0x680000: return 2;
        case 0x10000: case 0x180000: case 0x700000: return 3;
        case 0x280000: return 4;
        case 0x300000: return 5;
        case 0x400000: return 6;
        case 0x780000: return 7;
        case 0x800000: return 8;
        case 0xc80000: return modeOverride == 1 ? 10 : 9;
        case 0xd80000: return 10;
        case 0xc00000: return 11;
        case 0xd00000: return 12;
        case 0x1180000: return 13;
        default: return 14;
    }
}

inline uint32_t exposureTableType(int mode, int historyFlag, const NiceCaptureFlags& f) {
    if (mode >= 11 && mode <= 13) {
        if ((f.special | 2u) == 3u) return 5;
        return historyFlag != 0 && f.base == 1 ? 1 : 0;
    }
    if (mode != 9 && mode != 10) return 9;
    if (f.alternate == 1) return f.base == 1 ? 3 : 2;
    if (f.base == 0) return 4;
    if ((f.special | 2u) == 3u) return 5;
    return f.tableVariant < 3 ? f.tableVariant + 6 : 9;
}

struct HdrTableSelection { uint32_t hdrFlags, tableId; };
inline HdrTableSelection hdrModeAndTableId(int mode, uint32_t type) {
    switch (mode) {
        case 1: return {0x1000,2};
        case 2: return {0x4000,2};
        case 3: return {0x400,4};
        case 4: return {8,0};
        case 5: return {0x10,1};
        case 6: return {0x88,0};
        case 7: return {0x48,3};
        case 8: return {0x108,3};
        case 9: case 10: {
            constexpr uint32_t ids[] = {4,0,3,0,1,2};
            return {0x10000,type >= 3 && type <= 8 ? ids[type-3] : 0};
        }
        case 11: case 12: case 13: return {0x10000,type == 5 ? 3u : 0u};
        default: return {2,3};
    }
}

// Indexes the already loaded sensor tuning bank. The bank is selected by
// EVBaseCalc from params+0xb8 for run modes 9..13, params+0xb0 otherwise.
// No defaults for missing tuning, motion or live scene provenance.
inline bool usesAlternateTuningBank(int mode) { return mode >= 9 && mode <= 13; }
} // namespace vivo_aec

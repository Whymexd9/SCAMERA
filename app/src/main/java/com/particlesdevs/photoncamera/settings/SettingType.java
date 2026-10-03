package com.particlesdevs.photoncamera.settings;

public enum SettingType {
    HDRX,
    FLASH,
    TIMER,
    QUAD,
    FPS_60,
    GRID,
    EIS,
    RAW,
    BATTERY_SAVER,
    BRACKETING,
    AE_METERING_STD,
    /** SCAM HDR hybrid: output grid / final size (1x, 12, 16, 20 MP, 2x). */
    HYBRID_OUTPUT,
    /** SCAM HDR hybrid: downsampler of the 2x image (Lanczos, bicubic, area, bilinear). */
    HYBRID_DOWNSAMPLER,
}

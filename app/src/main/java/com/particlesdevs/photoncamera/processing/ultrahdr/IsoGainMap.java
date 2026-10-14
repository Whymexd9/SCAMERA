package com.particlesdevs.photoncamera.processing.ultrahdr;

import android.graphics.Bitmap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * P125 The ISO 21496-1 gain map of a HEIC / AVIF photo (owner, 2026-10-14: «Делаем только пункт 1»): the SDR photo is the base
 * image, the gain map a second (hidden) image, and a 'tmap' derived image item (ISO/IEC 23008-12:2024 AMD 1; AVIF 1.2) joins
 * them with this metadata, in an 'altr' group with the base so a reader without gain maps shows the SDR photo. The values
 * are those of the Ultra HDR JPEG's hdrgm XMP (UltraHdrContainer, from {@link GainMapComputer}): base headroom 0 (SDR),
 * alternate headroom HDRCapacityMax, the map's 8-bit codes linear in log2 gain between GainMapMin and GainMapMax (gamma 1),
 * both offsets {@link GainMapComputer#DECODE_OFFSET}, one channel, applied in the base image's colour space.
 */
public final class IsoGainMap {
    /** Denominator of every fraction: 1e-6 steps, log2 values up to ±2147 fit the signed numerators. */
    public static final int DENOMINATOR = 1_000_000;
    /** The map is stored at half the photo's size per axis (a quarter of the pixels), as the phones' HDR HEIC / AVIF do. */
    public static final int DOWNSCALE = 2;

    public final double gainMapMin, gainMapMax, gamma, baseOffset, alternateOffset, baseHeadroom, alternateHeadroom;

    IsoGainMap(double gainMapMin, double gainMapMax, double alternateHeadroom) {
        this.gainMapMin = gainMapMin;
        this.gainMapMax = gainMapMax;
        this.gamma = 1;
        this.baseOffset = GainMapComputer.DECODE_OFFSET;
        this.alternateOffset = GainMapComputer.DECODE_OFFSET;
        this.baseHeadroom = 0;
        this.alternateHeadroom = alternateHeadroom;
    }

    /** The metadata of a shot's gain map. */
    public static IsoGainMap of(GainMapComputer.Result gain) {
        return new IsoGainMap(gain.gainMapMin, gain.gainMapMax, gain.hdrCapacityMax);
    }

    /** {min, max, gamma, base offset, alternate offset, base headroom, alternate headroom}: the native AVIF encoder's order. */
    public double[] values() {
        return new double[]{gainMapMin, gainMapMax, gamma, baseOffset, alternateOffset, baseHeadroom, alternateHeadroom};
    }

    static int signed(double v) {
        return (int) Math.round(Math.max(-2147.0, Math.min(2147.0, v)) * DENOMINATOR);
    }

    static int unsigned(double v) {
        return (int) Math.round(Math.max(0, Math.min(4294.0, v)) * DENOMINATOR);
    }

    /**
     * The 'tmap' item's data: ToneMapImage version 0 (ISO/IEC 23008-12:2024 AMD 1, 6.6.2.4.2) holding GainMapMetadata (ISO
     * 21496-1 C.2.2): minimum and writer version 0, is_multichannel 0, use_base_colour_space 1, the two headrooms and one
     * GainMapChannel - the bytes libavif writes for these values (avifWriteToneMappedImagePayload).
     */
    public byte[] toneMapPayload() {
        final ByteBuffer b = ByteBuffer.allocate(62).order(ByteOrder.BIG_ENDIAN);
        b.put((byte) 0);          // ToneMapImage version
        b.putShort((short) 0);    // minimum_version
        b.putShort((short) 0);    // writer_version
        b.put((byte) 0x40);       // is_multichannel 0, use_base_colour_space 1, reserved 0
        b.putInt(unsigned(baseHeadroom)).putInt(DENOMINATOR);
        b.putInt(unsigned(alternateHeadroom)).putInt(DENOMINATOR);
        b.putInt(signed(gainMapMin)).putInt(DENOMINATOR);
        b.putInt(signed(gainMapMax)).putInt(DENOMINATOR);
        b.putInt(unsigned(gamma)).putInt(DENOMINATOR);
        b.putInt(signed(baseOffset)).putInt(DENOMINATOR);
        b.putInt(signed(alternateOffset)).putInt(DENOMINATOR);
        return b.array();
    }

    /** The map at {@link #DOWNSCALE} (bilinear on the log2-coded values; at least 1 x 1); the caller recycles it. */
    public static Bitmap stored(Bitmap gainMap) {
        final int w = Math.max(1, gainMap.getWidth() / DOWNSCALE), h = Math.max(1, gainMap.getHeight() / DOWNSCALE);
        return Bitmap.createScaledBitmap(gainMap, w, h, true);
    }

    /** One byte per pixel (the R channel of the grey ARGB_8888 map), rows without padding. */
    public static byte[] luma(Bitmap map) {
        final int w = map.getWidth(), h = map.getHeight();
        final byte[] out = new byte[w * h];
        final int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            map.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) out[y * w + x] = (byte) (row[x] >>> 16);
        }
        return out;
    }
}

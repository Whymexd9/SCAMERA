package com.particlesdevs.photoncamera.processing.heif;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;

import com.particlesdevs.photoncamera.processing.PhotoFormat;
import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Whether this phone can write the 10-bit HEIC («HEIC 10 бит», {@link PhotoFormat#KEY_HEIC_10BIT}): Android 13+ (RGBA_1010102
 * bitmaps, COLOR_FormatYUVP010) and an HEVC encoder with the Main10 profile, P010 ByteBuffer input and 512 x 512 frames
 * (the grid tiles), a hardware one first. The rules are plain functions over {@link Encoder} facts so tests can feed them
 * any codec list; {@link #encoder()} reads the platform's list once.
 */
public final class Heic10Support {
    private static final String TAG = "Heic10Support";
    /** RGBA_1010102 bitmaps and COLOR_FormatYUVP010 are Android 13. */
    public static final int MIN_SDK = 33;
    /** MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010 (API 33). */
    public static final int COLOR_FORMAT_YUV_P010 = 54;
    /** MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10. */
    public static final int HEVC_PROFILE_MAIN10 = 2;

    /** What the 10-bit HEIC needs to know about one HEVC encoder. */
    public static final class Encoder {
        public final String name;
        public final boolean hardware, main10, p010, tileSize, cq;
        /** KEY_QUALITY range of the CQ mode ([0, 0] without CQ). */
        public final int qualityLow, qualityHigh;
        /** Bitrate range (bit/s) of the encoder. */
        public final int bitrateLow, bitrateHigh;

        public Encoder(String name, boolean hardware, boolean main10, boolean p010, boolean tileSize, boolean cq,
                       int qualityLow, int qualityHigh, int bitrateLow, int bitrateHigh) {
            this.name = name;
            this.hardware = hardware;
            this.main10 = main10;
            this.p010 = p010;
            this.tileSize = tileSize;
            this.cq = cq;
            this.qualityLow = qualityLow;
            this.qualityHigh = qualityHigh;
            this.bitrateLow = bitrateLow;
            this.bitrateHigh = bitrateHigh;
        }

        /** Main10 + P010 input + 512 x 512 frames. */
        public boolean usable() {
            return main10 && p010 && tileSize;
        }

        @Override
        public String toString() {
            return name + (hardware ? " (hw)" : " (sw)") + " main10=" + main10 + " p010=" + p010 + " 512=" + tileSize
                    + (cq ? " CQ " + qualityLow + ".." + qualityHigh : " no CQ");
        }
    }

    private Heic10Support() {}

    /**
     * The encoder the 10-bit HEIC uses among the usable ones: hardware before software, and within each a CQ-capable one
     * first (the OPPO 8 Gen 3 lists "c2.qti.hevc.encoder", VBR / CBR only, before "c2.qti.hevc.encoder.cq", the 128..512 px
     * CQ tile encoder HeifWriter uses); list order breaks ties. Null when none can.
     */
    public static Encoder choose(List<Encoder> encoders) {
        final List<Encoder> ranked = ranked(encoders);
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    /** The usable encoders in the order {@link #choose} prefers them (the encoder tries the next when one refuses). */
    public static List<Encoder> ranked(List<Encoder> encoders) {
        final List<Encoder> out = new ArrayList<>();
        for (Encoder e : encoders) if (e.usable()) out.add(e);
        // stable: list order within the same rank
        java.util.Collections.sort(out, (a, b) -> Integer.compare(rank(b), rank(a)));
        return out;
    }

    private static int rank(Encoder e) {
        return (e.hardware ? 2 : 0) + (e.cq ? 1 : 0);
    }

    /** Why the 10-bit HEIC cannot be written on Android {@code sdk} with {@code chosen} (null: it can), in the UI language. */
    public static String reason(int sdk, Encoder chosen) {
        if (sdk < MIN_SDK) return Lang.t("Нужен Android 13 или новее: фото сохраняется в 8-битный HEIC.",
                "Needs Android 13 or newer: the photo is saved as 8-bit HEIC.");
        if (chosen == null) return Lang.t("Нет кодировщика HEVC Main10 с входом P010: фото сохраняется в 8-битный HEIC.",
                "No HEVC Main10 encoder with P010 input: the photo is saved as 8-bit HEIC.");
        return null;
    }

    /** Whether a shot is written as 10-bit HEIC: the (effective) format is HEIC, the setting is on and the phone can. */
    public static boolean applies(PhotoFormat format, boolean setting, boolean supported) {
        return format == PhotoFormat.HEIC && setting && supported;
    }

    /** KEY_QUALITY of the CQ mode for a HEIC quality 1..100 in the encoder's range. */
    public static int cqQuality(int quality, int low, int high) {
        final int q = Math.max(1, Math.min(100, quality));
        if (high <= low) return low;
        return low + (int) Math.round((high - low) * (q - 1) / 99.0);
    }

    /** VBR bitrate (bit/s) of one 512 x 512 tile per frame at {@code fps}: 0.5 .. 4 bits per pixel over the quality. */
    public static int vbrBitrate(int quality, int tile, int fps, int low, int high) {
        final int q = Math.max(1, Math.min(100, quality));
        final double bitsPerPixel = 0.5 + 3.5 * (q - 1) / 99.0;
        long rate = Math.round(bitsPerPixel * tile * tile * fps);
        if (high > 0) rate = Math.min(rate, high);
        return (int) Math.max(rate, Math.max(1, low));
    }

    private static volatile List<Encoder> probed;
    private static volatile boolean testing;
    private static Encoder forTesting;

    /** The platform's HEVC encoders (empty below Android 13 or when the codec list fails). Read once. */
    public static List<Encoder> encoders() {
        List<Encoder> known = probed;
        if (known != null) return known;
        final List<Encoder> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                    if (!info.isEncoder() || info.isAlias()) continue;
                    boolean hevc = false;
                    for (String type : info.getSupportedTypes()) hevc |= type.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_HEVC);
                    if (!hevc) continue;
                    try {
                        out.add(describe(info));
                    } catch (RuntimeException e) {
                        Log.w(TAG, "capabilities of " + info.getName() + " unavailable: " + e);
                    }
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "codec list unavailable: " + e);
            }
            for (Encoder e : out) Log.d(TAG, "HEVC encoder " + e);
        }
        known = Collections.unmodifiableList(out);
        probed = known;
        return known;
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static Encoder describe(MediaCodecInfo info) {
        final MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC);
        boolean main10 = false, p010 = false;
        for (MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels) main10 |= pl.profile == HEVC_PROFILE_MAIN10;
        for (int f : caps.colorFormats) p010 |= f == COLOR_FORMAT_YUV_P010;
        final MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
        final boolean size = video != null && video.isSizeSupported(TileGrid.TILE, TileGrid.TILE);
        final MediaCodecInfo.EncoderCapabilities enc = caps.getEncoderCapabilities();
        final boolean cq = enc != null && enc.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ);
        int qLow = 0, qHigh = 0;
        if (cq && enc.getQualityRange() != null) {
            qLow = enc.getQualityRange().getLower();
            qHigh = enc.getQualityRange().getUpper();
        }
        int bLow = 0, bHigh = 0;
        if (video != null && video.getBitrateRange() != null) {
            bLow = video.getBitrateRange().getLower();
            bHigh = video.getBitrateRange().getUpper();
        }
        return new Encoder(info.getName(), info.isHardwareAccelerated(), main10, p010, size, cq, qLow, qHigh, bLow, bHigh);
    }

    /** The encoder of the 10-bit HEIC on this phone, or null. */
    public static Encoder encoder() {
        if (testing) return forTesting;
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? choose(encoders()) : null;
    }

    /** The phone's HEVC encoders (the test encoder alone under {@link #setForTesting}). */
    public static List<Encoder> candidates() {
        if (testing) return forTesting == null ? Collections.emptyList() : Collections.singletonList(forTesting);
        return encoders();
    }

    /** Whether this phone can write the 10-bit HEIC. */
    public static boolean available() {
        return (testing || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) && encoder() != null;
    }

    /** Whether this shot is written as 10-bit HEIC: the format is HEIC (Android 9+), «HEIC 10 бит» is on, the phone can. */
    public static boolean wanted() {
        // The codec list is read only when the setting is on and the format is HEIC (the 8-bit default path never reads it).
        if (!com.particlesdevs.photoncamera.settings.PreferenceKeys.isHeic10Bit()) return false;
        final PhotoFormat format = com.particlesdevs.photoncamera.settings.PreferenceKeys.getPhotoFormat();
        return applies(format, true, format == PhotoFormat.HEIC && available());
    }

    /** Why the 10-bit HEIC row is inactive on this phone, or null. */
    public static String unavailableReason() {
        if (testing) return reason(MIN_SDK, forTesting);
        return reason(Build.VERSION.SDK_INT, encoder());
    }

    /** Tests: pretend the phone has {@code encoder} (null: no usable encoder); {@link #clearForTesting} undoes it. */
    public static void setForTesting(Encoder encoder) {
        forTesting = encoder;
        testing = true;
    }

    public static void clearForTesting() {
        testing = false;
        forTesting = null;
    }
}

package com.particlesdevs.photoncamera.processing.avif;

import android.graphics.Bitmap;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.particlesdevs.photoncamera.processing.PhotoFormat;
import com.particlesdevs.photoncamera.processing.color.OutputColour;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * AVIF photo output through the bundled libavif + libaom (native library scameraAvif, app/src/main/cpp/scamera-avif-core.h):
 * an AV1 still image, 8 / 10 / 12-bit, YCbCr 4:4:4 or 4:2:0, or lossless (identity matrix, 4:4:4). BT.709 primaries, sRGB
 * transfer, BT.709 matrix, full range. Takes an ARGB_8888 bitmap or an RGBA_1010102 one (a 10-bit processing output keeps
 * its 10 bits; 8-bit pixels are expanded when a higher depth is chosen). Every failure is an IOException: the caller saves
 * the photo as JPEG instead. P46: {@link Options#withColour} declares another colour - Display P3 (CICP 12/13/1 and the
 * P3 ICC profile) or HDR HLG (CICP 9/18/9, the BT.2020 matrix of the RGB -> YCbCr conversion); the default is the above.
 */
public final class AvifEncoder {
    private static final String TAG = "AvifEncoder";
    /**
     * Working memory of one encode per pixel, measured on the host build at 12.6 and 50 MP (peak RSS without the source
     * pixels): about 36 bytes at 10 / 12-bit 4:4:4 and 29 at 8-bit 4:4:4 (libaom keeps several frame-sized buffers),
     * about 20 at 4:2:0.
     */
    static final int BYTES_PER_PIXEL_444 = 36, BYTES_PER_PIXEL_420 = 20;
    /** Bitmap.Config.RGBA_1010102 (the 10-bit final image) exists from Android 13. */
    public static final int TEN_BIT_MIN_SDK = 33;
    private static final boolean LOADED;
    private static volatile Boolean availableForTesting;

    static {
        boolean loaded;
        try {
            System.loadLibrary("scameraAvif");
            loaded = true;
        } catch (Throwable t) {
            Log.w(TAG, "AVIF encoder unavailable, AVIF is not offered: " + t);
            loaded = false;
        }
        LOADED = loaded;
    }

    private AvifEncoder() {}

    /** Whether the native encoder is loaded (AVIF is offered only then). */
    public static boolean available() {
        Boolean forced = availableForTesting;
        return forced != null ? forced : LOADED;
    }

    /** Unit tests: pretend the native encoder is (not) there; null restores the real state. */
    @VisibleForTesting
    public static void setAvailableForTesting(@Nullable Boolean available) {
        availableForTesting = available;
    }

    /** The settings of one encode (PreferenceKeys.getAvifOptions). */
    public static final class Options {
        /** 1-100 on libavif's scale; not used when lossless. */
        public final int quality;
        /** Exact pixels: identity matrix, 4:4:4 and the bitmap's own depth (8 or 10); quality, depth and chroma are not used. */
        public final boolean lossless;
        /** 8, 10 or 12 bit. */
        public final int depth;
        /** Full-resolution chroma (4:4:4), else 4:2:0. */
        public final boolean yuv444;
        /** libavif speed: 0 slowest / smallest .. 10 fastest (libaom cpu-used = min(speed, 9)). */
        public final int speed;
        /** Conversion and encoder threads. */
        public final int threads;
        /** P46: the colour the file declares (CICP + optional ICC); {@link OutputColour.Signal#SRGB} by default. */
        public final OutputColour.Signal colour;

        public Options(int quality, boolean lossless, int depth, boolean yuv444, int speed, int threads) {
            this(quality, lossless, depth, yuv444, speed, threads, OutputColour.Signal.SRGB);
        }

        private Options(int quality, boolean lossless, int depth, boolean yuv444, int speed, int threads, OutputColour.Signal colour) {
            this.quality = Math.max(1, Math.min(100, quality));
            this.lossless = lossless;
            this.depth = depth == 8 || depth == 12 ? depth : 10;
            this.yuv444 = yuv444;
            this.speed = Math.max(0, Math.min(10, speed));
            this.threads = Math.max(1, threads);
            this.colour = colour == null ? OutputColour.Signal.SRGB : colour;
        }

        /** These settings declaring {@code signal} (P46). */
        public Options withColour(OutputColour.Signal signal) {
            return new Options(quality, lossless, depth, yuv444, speed, threads, signal);
        }

        /** Whether the file has full-resolution chroma (lossless always does). */
        public boolean fullChroma() {
            return lossless || yuv444;
        }

        /** "10-bit 4:4:4 q90 speed 6" / "lossless speed 6": the log's description. */
        public String describe() {
            return (lossless ? "lossless" : depth + "-bit " + (yuv444 ? "4:4:4" : "4:2:0") + " q" + quality) + " speed " + speed
                    + (colour.isDefault() ? "" : " " + colour);
        }
    }

    /** What an encode wrote (from the native side). */
    public static final class Result {
        public final int depth;
        public final boolean yuv444;
        public final long convertMs, encodeMs, bytes;
        /** Whether the EXIF block is in the file. */
        public final boolean exif;

        /** {@code stats} as the native side fills them: depth, 4:4:4, conversion ms, encode ms, bytes, EXIF stored. */
        public Result(long[] stats) {
            depth = (int) stats[0];
            yuv444 = stats[1] != 0;
            convertMs = stats[2];
            encodeMs = stats[3];
            bytes = stats[4];
            exif = stats[5] != 0;
        }
    }

    /** Whether an AVIF with these settings keeps more than 8 bits of a 10-bit image: 10 / 12-bit or lossless. */
    public static boolean keepsTenBits(Options options) {
        return options.lossless || options.depth > 8;
    }

    /**
     * Whether the shot's final image should come out 10-bit (RGBA_1010102, PostPipeline.tenBitOutput): the effective
     * format is AVIF on Android 13+ and the AVIF keeps more than 8 bits. The pipeline falls back to 8 bits by itself.
     */
    public static boolean tenBitImageWanted(PhotoFormat format, Options options, int sdk) {
        return format == PhotoFormat.AVIF && sdk >= TEN_BIT_MIN_SDK && keepsTenBits(options);
    }

    /** {@link #tenBitImageWanted(PhotoFormat, Options, int)} for the stored settings on this phone. */
    public static boolean tenBitImageWanted() {
        return tenBitImageWanted(PreferenceKeys.getPhotoFormat(), PreferenceKeys.getAvifOptions(), Build.VERSION.SDK_INT);
    }

    /** Estimated native working memory of encoding a {@code width} x {@code height} photo (bytes). */
    public static long workingBytes(int width, int height, boolean fullChroma) {
        return (long) width * height * (fullChroma ? BYTES_PER_PIXEL_444 : BYTES_PER_PIXEL_420);
    }

    /** Null on success, else the reason; stats: depth, 4:4:4, conversion ms, encode ms, bytes, EXIF stored. */
    private static native String encode(Bitmap bitmap, String path, int quality, boolean lossless, int depth, boolean yuv444,
                                        int speed, int threads, byte[] exif, int primaries, int transfer, int matrix,
                                        byte[] icc, long[] stats);

    /**
     * Writes {@code bitmap} (ARGB_8888 or RGBA_1010102) to {@code file} as AVIF with {@code exif} ("Exif\0\0" + TIFF, the
     * layout of ExifBlock.exifDataBlock, or null). Keeps the bitmap. Throws when the encoder is missing or failed; no
     * partial file is left behind.
     */
    public static Result encode(Bitmap bitmap, Path file, Options options, @Nullable byte[] exif) throws IOException {
        if (!LOADED) throw new IOException("AVIF encoder unavailable");
        if (bitmap.isRecycled()) throw new IOException("bitmap is recycled");
        long[] stats = new long[6];
        String error;
        try {
            final OutputColour.Signal c = options.colour;
            error = encode(bitmap, file.toString(), options.quality, options.lossless, options.depth, options.yuv444,
                    options.speed, options.threads, exif, c.primaries, c.transfer, c.matrix, c.icc(), stats);
        } catch (RuntimeException | LinkageError e) {
            error = String.valueOf(e);
        }
        if (error != null) {
            try { Files.deleteIfExists(file); } catch (IOException ignored) {}
            throw new IOException("AVIF: " + error);
        }
        return new Result(stats);
    }
}

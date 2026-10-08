package com.particlesdevs.photoncamera.processing.heif;

import android.graphics.Bitmap;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;

import androidx.annotation.RequiresApi;

import com.particlesdevs.photoncamera.processing.color.OutputColour;
import com.particlesdevs.photoncamera.util.Log;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The 10-bit HEIC: the RGBA_1010102 final image coded by the platform HEVC encoder in Main10 (P010 ByteBuffer input,
 * every frame intra) as a grid of 512 x 512 tiles ({@link TileGrid}), one input buffer per tile, and stored by
 * {@link HeifContainerWriter} with the EXIF block. HeifWriter cannot do this: its bitmap input is 8-bit and only its AVIF
 * sibling has a high-bit-depth switch. The RGB -> P010 conversion ({@link P010}) runs on a few threads, one tile band
 * (512 image rows) ahead of the encoder, without a full-frame copy of the image. Any failure returns false (partial file
 * deleted, codec released) so PhotoOutput falls back to the 8-bit HEIC and then to JPEG.
 * P46: {@link #write(Path, Bitmap, int, byte[], long, Stats, OutputColour.Signal)} declares another colour - Display P3
 * (nclx 12/13/1 + ICC profile in the container; the VUI stays BT.709, MediaFormat has no P3 standard) or HLG (BT.2020
 * matrix in the P010 conversion, VUI BT.2020 / HLG / full range, nclx 9/18/9). The sRGB default is the path above.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public final class Heic10Encoder {
    private static final String TAG = "Heic10Encoder";
    static final int FPS = 30;
    private static final long DEQUEUE_US = 10_000;

    private Heic10Encoder() {}

    /** What one encode did, for the PhotonLog line. */
    public static final class Stats {
        public String codec = "-", mode = "-", sps = "-";
        public int tiles;
        public long bytes, convertMs, totalMs;

        @Override
        public String toString() {
            return codec + " " + mode + ", " + tiles + " tiles, " + bytes / 1024 + " KB, convert " + convertMs + " ms, total "
                    + totalMs + " ms, SPS " + sps;
        }
    }

    /**
     * Writes {@code img} (RGBA_1010102) as a 10-bit HEIC. {@code exifBlock}: "Exif\0\0" + TIFF (ExifBlock.exifDataBlock) or
     * null. True when the file is complete; false (no file left) otherwise.
     */
    public static boolean write(Path file, Bitmap img, int quality, byte[] exifBlock, long timeoutMs, Stats stats) {
        return write(file, img, quality, exifBlock, timeoutMs, stats, OutputColour.Signal.SRGB);
    }

    /** As above, declaring {@code colour} (P46); {@link OutputColour.Signal#SRGB} is the call above. */
    public static boolean write(Path file, Bitmap img, int quality, byte[] exifBlock, long timeoutMs, Stats stats,
                                OutputColour.Signal colour) {
        final long started = System.nanoTime();
        final List<Heic10Support.Encoder> candidates = Heic10Support.ranked(Heic10Support.candidates());
        if (candidates.isEmpty()) {
            Log.w(TAG, "no HEVC Main10 encoder with P010 input");
            return false;
        }
        if (!TenBitBitmaps.isTenBit(img)) {
            Log.w(TAG, "not a 10-bit image: " + img.getConfig());
            return false;
        }
        final TileGrid grid = new TileGrid(img.getWidth(), img.getHeight());
        final long deadline = started + timeoutMs * 1_000_000L;
        MediaCodec codec = null;
        TileSource source = null;
        boolean ok = false;
        try {
            MediaFormat input = null;
            // An encoder that refuses Main10 / P010 at configure or start (nothing coded yet) hands over to the next one.
            for (Heic10Support.Encoder enc : candidates) {
                stats.codec = enc.name;
                try {
                    codec = MediaCodec.createByCodecName(enc.name);
                    input = configure(codec, enc, quality, grid.tile, stats, colour);
                    codec.start();
                    break;
                } catch (Exception e) {
                    Log.w(TAG, enc.name + " refused 512x512 Main10 P010: " + e);
                    if (codec != null) {
                        try { codec.release(); } catch (RuntimeException ignored) {}
                        codec = null;
                    }
                }
            }
            if (codec == null) throw new IllegalStateException("no encoder took 512x512 Main10 P010");
            source = new TileSource(img, grid, P010.Matrix.forCode(colour.matrix));
            final Encoded encoded = run(codec, source, input, grid, deadline);
            stats.convertMs = source.convertNanos / 1_000_000;
            final HevcNal.Sps sps = HevcNal.parseSps(encoded.sets.sps.get(0));
            stats.sps = sps.toString();
            if (sps.bitDepthLuma != 10 || sps.bitDepthChroma != 10 || sps.chromaFormatIdc != 1)
                throw new IllegalStateException("the encoder did not code 10-bit 4:2:0: " + sps);
            if (sps.width != grid.tile || sps.height != grid.tile)
                throw new IllegalStateException("tile coded as " + sps.width + "x" + sps.height + ", not " + grid.tile);
            final HeifContainerWriter writer = new HeifContainerWriter(grid, encoded.sets.hvcC(), sps.bitDepthLuma);
            for (byte[] sample : encoded.samples) writer.addTile(sample);
            writer.setExif(exifBlock);
            if (!colour.isDefault()) writer.setColour(colour);
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 20)) {
                writer.writeTo(out);
            }
            stats.tiles = encoded.samples.size();
            stats.bytes = Files.size(file);
            ok = stats.bytes > 0;
        } catch (Exception | OutOfMemoryError e) {
            Log.e(TAG, "10-bit HEIC encode failed (" + stats.codec + " " + stats.mode + "): " + android.util.Log.getStackTraceString(e));
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (RuntimeException ignored) {}
                try { codec.release(); } catch (RuntimeException ignored) {}
            }
            if (source != null) source.close();
            if (!ok) {
                try { Files.deleteIfExists(file); } catch (IOException ignored) {}
            }
            stats.totalMs = (System.nanoTime() - started) / 1_000_000;
        }
        return ok;
    }

    /** Configures the encoder for 512 x 512 Main10 P010 intra frames: CQ at the HEIC quality when it has CQ, else VBR. */
    private static MediaFormat configure(MediaCodec codec, Heic10Support.Encoder enc, int quality, int tile, Stats stats,
                                         OutputColour.Signal colour) {
        if (enc.cq) {
            try {
                final int q = Heic10Support.cqQuality(quality, enc.qualityLow, enc.qualityHigh);
                codec.configure(format(tile, true, q, 0, colour), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                stats.mode = "CQ " + q;
                return codec.getInputFormat();
            } catch (RuntimeException e) {
                // Some encoders list CQ but refuse it with Main10: reset and take VBR.
                Log.w(TAG, enc.name + " refused CQ, VBR instead: " + e);
                codec.reset();
            }
        }
        final int bitrate = Heic10Support.vbrBitrate(quality, tile, FPS, enc.bitrateLow, enc.bitrateHigh);
        codec.configure(format(tile, false, 0, bitrate, colour), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        stats.mode = "VBR " + bitrate / 1000 + " kbit/s";
        return codec.getInputFormat();
    }

    static MediaFormat format(int tile, boolean cq, int cqQuality, int bitrate) {
        return format(tile, cq, cqQuality, bitrate, OutputColour.Signal.SRGB);
    }

    /**
     * The encoder format; the VUI follows {@code colour} only for HDR (BT.2020 / HLG or PQ / its range): an SDR colour keeps
     * the BT.709 SDR VUI of the default (MediaFormat cannot name P3 primaries; the container's colr boxes do).
     */
    static MediaFormat format(int tile, boolean cq, int cqQuality, int bitrate, OutputColour.Signal colour) {
        final MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, tile, tile);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, Heic10Support.COLOR_FORMAT_YUV_P010);
        f.setInteger(MediaFormat.KEY_PROFILE, Heic10Support.HEVC_PROFILE_MAIN10);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0); // every tile an intra frame
        f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        if (colour.hdr()) {
            // HDR (P46): the bitstream itself says BT.2020 + HLG (or PQ), so decoders that read only the VUI see HDR too.
            f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020);
            f.setInteger(MediaFormat.KEY_COLOR_RANGE, colour.fullRange ? MediaFormat.COLOR_RANGE_FULL : MediaFormat.COLOR_RANGE_LIMITED);
            f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, colour.transfer == OutputColour.TRANSFER_PQ
                    ? MediaFormat.COLOR_TRANSFER_ST2084 : MediaFormat.COLOR_TRANSFER_HLG);
        } else {
            f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
            f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL);
            // The bitstream says SDR video; the container's colr box carries the real sRGB transfer.
            f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
        }
        if (cq) {
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ);
            f.setInteger(MediaFormat.KEY_QUALITY, cqQuality);
        } else {
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        }
        return f;
    }

    /** Parameter sets and tile samples (length-prefixed, raster order) of one encode. */
    static final class Encoded {
        final HevcNal.ParameterSets sets = new HevcNal.ParameterSets();
        final List<byte[]> samples = new ArrayList<>();
    }

    /** Synchronous encode loop: one P010 tile per input buffer, then end of stream; output drained all along. */
    private static Encoded run(MediaCodec codec, TileSource source, MediaFormat input, TileGrid grid, long deadline) throws Exception {
        final Encoded out = new Encoded();
        final int n = grid.count();
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int next = 0;
        boolean eosQueued = false, eos = false;
        while (!eos) {
            if (System.nanoTime() > deadline) {
                if (out.samples.size() == n && eosQueued) {
                    Log.w(TAG, "no end of stream from the encoder, every tile is there");
                    break;
                }
                throw new IllegalStateException("timeout: " + out.samples.size() + " of " + n + " tiles coded");
            }
            if (!eosQueued) {
                final int in = codec.dequeueInputBuffer(DEQUEUE_US);
                if (in >= 0) {
                    final long pts = next * 1_000_000L / FPS;
                    if (next < n) {
                        final byte[] p010 = source.tile(next, deadline);
                        final int size = fill(codec, in, p010, grid.tile, input);
                        codec.queueInputBuffer(in, 0, size, pts, 0);
                        next++;
                    } else {
                        codec.queueInputBuffer(in, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        eosQueued = true;
                    }
                }
            }
            while (true) {
                final int o = codec.dequeueOutputBuffer(info, eosQueued ? DEQUEUE_US : 0);
                if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    final ByteBuffer csd = codec.getOutputFormat().getByteBuffer("csd-0");
                    if (csd != null) out.sets.collect(HevcNal.split(bytes(csd, csd.position(), csd.remaining())));
                    continue;
                }
                if (o < 0) continue;
                final ByteBuffer buffer = codec.getOutputBuffer(o);
                final byte[] data = buffer == null || info.size <= 0 ? new byte[0] : bytes(buffer, info.offset, info.size);
                final boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true;
                codec.releaseOutputBuffer(o, false);
                if (data.length == 0) continue;
                final List<byte[]> nals = HevcNal.split(data);
                out.sets.collect(nals); // codec config buffer, or parameter sets in front of an IDR
                if (config) continue;
                boolean slice = false;
                for (byte[] nal : nals) slice |= HevcNal.isSlice(nal);
                if (!slice) {
                    Log.w(TAG, "output buffer of " + data.length + " bytes without a slice, skipped");
                    continue;
                }
                if (out.samples.size() >= n) throw new IllegalStateException("more coded frames than tiles");
                out.samples.add(HevcNal.toLengthPrefixed(nals));
            }
        }
        if (out.samples.size() != n) throw new IllegalStateException(out.samples.size() + " coded tiles of " + n);
        if (!out.sets.complete()) throw new IllegalStateException("no VPS / SPS / PPS from the encoder");
        return out;
    }

    private static byte[] bytes(ByteBuffer buffer, int offset, int size) {
        final ByteBuffer b = buffer.duplicate();
        b.position(offset);
        b.limit(offset + size);
        final byte[] out = new byte[size];
        b.get(out);
        return out;
    }

    /** Copies one P010 tile into input buffer {@code index}; returns the size to queue. */
    private static int fill(MediaCodec codec, int index, byte[] p010, int tile, MediaFormat input) {
        Image image = null;
        try {
            image = codec.getInputImage(index);
        } catch (RuntimeException e) {
            Log.d(TAG, "no input image: " + e);
        }
        if (image != null) {
            writeImage(image, p010, tile);
            return P010.tileBytes(tile, tile);
        }
        final ByteBuffer buffer = codec.getInputBuffer(index);
        if (buffer == null) throw new IllegalStateException("no input buffer");
        int stride = input != null && input.containsKey(MediaFormat.KEY_STRIDE) ? input.getInteger(MediaFormat.KEY_STRIDE) : tile * 2;
        if (stride < tile * 2) stride *= 2; // a stride in pixels
        int slice = input != null && input.containsKey(MediaFormat.KEY_SLICE_HEIGHT) ? input.getInteger(MediaFormat.KEY_SLICE_HEIGHT) : tile;
        if (slice < tile) slice = tile;
        return writeBuffer(buffer, p010, tile, stride, slice);
    }

    /** P010 into a plain ByteBuffer: Y rows at {@code stride} bytes, the CbCr plane after {@code slice} rows. */
    static int writeBuffer(ByteBuffer buffer, byte[] p010, int tile, int stride, int slice) {
        final int uvOffset = stride * slice;
        final int size = uvOffset + stride * (tile / 2);
        if (buffer.capacity() < size) throw new IllegalStateException("input buffer of " + buffer.capacity() + " < " + size);
        final ByteBuffer b = buffer.duplicate();
        final int rowBytes = tile * 2;
        for (int r = 0; r < tile; r++) {
            b.position(r * stride);
            b.put(p010, r * rowBytes, rowBytes);
        }
        final int uvBase = tile * tile * 2;
        for (int r = 0; r < tile / 2; r++) {
            b.position(uvOffset + r * stride);
            b.put(p010, uvBase + r * rowBytes, rowBytes);
        }
        return size;
    }

    /** P010 into the planes of an input Image (any row / pixel stride; semi-planar CbCr copied a row at a time). */
    static void writeImage(Image image, byte[] p010, int tile) {
        final Image.Plane[] planes = image.getPlanes();
        if (planes.length < 3) throw new IllegalStateException(planes.length + " planes");
        final ByteBuffer y = planes[0].getBuffer().duplicate().order(ByteOrder.LITTLE_ENDIAN);
        final int yRow = planes[0].getRowStride(), yPixel = planes[0].getPixelStride();
        final int rowBytes = tile * 2;
        for (int r = 0; r < tile; r++) {
            if (yPixel == 2) {
                y.position(r * yRow);
                y.put(p010, r * rowBytes, rowBytes);
            } else {
                for (int c = 0; c < tile; c++) y.putShort(r * yRow + c * yPixel, word(p010, r * rowBytes + 2 * c));
            }
        }
        final ByteBuffer u = planes[1].getBuffer().duplicate().order(ByteOrder.LITTLE_ENDIAN);
        final ByteBuffer v = planes[2].getBuffer().duplicate().order(ByteOrder.LITTLE_ENDIAN);
        final int uRow = planes[1].getRowStride(), uPixel = planes[1].getPixelStride();
        final int vRow = planes[2].getRowStride(), vPixel = planes[2].getPixelStride();
        final int half = tile / 2, uvBase = tile * tile * 2, uvRow = half * 4;
        // Semi-planar with Cr right after Cb (P010's own layout): whole rows go through the Cb plane's buffer.
        final boolean interleaved = uPixel == 4 && vPixel == 4 && uRow == vRow && crFollowsCb(u, v);
        for (int r = 0; r < half; r++) {
            final int at = uvBase + r * uvRow;
            if (interleaved && r * uRow + uvRow <= u.limit()) {
                u.position(r * uRow);
                u.put(p010, at, uvRow);
                continue;
            }
            for (int c = 0; c < half; c++) {
                u.putShort(r * uRow + c * uPixel, word(p010, at + 4 * c));
                v.putShort(r * vRow + c * vPixel, word(p010, at + 4 * c + 2));
            }
        }
    }

    /** Whether the Cr plane starts 2 bytes after the Cb plane (probed by writing through one and reading the other). */
    private static boolean crFollowsCb(ByteBuffer u, ByteBuffer v) {
        if (u.limit() < 4 || v.limit() < 2) return false;
        final byte keepU = u.get(2), keepV = v.get(0);
        u.put(2, (byte) 0x00);
        v.put(0, (byte) 0x5A);
        final boolean shared = u.get(2) == 0x5A;
        u.put(2, keepU);
        v.put(0, keepV);
        return shared;
    }

    private static short word(byte[] p010, int at) {
        return (short) ((p010[at] & 0xFF) | ((p010[at + 1] & 0xFF) << 8));
    }

    /**
     * P010 tiles in raster order: the image rows of a tile band are read from the bitmap (strip copies), the tiles of the
     * band are converted on a small thread pool, and the next band is converted while the encoder takes this one.
     */
    static final class TileSource implements AutoCloseable {
        private final TileGrid grid;
        private final P010.Matrix matrix;
        private final TenBitBitmaps.RowReader reader;
        private final ExecutorService pool;
        private final int[][] bands;
        @SuppressWarnings("unchecked")
        private final Future<byte[]>[][] futures;
        long convertNanos;

        @SuppressWarnings("unchecked")
        TileSource(Bitmap img, TileGrid grid, P010.Matrix matrix) {
            this.grid = grid;
            this.matrix = matrix;
            reader = new TenBitBitmaps.RowReader(img, TenBitBitmaps.STRIP_ROWS);
            final int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
            pool = Executors.newFixedThreadPool(threads, r -> {
                final Thread t = new Thread(r, "Heic10Tiles");
                t.setPriority(Thread.NORM_PRIORITY);
                return t;
            });
            bands = new int[2][grid.width * grid.tile];
            futures = new Future[grid.rows][];
        }

        private void submit(int row) {
            if (row >= grid.rows || futures[row] != null) return;
            final long t0 = System.nanoTime();
            final int[] band = bands[row & 1];
            final int validRows = grid.validHeight(row);
            reader.read(grid.y0(row), validRows, band, 0);
            convertNanos += System.nanoTime() - t0;
            final Future<byte[]>[] f = new Future[grid.columns];
            for (int c = 0; c < grid.columns; c++) {
                final int column = c;
                f[c] = pool.submit(() -> {
                    final byte[] out = new byte[P010.tileBytes(grid.tile, grid.tile)];
                    P010.convertTile(band, grid.width, validRows, grid.x0(column), grid.validWidth(column), grid.tile, grid.tile, out, matrix);
                    return out;
                });
            }
            futures[row] = f;
        }

        /** P010 of tile {@code index} (raster order). Tiles must be taken in order: band buffers are reused. */
        byte[] tile(int index, long deadline) throws Exception {
            final int row = index / grid.columns, column = index % grid.columns;
            submit(row);
            if (column == 0) submit(row + 1); // band row-1 is fully taken: its buffer is free for row+1
            final long t0 = System.nanoTime();
            final long wait = Math.max(1, (deadline - t0) / 1_000_000L);
            final byte[] out = futures[row][column].get(wait, TimeUnit.MILLISECONDS);
            futures[row][column] = null;
            convertNanos += System.nanoTime() - t0;
            return out;
        }

        @Override
        public void close() {
            pool.shutdownNow();
            reader.close();
        }
    }
}

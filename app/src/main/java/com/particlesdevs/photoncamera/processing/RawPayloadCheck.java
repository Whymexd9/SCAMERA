package com.particlesdevs.photoncamera.processing;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.media.Image;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-frame check that a RAW_SENSOR buffer holds the plain 16-bit samples its metadata promises.
 * <p>
 * vivo X100 Ultra, main camera (owner's log, 2026-10-05): the frames of the repeating preview request (the ZSL ring) came as
 * packed 10-bit data behind ImageFormat.RAW_SENSOR with RAW16 metadata (4096x3072, rowStride 8192, 25 165 824 bytes). Read
 * as uint16, the first 5/8 of the rows were codes up to 65535 (white level 1023) and the rest exact zeros, so every Hybrid
 * and SCAM HDR photo came out 3/8 black and 5/8 pink. The post-shutter frames of the same reader were plain: the layout is a
 * property of the frame, not of the stream, so every frame is checked (32 rows x 256 samples, well under a millisecond).
 * <p>
 * A plain sample never exceeds the white level. Up to 4x the white level is tolerated (a HAL that reports 10 bits for 12-bit
 * data still passes); a quarter of the samples above that, or 5 % with a quarter of the rows exactly zero, is a payload that
 * no reader of this app can use. White levels above 4095 (16-bit ranges) are not checked.
 */
public final class RawPayloadCheck {
    static final int ROWS = 32, SAMPLES = 256;
    static final float IMPOSSIBLE_SHARE = 0.25f, IMPOSSIBLE_WITH_ZERO_ROWS = 0.05f, ZERO_ROW_SHARE = 0.25f;

    private RawPayloadCheck() {}

    /** Verdict for one frame: {@link #error} is null for a usable plain 16-bit payload. */
    public static final class Result {
        public final String error;
        /** Share of the sampled values at or above 4x (white + 1), and of the sampled rows that are all zero. */
        public final float impossibleShare, zeroRowShare;
        public final boolean packed10;

        Result(String error, float impossibleShare, float zeroRowShare) {
            this(error, impossibleShare, zeroRowShare, false);
        }

        Result(String error, float impossibleShare, float zeroRowShare, boolean packed10) {
            this.error = error; this.impossibleShare = impossibleShare; this.zeroRowShare = zeroRowShare;
            this.packed10 = packed10;
        }

        public boolean plain() { return error == null; }
        public boolean isPacked10() { return packed10; }
    }

    private static final Result UNCHECKED = new Result(null, 0, 0, false);

    public static Result check(Image image, int whiteLevel) {
        if (image == null || image.getFormat() != ImageFormat.RAW_SENSOR) return UNCHECKED;
        Image.Plane plane = image.getPlanes()[0];
        return check(plane.getBuffer(), image.getWidth(), image.getHeight(), plane.getRowStride(), whiteLevel);
    }

    /** The check on a little-endian RAW16 buffer as the reader delivers it (rows {@code rowStride} bytes apart). */
    public static Result check(ByteBuffer buffer, int width, int height, int rowStride, int whiteLevel) {
        if (buffer == null || width <= 0 || height <= 0 || whiteLevel <= 0 || whiteLevel > 4095) return UNCHECKED;
        String geometry = String.format(Locale.ROOT, "%dx%d, rowStride %d, %d bytes", width, height, rowStride, buffer.capacity());
        if (rowStride < 2 * width || (rowStride & 1) != 0)
            return new Result("RAW16 rows do not fit their stride (" + geometry + ")", 0, 0);
        if (buffer.capacity() < (long) (height - 1) * rowStride + 2L * width)
            return new Result("RAW16 buffer is smaller than its rows (" + geometry + ")", 0, 0);
        ByteBuffer view = buffer.duplicate();
        view.position(0);
        ShortBuffer data = view.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        final int limit = 4 * (whiteLevel + 1), rows = Math.min(ROWS, height), step = Math.max(1, width / SAMPLES);
        long impossible = 0, total = 0;
        int zeroRows = 0;
        for (int r = 0; r < rows; r++) {
            int y = rows == 1 ? 0 : (int) ((long) r * (height - 1) / (rows - 1));
            int base = (int) ((long) y * rowStride / 2);
            boolean zero = true;
            for (int i = 0, x = step / 2; i < SAMPLES && x < width; i++, x += step) {
                int v = data.get(base + x) & 0xffff;
                if (v >= limit) impossible++;
                if (v != 0) zero = false;
                total++;
            }
            if (zero) zeroRows++;
        }
        float share = total == 0 ? 0 : (float) impossible / total, zeroShare = (float) zeroRows / rows;
        boolean packed10 = (share >= IMPOSSIBLE_WITH_ZERO_ROWS && zeroShare >= 0.28f && zeroShare <= 0.45f)
                || (share >= 0.50f && zeroShare >= 0.28f);
        if (share >= IMPOSSIBLE_SHARE || (share >= IMPOSSIBLE_WITH_ZERO_ROWS && zeroShare >= ZERO_ROW_SHARE))
            return new Result(String.format(Locale.ROOT,
                    "RAW is not plain 16-bit: %.1f %% of samples at or above 4x white %d, %.1f %% zero rows (%s)",
                    share * 100, whiteLevel, zeroShare * 100, geometry), share, zeroShare, packed10);
        return new Result(null, share, zeroShare, false);
    }

    /**
     * Row stride in bytes of a packed MIPI RAW10 payload behind a RAW_SENSOR buffer, 0 when it cannot be read from the data.
     * <p>
     * Xiaomi 17 Ultra, tele 4 through logical camera 0 (owner's log, 2026-10-09): 4080x3072 RAW_SENSOR, rowStride 8160, the
     * first 62.5 % of the buffer packed RAW10 and the rest zero. The reported rowStride is the RAW16 one, so the packed rows'
     * stride is found from where the data ends: every packed group holds four high bytes of samples above black (never zero),
     * so the payload is one run of non-zero 64-byte blocks followed by zeros. The smallest stride from width*10/8 whose
     * {@code height} rows end there (last row with or without its padding) is the stride.
     */
    public static int packedStride(ByteBuffer buffer, int width, int height) {
        if (buffer == null || width < 4 || (width & 3) != 0 || height < 2) return 0;
        final int rowBytes = width * 10 / 8, capacity = buffer.capacity(), block = 64;
        if ((long) rowBytes * height > capacity) return 0;
        // Binary search for the first all-zero block after which the buffer stays zero.
        int lo = 0, hi = capacity / block;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (zeroBlock(buffer, mid * block, Math.min(block, capacity - mid * block))) hi = mid; else lo = mid + 1;
        }
        int end = Math.min(capacity, lo * block);
        while (end > 0 && buffer.get(end - 1) == 0) end--;
        // No zero tail (a plain frame, or packed rows over a stale frame): nothing to read the stride from.
        if (end <= 0 || end > capacity - rowBytes) return 0;
        for (int stride = rowBytes; stride <= rowBytes + 512; stride++) {
            long first = (long) (height - 1) * stride + rowBytes, all = (long) height * stride;
            if (all > capacity) break;
            // A zero last low-bits byte (or a zero padding tail) ends the data a few bytes early.
            if (first <= end + 8L && end <= all) return stride;
        }
        return 0;
    }

    private static boolean zeroBlock(ByteBuffer buffer, int from, int length) {
        for (int i = 0; i < length; i++) if (buffer.get(from + i) != 0) return false;
        return true;
    }

    /** White level the check compares against: the larger of the static and the frame's dynamic white, 0 when unknown. */
    public static int whiteLevel(CameraCharacteristics characteristics, CaptureResult result) {
        int white = 0;
        Integer stat = characteristics == null ? null : characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
        if (stat != null) white = stat;
        Integer dynamic = result == null ? null : result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL);
        if (dynamic != null) white = Math.max(white, dynamic);
        return white;
    }

    private static final Set<String> sDumped = ConcurrentHashMap.newKeySet();

    /**
     * Once per camera and process: the head of a rejected buffer and the bytes around 5/8 and 3/4 of it (where a 10- or 12-bit
     * payload ends) go to Download/SCAMERA/raw-payload-*.zip with its metadata, so the layout can be read from real bytes.
     */
    public static void dumpOnce(Image image, Result result, String cameraId) {
        if (image == null || result == null || result.plain()) return;
        // A packed RAW10 payload whose stride is read from the data is understood and unpacked (Xiaomi 17 Ultra tele): no zip
        // in Download/SCAMERA on every start for it.
        if (result.isPacked10() && packedStride(image.getPlanes()[0].getBuffer(), image.getWidth(), image.getHeight()) > 0) return;
        if (!sDumped.add(String.valueOf(cameraId))) return;
        try {
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer src = plane.getBuffer().duplicate();
            src.position(0);
            int capacity = src.capacity();
            String meta = String.format(Locale.ROOT,
                    "camera=%s device=%s format=%d size=%dx%d rowStride=%d pixelStride=%d capacity=%d timestamp=%d%n%s%n"
                            + "head.bin=[0,%d) mid58.bin starts at %d, mid34.bin starts at %d%n",
                    cameraId, android.os.Build.DEVICE, image.getFormat(), image.getWidth(), image.getHeight(),
                    plane.getRowStride(), plane.getPixelStride(), capacity, image.getTimestamp(), result.error,
                    Math.min(capacity, 256 << 10), Math.max(0, capacity / 8 * 5 - (32 << 10)), Math.max(0, capacity / 4 * 3 - (32 << 10)));
            byte[] head = slice(src, 0, 256 << 10), mid58 = slice(src, capacity / 8 * 5 - (32 << 10), 64 << 10),
                    mid34 = slice(src, capacity / 4 * 3 - (32 << 10), 64 << 10);
            String name = "raw-payload-" + cameraId + "-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new java.util.Date());
            new Thread(() -> export(name, meta, head, mid58, mid34), "raw-payload-dump").start();
        } catch (RuntimeException e) {
            com.particlesdevs.photoncamera.util.Log.w("RawPayload", "dump failed: " + e);
        }
    }

    private static byte[] slice(ByteBuffer src, int from, int length) {
        from = Math.max(0, Math.min(from, src.capacity()));
        byte[] out = new byte[Math.min(length, src.capacity() - from)];
        ByteBuffer view = src.duplicate();
        view.position(from);
        view.get(out);
        return out;
    }

    private static void export(String name, String meta, byte[] head, byte[] mid58, byte[] mid34) {
        android.content.Context context = com.particlesdevs.photoncamera.app.PhotonCamera.getAppContext();
        android.net.Uri uri = null;
        try {
            java.io.OutputStream stream;
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name + ".zip");
                v.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/zip");
                v.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/SCAMERA");
                uri = context.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                stream = uri == null ? null : context.getContentResolver().openOutputStream(uri);
            } else {
                java.io.File dir = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS), "SCAMERA");
                dir.mkdirs();
                stream = new java.io.FileOutputStream(new java.io.File(dir, name + ".zip"));
            }
            if (stream == null) throw new java.io.IOException("cannot create " + name + ".zip");
            try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(stream)) {
                put(zip, "meta.txt", meta.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                put(zip, "head.bin", head);
                put(zip, "mid58.bin", mid58);
                put(zip, "mid34.bin", mid34);
            }
            com.particlesdevs.photoncamera.util.Log.i("RawPayload", "Saved Download/SCAMERA/" + name + ".zip");
        } catch (Exception e) {
            if (uri != null) try { context.getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
            com.particlesdevs.photoncamera.util.Log.w("RawPayload", "dump export failed: " + e);
        }
    }

    private static void put(java.util.zip.ZipOutputStream zip, String entry, byte[] data) throws java.io.IOException {
        zip.putNextEntry(new java.util.zip.ZipEntry(entry));
        zip.write(data);
        zip.closeEntry();
    }
}

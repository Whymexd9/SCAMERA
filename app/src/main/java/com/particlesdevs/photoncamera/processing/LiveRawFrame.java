package com.particlesdevs.photoncamera.processing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The latest preview RAW frame and the sensor parameters needed to develop it,
 * handed from the capture thread to the GL preview thread.
 *
 * <p>This is what makes a live viewfinder possible at all: the viewfinder can
 * only show the pipeline's tone placement if it develops the same data the
 * pipeline will, and that means RAW, not the ISP's YUV. The frames are already
 * arriving - the ZSL ring is fed from the same stream - so no extra output
 * stream or HAL load is involved.
 *
 * <p>One frame is kept, not a queue: the viewfinder wants the newest frame, and
 * an older one is worthless the moment a newer arrives. The buffers are reused
 * across frames, so a 12.6 MP RAW costs its allocations once for the session
 * rather than once per frame.
 *
 * <p>Held statically because the producer (CaptureController's image callback)
 * and the consumer (the GL renderer) have no other connection, the same
 * arrangement SceneLuma uses.
 */
public final class LiveRawFrame {

    private static final Object LOCK = new Object();

    /**
     * Three buffers, not one. The capture thread writes while the GL thread is
     * uploading the previous frame, and sharing a single buffer let the upload
     * read bytes from two different exposures - which shows up as the
     * viewfinder flickering between colours rather than as a clean error.
     * P314 (owner, 2026-10-21: the reference viewfinder «обработку он никак не замедляет»; ours held a 2x ISZ merge at 21-56 s):
     * the capture thread fills {@code writer}, {@code ready} is the newest whole frame, {@code consumer} the GL thread's (being
     * uploaded and metered) until its next {@link #acquire()}. publish swaps writer and ready, acquire ready and consumer, both
     * under the lock - acquire no longer copies the frame (24 MB at 2x ISZ, under the lock, on the GL thread at every new frame).
     */
    private static ByteBuffer writer;
    private static ByteBuffer ready;
    // Owned exclusively by the single GL consumer, never reused by the producer.
    private static ByteBuffer consumer;
    /** P314: {@code ready} holds a frame acquire() has not taken yet. */
    private static boolean readyFresh;
    /**
     * P314: drop-when-busy, as the reference viewfinder's hand-off: while the GL thread has not taken the last frame yet, a new one
     * is not copied (the camera thread's copies follow the viewfinder's draws, not the camera's rate). A frame not taken within this
     * long is replaced all the same (the viewfinder not drawing RAW for a while - its programs being linked, a paused view - must not
     * be left with an old frame).
     */
    static final long PENDING_NS = 100_000_000L;
    /** The limit in force (PENDING_NS; tests hold a frame for as long as they need: a slow machine must not make them flaky). */
    static volatile long pendingNs = PENDING_NS;
    /** P314: frames the camera delivered while the last one was still waiting for the GL thread (counted, logged with draws). */
    private static long dropped;
    private static int width, height, rowStride;
    private static float[] crop = {0,0,1,1};
    private static int cfaPattern, cfaBlock=1, iso=100, maxAnalogIso=6400;
    private static boolean autoExposure=true;
    /** P314: the frame is a Quad / Tetra frame's cell means (see {@link Frame#cellMean}). */
    private static boolean cellMean;
    /** P212h (A6 = M11): the range the viewfinder's display gain is held to, and whether its meter gives SceneLuma. */
    private static float minDisplayGain=0f, maxDisplayGain=Float.POSITIVE_INFINITY;
    private static boolean sceneLuma=true;
    private static double noiseS,noiseO;
    private static float whiteLevel = 1023.0f;
    private static final float[] blackLevel = new float[] {0, 0, 0, 0};
    /** P315: the black of a stream that came without its pedestal (PedestalFloor), measured on the viewfinder's own frames. */
    private static final PedestalFloor PEDESTAL = new PedestalFloor();
    /** P315: the black may come from the frame's floor (CaptureController: no black override and «чёрный по данным» on). */
    private static volatile boolean blackFromData = true;

    public static void setBlackFromData(boolean value) { blackFromData = value; }
    private static final float[] wbGains = new float[] {1, 1, 1};
    private static final float[] colorTransform = new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
    /** P108: frames this large with a 2x2 mosaic are binned for the viewfinder (a 50 MP Quad-Bayer full-resolution stream). */
    static final long QUAD_BIN_PIXELS = 30_000_000L;
    /** P179: above this a Tetra viewfinder frame is binned from the central 2 x 2 of each block. */
    static final long SPARSE_BIN_PIXELS = 100_000_000L;
    private static int sparseLogged;
    private static final long QUAD_BIN_INTERVAL_NS = 150_000_000L;
    private static long quadAtNs;
    /** P188: how long the last binning took (ns). */
    private static volatile long lastBinNs;

    /**
     * P188 (owner, 2026-10-17: the 50 MP FULLRES viewfinder ran at ~7 fps «как при 200мп», it must run at 30): the camera thread
     * waits at least this long between two binned frames - QUAD_BIN_INTERVAL_NS above 100 MP (200 MP: the HAL gives ~7 fps anyway),
     * below it what the last binning took and a quarter more, at most QUAD_BIN_INTERVAL_NS (50 MP sparse bin: a few ms, so every
     * frame; a packed RAW10 / RAW12 frame that is slow to unpack keeps the camera thread free as before). P191: twice the last
     * binning held a 45 ms bin at ~11 fps.
     */
    /**
     * P319 (user report: «Лайв-вью работает гораздо тормознее»): a Quad / Tetra frame's cell means (P314: a few ms) are taken for
     * every frame - P188's pacing, made for the 50 / 200 MP bins, halved a 2x ISZ viewfinder to 15 fps whenever one bin took
     * longer than ~27 ms (a merge running, a hot phone). Paced only when the bin itself is near a frame's time.
     */
    static final long CELL_MEAN_PACE_NS = 30_000_000L;

    static long paceNs(boolean cellMeans, long pixels, long lastBin) {
        return cellMeans && lastBin < CELL_MEAN_PACE_NS ? 0 : binIntervalNs(pixels, lastBin);
    }

    static long binIntervalNs(long pixels, long lastBin) {
        final long bin = Math.max(0, lastBin);
        return pixels > SPARSE_BIN_PIXELS ? QUAD_BIN_INTERVAL_NS : Math.min(QUAD_BIN_INTERVAL_NS, bin + bin / 4);
    }
    private static ByteBuffer quadFull, quadBinned;

    /**
     * P108: the Quad-Bayer frame's 2x2 same-colour cells averaged into one Bayer site each ((w/2) x (h/2) uint16, native order),
     * from a packed RAW10 / RAW12 (unpacked first, natively) or a plain 16-bit frame; null when the geometry does not fit.
     */
    static synchronized ByteBuffer binQuad(ByteBuffer plane, int w, int h, int stride, int format) {
        return binMosaic(plane, w, h, stride, format, 2);
    }

    static synchronized ByteBuffer binMosaic(ByteBuffer plane, int w, int h, int stride, int format, int block) {
        return binMosaic(plane, w, h, stride, format, block, sparseBin(w, h, block));
    }

    /**
     * The viewfinder frame takes a quarter of the sites: P179, a 200 MP Tetra frame the central 2 x 2 of each block; P181
     * (owner, 2026-10-17: «Для 50мп сделай тоже упрощенный видоискатель как ты сделал для 200мп»), a 50 MP Quad frame one site
     * of each 2 x 2 cell (the screen shows the frame downscaled again, the photo never takes it).
     */
    static boolean sparseBin(int w, int h, int block) {
        final long pixels = (long) w * h;
        return block == 4 ? pixels > SPARSE_BIN_PIXELS : (block == 2 || block == 1) && pixels >= QUAD_BIN_PIXELS;
    }

    /**
     * P194 (the X100 / X300 Ultra's 50 MP FULLRES by the hardware remosaic: a plain Bayer RAW-only stream): {@code block} 1 is
     * sparse only - one 2 x 2 Bayer quad of each 4 x 4 (the sites (4i + a, 4j + b), a, b in {0, 1}), a (w/2) x (h/2) plain Bayer
     * of the same CFA phase, a quarter of the reads as the 50 MP Quad bin; the viewfinder only, never the photo.
     */
    /**
     * P314 (review): the bins' own threads (the caller and two): in the shared ParallelWork pool a shot's CPU pass held the camera
     * thread for its whole length on every frame of a viewfinder that kept running beside the shot.
     */
    private static final com.particlesdevs.photoncamera.util.ParallelWork.Own BIN_WORK =
            new com.particlesdevs.photoncamera.util.ParallelWork.Own("SCAMERA-vf-bin", 3);

    static synchronized ByteBuffer binMosaic(ByteBuffer plane, int w, int h, int stride, int format, int block, boolean sparse) {
        final int cell = block == 1 ? 2 : block;
        if (plane == null || (block != 1 && block != 2 && block != 4) || (block == 1 && !sparse) || w < cell * 2 || h < cell * 2
                || w % (cell * 2) != 0 || h % (cell * 2) != 0 || (long) w * h * 2 > Integer.MAX_VALUE) return null;
        final long callStart = System.nanoTime(); // P188: the unpack of a packed frame counts too
        final java.nio.ShortBuffer src;
        final int srcRow;
        if (com.particlesdevs.photoncamera.util.RawUnpack.isPacked(format)) {
            if (quadFull == null || quadFull.capacity() < w * h * 2) quadFull = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder());
            quadFull.clear();
            if (!com.particlesdevs.photoncamera.util.RawUnpack.unpack(plane.duplicate(), format, w, h, stride, quadFull)) return null;
            src = quadFull.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer();
            srcRow = w;
        } else {
            if ((stride & 1) != 0 || (long) (h - 1) * stride + w * 2L > plane.remaining()) return null;
            ByteBuffer b = plane.duplicate().order(ByteOrder.nativeOrder());
            b.position(plane.position());
            src = b.slice().order(ByteOrder.nativeOrder()).asShortBuffer();
            srcRow = stride / 2;
        }
        final int bw = w / cell, bh = h / cell;
        if (quadBinned == null || quadBinned.capacity() < bw * bh * 2) quadBinned = ByteBuffer.allocateDirect(bw * bh * 2).order(ByteOrder.nativeOrder());
        java.nio.ShortBuffer out = quadBinned.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer();
        final int chunks = Math.max(1, Math.min(bh, BIN_WORK.threads() * 4));
        // P179 (200 MP: the preview ran at 2.7 fps, its development held the camera's buffers): above SPARSE_BIN_PIXELS a
        // Tetra block is the mean of its central 2 x 2 pixels (a quarter of the reads) - the viewfinder only, never the photo
        final long binStart = System.nanoTime();
        // P191 (owner, 2026-10-17: the 50 MP viewfinder still ran at ~11 fps): rows go through short[] in bulk - the per-site
        // ShortBuffer get / put took ~45 ms a 50 MP frame, and the wait of twice that held the viewfinder at ~11 of the camera's 30 fps
        final java.nio.ShortBuffer source = src, target = out;
        final int rows = sparse ? (block == 4 ? 2 : 1) : block, first = sparse && block == 4 ? 1 : 0;
        final int span = block == 1 ? w - 2 : sparse ? w - 1 : w;
        BIN_WORK.forEach(chunks, chunk -> {
            final java.nio.ShortBuffer in = source.duplicate(), put = target.duplicate();
            final short[][] line = new short[rows][span];
            final short[] row = new short[bw];
            for (int y = bh * chunk / chunks; y < bh * (chunk + 1) / chunks; y++) {
                for (int r = 0; r < rows; r++) {
                    in.position((block == 1 ? 4 * (y >> 1) + (y & 1) : block * y + first + r) * srcRow);
                    in.get(line[r], 0, span);
                }
                if (block == 1) {
                    final short[] a = line[0];
                    for (int x = 0; x < bw; x++) row[x] = a[4 * (x >> 1) + (x & 1)];
                } else if (sparse && block == 2) {
                    final short[] a = line[0];
                    for (int x = 0; x < bw; x++) row[x] = a[2 * x];
                } else if (sparse) {
                    final short[] a = line[0], b = line[1];
                    for (int x = 0; x < bw; x++) {
                        final int m = 4 * x + 1;
                        final int s = (a[m] & 0xFFFF) + (a[m + 1] & 0xFFFF) + (b[m] & 0xFFFF) + (b[m + 1] & 0xFFFF);
                        row[x] = (short) ((s + 2) / 4);
                    }
                } else {
                    final int half = block * block / 2, n = block * block;
                    for (int x = 0; x < bw; x++) {
                        final int c = block * x;
                        int s = 0;
                        for (int dy = 0; dy < block; dy++) {
                            final short[] a = line[dy];
                            for (int dx = 0; dx < block; dx++) s += a[c + dx] & 0xFFFF;
                        }
                        row[x] = (short) ((s + half) / n);
                    }
                }
                put.position(y * bw);
                put.put(row, 0, bw);
            }
        });
        lastBinNs = System.nanoTime() - callStart;
        if (sparse && ++sparseLogged % 20 == 1)
            android.util.Log.i("LiveRawFrame", (block == 4 ? "200 MP viewfinder bin (centre 2x2 of each block)" : block == 1
                    ? "50 MP Bayer viewfinder bin (one 2x2 quad of each 4x4)" : "50 MP viewfinder bin (one site of each cell)")
                    + " ms=" + (System.nanoTime() - binStart) / 1_000_000);
        ByteBuffer result = quadBinned.duplicate().order(ByteOrder.nativeOrder());
        result.clear();
        result.limit(bw * bh * 2);
        return result;
    }

    /**
     * P266 (owner, 2026-10-19: «это тоже можно добавить» - the RAW viewfinder's resolution, the RAW's aspect kept, the photo
     * unchanged): the longest side the viewfinder's frame may have. 0 = auto (as before: 50 / 200 MP frames binned), -1 = the
     * full frame (no binning at all), else a plain Bayer frame halved (one 2 x 2 quad of each 4 x 4) until it fits - P316 a packed
     * RAW10 / RAW12 one and a mosaic's cell means too.
     */
    private static volatile int maxLongSide;
    private static ByteBuffer halfA, halfB;
    /** P316: a packed frame unpacked before its halvings (the size cap of RAW10 / RAW12 frames). */
    private static ByteBuffer unpacked;

    public static void setMaxLongSide(int px) {
        maxLongSide = px;
    }

    /** How many halvings bring {@code w} x {@code h} to a longest side of at most {@code cap} (keeping both sides whole 4s). */
    static int halvings(int w, int h, int cap) {
        int n = 0;
        while (cap > 0 && Math.max(w, h) > cap && w % 4 == 0 && h % 4 == 0 && w >= 8 && h >= 8) {
            w /= 2;
            h /= 2;
            n++;
        }
        return n;
    }

    /** A plain 16-bit Bayer frame at half size: one 2 x 2 quad of each 4 x 4 (the CFA phase kept); into {@code into}. */
    static ByteBuffer halveBayer(ByteBuffer plane, int w, int h, int stride, ByteBuffer into) {
        final int bw = w / 2, bh = h / 2;
        final java.nio.ShortBuffer in = plane.duplicate().order(ByteOrder.nativeOrder()).slice().order(ByteOrder.nativeOrder()).asShortBuffer();
        final java.nio.ShortBuffer out = into.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer();
        final short[] line = new short[w], row = new short[bw];
        final int srcRow = stride / 2;
        for (int y = 0; y < bh; y++) {
            in.position((4 * (y >> 1) + (y & 1)) * srcRow);
            in.get(line, 0, w);
            for (int x = 0; x < bw; x++) row[x] = line[4 * (x >> 1) + (x & 1)];
            out.position(y * bw);
            out.put(row, 0, bw);
        }
        ByteBuffer result = into.duplicate().order(ByteOrder.nativeOrder());
        result.clear();
        result.limit(bw * bh * 2);
        return result;
    }

    /** Bumped on every published frame so the renderer can skip re-uploading. */
    private static int version = 0;
    private static float[] shading = {1,1,1};
    private static int shadingWidth=1,shadingHeight=1;
    private static volatile boolean enabled = false;
    /** P13: frames are published because the stream is a colour-block mosaic (shown even with the live RAW switch off). */
    private static volatile boolean mosaicPreview = false;

    public static void setMosaicPreview(boolean value) { mosaicPreview = value; }

    public static boolean isMosaicPreview() { return mosaicPreview && enabled; }

    private LiveRawFrame() {}

    /** The producer checks this before copying: no consumer, no copy. */
    public static boolean isEnabled() {
        return enabled;
    }

    private static int session;
    private static long publishedNanos;

    public static void setEnabled(boolean value) {
        synchronized (LOCK) {
            if (enabled == value) return;
            enabled = value;
            session++;
            if (!value) {
                writer = ready = consumer = null;
                readyFresh = false;
                version++;
            }
        }
        if (!value) releaseBinBuffers();
    }

    /** P314: whether a frame waits for the GL thread and is young enough that a new one is dropped instead of copied. */
    static boolean pending(boolean fresh, long ageNs) {
        return fresh && ageNs >= 0 && ageNs < pendingNs;
    }

    /**
     * P314: whether {@link #publish} would take a frame now (enabled, and the last one taken by the GL thread or old): the
     * producer asks before it reads the frame's metadata, and requests no draw for a frame it skips (the waiting one has its draw
     * requested already).
     */
    public static boolean takesFrame() {
        synchronized (LOCK) {
            if (!enabled) return false;
            if (!pending(readyFresh, System.nanoTime() - publishedNanos)) return true;
            dropped++;
            return false;
        }
    }

    /** P314: the camera's frames dropped so far while one waited for the GL thread. */
    public static long droppedFrames() {
        synchronized (LOCK) {
            return dropped;
        }
    }

    /**
     * P187: the binning buffers go with the RAW viewfinder (the next frame makes them again): 25 MB, and on a packed 200 MP
     * stream another 401 MB of unpacked frame, were held for the rest of the process - under a 200 MP merge too. Nulled, not
     * freed: a frame being published may still read them.
     */
    private static synchronized void releaseBinBuffers() {
        quadFull = quadBinned = null;
        quadAtNs = 0;   // P314: the next session's first binned frame is not held back by this one's pacing
    }

    /**
     * Publish a frame. The plane is copied because the Image it came from is
     * recycled as soon as this returns.
     */
    public static void publish(ByteBuffer plane, int w, int h, int stride,
                               int cfa, float white, float[] black,
                               float[] gains, float[] ccm) {
        publish(plane,w,h,stride,cfa,white,black,gains,ccm,null,1,1);
    }
    public static void publish(ByteBuffer plane,int w,int h,int stride,int cfa,float white,float[] black,float[] gains,float[] ccm,float[] lensShading,int sw,int sh){
        publish(plane,w,h,stride,cfa,white,black,gains,ccm,lensShading,sw,sh,null);
    }
    public static void publish(ByteBuffer plane,int w,int h,int stride,int cfa,float white,float[] black,float[] gains,float[] ccm,float[] lensShading,int sw,int sh,float[] cropRect){
        publish(plane,w,h,stride,cfa,white,black,gains,ccm,lensShading,sw,sh,cropRect,1,100,6400,true);
    }
    public static void publish(ByteBuffer plane,int w,int h,int stride,int cfa,float white,float[] black,float[] gains,float[] ccm,float[] lensShading,int sw,int sh,float[] cropRect,int block,Integer sensorIso,Integer analogIso,boolean automatic){
        publish(plane,w,h,stride,cfa,white,black,gains,ccm,lensShading,sw,sh,cropRect,block,sensorIso,analogIso,automatic,0,0);
    }
    public static void publish(ByteBuffer plane,int w,int h,int stride,int cfa,float white,float[] black,float[] gains,float[] ccm,float[] lensShading,int sw,int sh,float[] cropRect,int block,Integer sensorIso,Integer analogIso,boolean automatic,double shotNoise,double readNoise){
        publish(plane,w,h,stride,cfa,white,black,gains,ccm,lensShading,sw,sh,cropRect,block,sensorIso,analogIso,automatic,shotNoise,readNoise,
                android.graphics.ImageFormat.RAW_SENSOR);
    }
    /** format: RAW_SENSOR (16-bit rows), or RAW10 / RAW12 (packed rows, unpacked into the frame buffer: the viewfinder is the same). */
    public static void publish(ByteBuffer plane,int w,int h,int stride,int cfa,float white,float[] black,float[] gains,float[] ccm,float[] lensShading,int sw,int sh,float[] cropRect,int block,Integer sensorIso,Integer analogIso,boolean automatic,double shotNoise,double readNoise,int format){
        publish(plane,w,h,stride,cfa,white,black,gains,ccm,lensShading,sw,sh,cropRect,block,sensorIso,analogIso,automatic,shotNoise,readNoise,
                format,0f,Float.POSITIVE_INFINITY,true);
    }
    /**
     * P212h (A6 = M11): with the range LiveRawMeter holds an automatic display gain to ({@code minGain} .. {@code maxGain}: the OPPO
     * own AE's deficit, P212a) and whether the frame's meter is the software shutter / ISO priority's SceneLuma ({@code sceneLuma}:
     * not where the own AE meters the other value itself).
     */
    public static void publish(ByteBuffer plane,int w,int h,int stride,int cfa,float white,float[] black,float[] gains,float[] ccm,float[] lensShading,int sw,int sh,float[] cropRect,int block,Integer sensorIso,Integer analogIso,boolean automatic,double shotNoise,double readNoise,int format,
                               float minGain,float maxGain,boolean sceneLuma){
        // P314: nothing is binned or copied while the GL thread has not taken the last frame (drop-when-busy)
        if (plane == null || !takesFrame()) return;
        publish(plane,w,h,stride,cfa,white,black,gains,ccm,lensShading,sw,sh,cropRect,block,sensorIso,analogIso,automatic,shotNoise,readNoise,
                format,minGain,maxGain,sceneLuma,false);
    }

    /**
     * P314: a mosaic (Quad / Tetra) frame below the full-resolution sizes is binned for the viewfinder too - the mean of each colour
     * cell, one Bayer site ((w / block) x (h / block)): the reference viewfinder's shape at the frame's real colour resolution. It is
     * lossless for the viewfinder: rawdevelop_fs draws a mosaic of block 2 / 4 one colour per 2 x 2 of cell means (getFastColor),
     * and the binned frame with block 1 drawn the same way ({@link Frame#cellMean}: cfa_mode 0, not the range-filtered Bayer of a
     * plain frame) reads those same means, up to the 1/2 DN rounding of the mean. A 2x ISZ Quad frame is 6 MB to copy and upload
     * instead of 24, its 16 fetches a pixel 4. Not with «Full» (cap -1: nothing binned, as before).
     */
    static boolean binsCellMeans(int w, int h, int block, int cap) {
        return cap != -1 && (block == 2 || block == 4) && (long) w * h < QUAD_BIN_PIXELS && w % (block * 2) == 0 && h % (block * 2) == 0;
    }

    private static void publish(ByteBuffer plane,int w,int h,int stride,int cfa,float white,float[] black,float[] gains,float[] ccm,float[] lensShading,int sw,int sh,float[] cropRect,int block,Integer sensorIso,Integer analogIso,boolean automatic,double shotNoise,double readNoise,int format,
                               float minGain,float maxGain,boolean sceneLuma,boolean means){
        if (!enabled || plane == null) return;
        // P108: a full-resolution Quad-Bayer frame (Pixel 10 Pro: 8160x6144 RAW10, the ISP's own viewfinder is green in that
        // mode) is shown 2x2-binned as the plain Bayer 4080x3072, at most every QUAD_BIN_INTERVAL_NS (the camera thread); P194 a
        // plain Bayer frame as large (the hardware-remosaic FULLRES) one 2x2 quad of each 4x4, the same (w/2) x (h/2)
        // P314: and a smaller Quad / Tetra frame by its cell means (binsCellMeans); the large frames' bins stay as they were
        final int bin = block == 2 || block == 4 ? block : 1, cell = bin == 1 ? 2 : bin;
        final int cap = maxLongSide;
        final boolean cellMeans = !means && binsCellMeans(w, h, bin, cap);
        if (!means && (cellMeans || (cap != -1 && (long) w * h >= QUAD_BIN_PIXELS && w % (cell * 2) == 0 && h % (cell * 2) == 0))) {
            final long now = System.nanoTime();
            if (now - quadAtNs < paceNs(cellMeans, (long) w * h, lastBinNs)) return;
            quadAtNs = now;
            ByteBuffer binned = binMosaic(plane, w, h, stride, format, bin);
            if (binned == null) return;
            publish(binned, w / cell, h / cell, w / cell * 2, cfa, white, black, gains, ccm, lensShading, sw, sh, cropRect, 1, sensorIso, analogIso,
                    automatic, shotNoise, readNoise, android.graphics.ImageFormat.RAW_SENSOR, minGain, maxGain, sceneLuma, cellMeans);
            return;
        }
        // P266: the module's «Разрешение видоискателя RAW» - a plain Bayer frame halved until it fits. P316 (settings audit): a
        // packed RAW10 / RAW12 frame too (unpacked first: the X8U's default raw10, RAW10-only lenses), and a Quad / Tetra frame's
        // cell means (P314: one Bayer site a cell, halved as any Bayer frame) - the steps used to change only 16-bit plain Bayer
        final boolean packedIn = com.particlesdevs.photoncamera.util.RawUnpack.isPacked(format);
        if (cap > 0 && bin == 1 && (packedIn
                ? (long) (h - 1) * stride + com.particlesdevs.photoncamera.util.RawUnpack.packedRowBytes(format, w) <= plane.remaining()
                        && (long) w * h * 2 <= Integer.MAX_VALUE
                : format == android.graphics.ImageFormat.RAW_SENSOR && (stride & 1) == 0
                        && (long) (h - 1) * stride + w * 2L <= plane.remaining())) {
            final int n = halvings(w, h, cap);
            if (n > 0) {
                ByteBuffer src = plane;
                int sw0 = w, sh0 = h, st = stride;
                synchronized (LiveRawFrame.class) {
                    if (packedIn) {
                        final int need = w * h * 2;
                        if (unpacked == null || unpacked.capacity() < need)
                            unpacked = ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder());
                        unpacked.clear();
                        if (!com.particlesdevs.photoncamera.util.RawUnpack.unpack(plane.duplicate(), format, w, h, stride, unpacked)) return;
                        unpacked.position(0);
                        unpacked.limit(need);
                        src = unpacked;
                        st = w * 2;
                    }
                    for (int i = 0; i < n; i++) {
                        final int need = (sw0 / 2) * (sh0 / 2) * 2;
                        ByteBuffer into = (i & 1) == 0 ? halfA : halfB;
                        if (into == null || into.capacity() < need) {
                            into = ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder());
                            if ((i & 1) == 0) halfA = into; else halfB = into;
                        }
                        src = halveBayer(src, sw0, sh0, st, into);
                        sw0 /= 2;
                        sh0 /= 2;
                        st = sw0 * 2;
                    }
                }
                plane = src;
                w = sw0;
                h = sh0;
                stride = st;
                format = android.graphics.ImageFormat.RAW_SENSOR;
            }
        }
        final boolean packed = com.particlesdevs.photoncamera.util.RawUnpack.isPacked(format);
        final long rowBytes = packed ? com.particlesdevs.photoncamera.util.RawUnpack.packedRowBytes(format, w) : w * 2L;
        if (w < 2 || h < 2 || cfa < 0 || cfa > 3 || stride < rowBytes || (!packed && (stride & 1) != 0)
                || (long)(h-1)*stride+rowBytes > plane.remaining() || !Float.isFinite(white) || white <= 0) return;
        if ((long)w*h*2 > Integer.MAX_VALUE) return;
        block = block==2 || block==4 ? block : 1;
        if (w < 2*block || h < 2*block) return;
        if (black!=null) { if(black.length<4)return; for(float v:black)if(!Float.isFinite(v)||v<0||v>=white)return; }
        if (gains!=null) { if(gains.length<3)return; for(float v:gains)if(!Float.isFinite(v)||v<=0)return; }
        if (ccm!=null) { if(ccm.length<9)return; for(float v:ccm)if(!Float.isFinite(v))return; }
        if (cropRect!=null) {
            if(cropRect.length!=4)return;
            for(float v:cropRect)if(!Float.isFinite(v))return;
            if(cropRect[0]<0||cropRect[1]<0||cropRect[2]<=0||cropRect[3]<=0||cropRect[0]+cropRect[2]>1.001f||cropRect[1]+cropRect[3]>1.001f)return;
        }
        if(lensShading!=null) {
            boolean valid=sw>0&&sh>0&&(long)sw*sh*3==lensShading.length;
            for(float v:lensShading)valid &= Float.isFinite(v)&&v>0;
            if(!valid)lensShading=null;
        }
        int needed = w*h*2;
        final int generation;
        ByteBuffer target;
        synchronized (LOCK) {
            if (!enabled) return;
            generation = session;
            if (writer == null || writer.capacity() < needed) {
                writer = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder());
            }
            target = writer;
        }
        // Filled outside the lock: the copy is tens of megabytes and the GL
        // thread only needs the lock long enough to swap references.
        target.clear();
        if (packed) {
            if (!com.particlesdevs.photoncamera.util.RawUnpack.unpack(plane.duplicate(), format, w, h, stride, target)) return;
            target.position(0); target.limit(needed);
        } else {
            ByteBuffer src = plane.duplicate();
            int start = src.position();
            for (int y=0;y<h;y++) {
                src.limit(start+y*stride+w*2);
                src.position(start+y*stride);
                target.put(src);
            }
            target.flip();
        }
        // P315 (OPPO Find X8 Ultra, sensor mode 8: the RAW without its 64 DN black pedestal, the viewfinder green and half black):
        // the frame's own floor when its samples prove the pedestal lost (the GL develop, LiveWhyMeter and LiveRawMeter read it)
        if (black != null && blackFromData && com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevSwitch("vf_black_floor", true))
            black = PEDESTAL.black(generation, target, w, h, w * 2, block, black);
        synchronized (LOCK) {
            if (!enabled || session != generation) return;
            publishedNanos = System.nanoTime();
            // P314: the filled buffer becomes the ready frame; the ready one (never the GL thread's) is the next to fill
            writer = ready;
            ready = target;
            readyFresh = true;
            width = w;
            height = h;
            rowStride = w*2;
            cfaBlock = block==2 || block==4 ? block : 1;
            cellMean = means;
            iso = sensorIso==null ? 100 : Math.max(1,sensorIso);
            maxAnalogIso = analogIso==null ? 6400 : Math.max(101,analogIso);
            autoExposure = automatic;
            minDisplayGain = Float.isNaN(minGain) ? 0f : Math.max(0f, minGain);
            maxDisplayGain = Float.isNaN(maxGain) ? Float.POSITIVE_INFINITY : Math.max(minDisplayGain, maxGain);
            LiveRawFrame.sceneLuma = sceneLuma;
            noiseS=Double.isFinite(shotNoise)?Math.max(0,shotNoise):0;noiseO=Double.isFinite(readNoise)?Math.max(0,readNoise):0;
            cfaPattern = cfa;
            crop = cropRect == null ? new float[]{0,0,1,1} : cropRect.clone();
            whiteLevel = white;
            java.util.Arrays.fill(blackLevel,0);
            java.util.Arrays.fill(wbGains,1);
            java.util.Arrays.fill(colorTransform,0);colorTransform[0]=colorTransform[4]=colorTransform[8]=1;
            if (black != null && black.length >= 4) System.arraycopy(black, 0, blackLevel, 0, 4);
            if (gains != null && gains.length >= 3) System.arraycopy(gains, 0, wbGains, 0, 3);
            if (ccm != null && ccm.length >= 9) System.arraycopy(ccm, 0, colorTransform, 0, 9);
            shading=lensShading==null?new float[]{1,1,1}:lensShading.clone();
            shadingWidth=lensShading==null?1:sw;shadingHeight=lensShading==null?1:sh;
            version++;
        }
    }

    /**
     * Snapshot for the GL thread; null when nothing has been published. P314: a new frame changes places with the GL thread's
     * last one (no copy); without a new frame it is the last one again (the same buffer). Its buffer stays the frame's until the
     * next acquire.
     */
    public static Frame acquire() {
        synchronized (LOCK) {
            if (readyFresh) {
                final ByteBuffer last = consumer;
                consumer = ready;
                ready = last;
                readyFresh = false;
            }
            if (consumer == null) return null;
            Frame f = new Frame();
            f.buffer = consumer.duplicate().order(ByteOrder.nativeOrder());
            f.buffer.position(0);
            f.crop = crop.clone();
            f.width = width;
            f.height = height;
            f.rowStride = rowStride;
            f.cfaPattern = cfaPattern;
            f.cfaBlock = cfaBlock;
            f.cellMean = cellMean;
            f.iso = iso; f.maxAnalogIso = maxAnalogIso; f.autoExposure = autoExposure;
            f.minDisplayGain = minDisplayGain; f.maxDisplayGain = maxDisplayGain; f.sceneLuma = sceneLuma;
            f.noiseS=noiseS;f.noiseO=noiseO;
            f.whiteLevel = whiteLevel;
            f.blackLevel = blackLevel.clone();
            f.wbGains = wbGains.clone();
            f.colorTransform = colorTransform.clone();
            f.shading=shading;f.shadingWidth=shadingWidth;f.shadingHeight=shadingHeight;
            f.version = version;
            f.session = session;
            f.publishedNanos = publishedNanos;
            return f;
        }
    }

    /**
     * P36: the session of the frame {@link #acquire()} would return, -1 without one. The frame is always from the
     * current session (a session change drops the published frame).
     */
    public static int peekSession() {
        synchronized (LOCK) {
            return readyFresh || consumer != null ? session : -1;
        }
    }

    /** P36: the current session number (a frame published from now on carries a higher one after the next change). */
    public static int currentSession() {
        synchronized (LOCK) {
            return session;
        }
    }

    public static int getVersion() {
        synchronized (LOCK) {
            return version;
        }
    }

    public static final class Frame {
        public ByteBuffer buffer;
        public int width, height, rowStride, cfaPattern, version, session;
        public int cfaBlock=1, iso=100, maxAnalogIso=6400;
        public boolean autoExposure=true;
        /**
         * P314: the sites are the cell means of a Quad / Tetra frame (block 1 here): drawn one colour per 2 x 2 of them (cfa_mode 0),
         * as the mosaic itself, not as a plain Bayer frame.
         */
        public boolean cellMean;
        /** P212h (A6): the range of an automatic display gain (LiveRawMeter) and whether the meter is SceneLuma's. */
        public float minDisplayGain=0f, maxDisplayGain=Float.POSITIVE_INFINITY;
        public boolean sceneLuma=true;
        public double noiseS,noiseO;
        public long publishedNanos;
        public int shadingWidth,shadingHeight;
        public float[] shading, crop;
        public float whiteLevel;
        public float[] blackLevel, wbGains, colorTransform;
    }
}

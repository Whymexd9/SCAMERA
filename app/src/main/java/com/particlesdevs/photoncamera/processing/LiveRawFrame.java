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
 * an older one is worthless the moment a newer arrives. The buffer is reused
 * across frames, so a 12.6 MP RAW costs one 25 MB allocation for the session
 * rather than one per frame.
 *
 * <p>Held statically because the producer (CaptureController's image callback)
 * and the consumer (the GL renderer) have no other connection, the same
 * arrangement {@link PreviewLook} uses.
 */
public final class LiveRawFrame {

    private static final Object LOCK = new Object();

    /**
     * Two buffers, not one. The capture thread writes while the GL thread is
     * uploading the previous frame, and sharing a single buffer let the upload
     * read bytes from two different exposures - which shows up as the
     * viewfinder flickering between colours rather than as a clean error.
     */
    private static ByteBuffer front;
    private static ByteBuffer back;
    // Owned exclusively by the single GL consumer, never reused by the producer.
    private static ByteBuffer consumer;
    private static int consumerVersion = -1;
    private static int width, height, rowStride;
    private static float[] crop = {0,0,1,1};
    private static int cfaPattern, cfaBlock=1, iso=100, maxAnalogIso=6400;
    private static boolean autoExposure=true;
    private static double noiseS,noiseO;
    private static float whiteLevel = 1023.0f;
    private static final float[] blackLevel = new float[] {0, 0, 0, 0};
    private static final float[] wbGains = new float[] {1, 1, 1};
    private static final float[] colorTransform = new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
    /** P108: frames this large with a 2x2 mosaic are binned for the viewfinder (a 50 MP Quad-Bayer full-resolution stream). */
    static final long QUAD_BIN_PIXELS = 30_000_000L;
    private static final long QUAD_BIN_INTERVAL_NS = 150_000_000L;
    private static long quadAtNs;
    private static ByteBuffer quadFull, quadBinned;

    /**
     * P108: the Quad-Bayer frame's 2x2 same-colour cells averaged into one Bayer site each ((w/2) x (h/2) uint16, native order),
     * from a packed RAW10 / RAW12 (unpacked first, natively) or a plain 16-bit frame; null when the geometry does not fit.
     */
    static synchronized ByteBuffer binQuad(ByteBuffer plane, int w, int h, int stride, int format) {
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
        final int bw = w / 2, bh = h / 2;
        if (quadBinned == null || quadBinned.capacity() < bw * bh * 2) quadBinned = ByteBuffer.allocateDirect(bw * bh * 2).order(ByteOrder.nativeOrder());
        java.nio.ShortBuffer out = quadBinned.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer();
        for (int y = 0; y < bh; y++) {
            final int r0 = 2 * y * srcRow, r1 = r0 + srcRow, o = y * bw;
            for (int x = 0; x < bw; x++) {
                final int c = 2 * x;
                int s = (src.get(r0 + c) & 0xFFFF) + (src.get(r0 + c + 1) & 0xFFFF) + (src.get(r1 + c) & 0xFFFF) + (src.get(r1 + c + 1) & 0xFFFF);
                out.put(o + x, (short) ((s + 2) >> 2));
            }
        }
        ByteBuffer result = quadBinned.duplicate().order(ByteOrder.nativeOrder());
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
                front = back = consumer = null;
                consumerVersion = -1;
                version++;
            }
        }
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
        if (!enabled || plane == null) return;
        // P108: a full-resolution Quad-Bayer frame (Pixel 10 Pro: 8160x6144 RAW10, the ISP's own viewfinder is green in that
        // mode) is shown 2x2-binned as the plain Bayer 4080x3072, at most every QUAD_BIN_INTERVAL_NS (the camera thread)
        if (block == 2 && (long) w * h >= QUAD_BIN_PIXELS && w % 4 == 0 && h % 4 == 0) {
            final long now = System.nanoTime();
            if (now - quadAtNs < QUAD_BIN_INTERVAL_NS) return;
            quadAtNs = now;
            ByteBuffer binned = binQuad(plane, w, h, stride, format);
            if (binned == null) return;
            publish(binned, w / 2, h / 2, w, cfa, white, black, gains, ccm, lensShading, sw, sh, cropRect, 1, sensorIso, analogIso,
                    automatic, shotNoise, readNoise, android.graphics.ImageFormat.RAW_SENSOR);
            return;
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
            if (back == null || back.capacity() < needed) {
                back = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder());
            }
            target = back;
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
        synchronized (LOCK) {
            if (!enabled || session != generation) return;
            publishedNanos = System.nanoTime();
            back = front;
            front = target;
            width = w;
            height = h;
            rowStride = w*2;
            cfaBlock = block==2 || block==4 ? block : 1;
            iso = sensorIso==null ? 100 : Math.max(1,sensorIso);
            maxAnalogIso = analogIso==null ? 6400 : Math.max(101,analogIso);
            autoExposure = automatic;
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

    /** Snapshot for the GL thread; null when nothing has been published. */
    public static Frame acquire() {
        synchronized (LOCK) {
            if (front == null) return null;
            Frame f = new Frame();
            if(consumer == null || consumer.capacity() < front.limit()) {
                consumer = ByteBuffer.allocateDirect(front.limit()).order(ByteOrder.nativeOrder());
                consumerVersion = -1;
            }
            if(consumerVersion != version){consumer.clear();consumer.put(front.duplicate());consumer.flip();consumerVersion=version;}
            f.buffer = consumer.duplicate().order(ByteOrder.nativeOrder());
            f.buffer.position(0);
            f.crop = crop.clone();
            f.width = width;
            f.height = height;
            f.rowStride = rowStride;
            f.cfaPattern = cfaPattern;
            f.cfaBlock = cfaBlock;
            f.iso = iso; f.maxAnalogIso = maxAnalogIso; f.autoExposure = autoExposure;
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
            return front == null ? -1 : session;
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
        public double noiseS,noiseO;
        public long publishedNanos;
        public int shadingWidth,shadingHeight;
        public float[] shading, crop;
        public float whiteLevel;
        public float[] blackLevel, wbGains, colorTransform;
    }
}

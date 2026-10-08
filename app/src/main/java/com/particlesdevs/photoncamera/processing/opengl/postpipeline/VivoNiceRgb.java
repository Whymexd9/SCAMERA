package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.*;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.BufferUtils;
import com.particlesdevs.photoncamera.util.ParallelWork;
import static android.opengl.GLES20.*;

/**
 * Import reconstructed sensor RGB; apply WB and normalized lens shading once. On an LMC hybrid shot it also runs the
 * per-channel highlight recovery (research/hybrid5/highlights.md): clip levels per physical channel before WB, a clipped
 * green of a white highlight rebuilt from R and B, all channels clipped -> neutral white at the brightest level, a
 * saturated single colour left alone. Hybrid values stay signed ({@link #signedInput}).
 */
public final class VivoNiceRgb extends Node {
    public VivoNiceRgb(){super("","VivoNiceRgb");}
    @Override public void Compile(){}

    /**
     * Per-pixel clip flags of the last hybrid merge, or null: uint8 per OUTPUT pixel (the worker's clipFlags trailer,
     * vivo-nice-hybrid.h): bits 0-2 = R/G/B from the clipped mean, 3 = a clipped sample was excluded nearby, 4 = inside
     * the Bento mask, 5 = the clipped mean holds the ultrashort frame. Set by the client that reads the worker result
     * (null at the start of every merge), consumed and cleared by the next hybrid post-processing. Optional: without it
     * the clip level comes from the values alone (Bento shots: only the ultrashort's clip k is recovered).
     */
    public static volatile java.nio.ByteBuffer lastClipFlags;
    /**
     * White-balanced level (same units as this node's output) of the clipped white of the last hybrid shot: where every
     * channel clipped the recovery outputs a neutral white at about this level (Bento: k times the base white). 0 when
     * nothing could clip. For the tone stages that place the brightest data at white.
     */
    public static volatile float lastClipWhite;
    /** The shot's RGB has real clipped highlights (a clip plateau of the base or Bento frames): the ARK tone may use the
     *  Bento ceiling. False when the data only exceeds 1 by the white balance of unclipped saturated colour. */
    public static volatile boolean lastRealClip;

    /**
     * Per-channel level where the NICE output saturates (the plateau of blown
     * windows/sky). Channels clip at different scene levels, so after white
     * balance a clipped highlight turned pink; the shader neutralises it near
     * this level. No plateau (nothing clipped): effectively disabled.
     * SCAM HDR (NICE network) route only; the hybrid uses {@link #channelClip}.
     */
    private static float[] clipLevels(java.nio.ByteBuffer rgb){
        java.nio.FloatBuffer f=rgb.duplicate().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        int n=f.limit()/3;float[] max=new float[3];
        for(int i=0;i<n;i+=17)for(int c=0;c<3;c++)max[c]=Math.max(max[c],f.get(i*3+c));
        int near=0,total=0;
        for(int i=0;i<n;i+=17){total++;
            for(int c=0;c<3;c++)if(max[c]>0&&f.get(i*3+c)>=0.97f*max[c]){near++;break;}}
        boolean plateau=total>0&&near>=total/2000;
        com.particlesdevs.photoncamera.util.Log.i("NICE_PIPELINE","highlight clip levels="+max[0]+","+max[1]+","+max[2]
                +" plateau="+plateau+" ("+near+"/"+total+")");
        if(!plateau)return new float[]{1e30f,1e30f,1e30f};
        return max;
    }

    /** Clip levels of the hybrid merge per camera channel, raw units (base-frame white = 1, before WB and lens shading). */
    static final class ChannelClip {
        /** Base frames' clip (about 1.0) and the Bento ultrashort's clip (about k); all <= 0: that level is not used. */
        final float[] lo = new float[3], hi = new float[3];
        /** Sampled maximum per channel. */
        final float[] max = new float[3];
        /** Some sample reaches 85 % of a used level: the local chroma statistics are worth their pass. */
        boolean nearClip;
        /**
         * P58: the ultrashort clipped below the worker's flag threshold (a measured hi plateau under 0.975 k with no
         * ultrashort clip flagged): inside the Bento mask a pixel without flags may still sit at the hi clip (clipHiUnflaggedU).
         */
        boolean hiUnflagged;
        String log = "";
        boolean enabled() { return lo[1] > 0f || hi[1] > 0f; }
    }

    static final float HIST_T0 = 0.80f, HIST_BIN = 0.002f;
    static final int HIST_BINS = 150; // [0.80, 1.10) of the expected white
    private static final int PLATEAU_WINDOW = 5;

    /**
     * Centre of the clip plateau in units of the expected white (1.0 for the base frames, k for the Bento ultrashort):
     * clipped means pile up in a narrow spike just below that white, while real content thins out towards it. The
     * spike is the densest 1 % window in [searchLo, searchHi) and must stand well above the density of [0.80, 0.90);
     * its position is the mean of the samples inside it ({@code sum}: per-bin sums of the values, same units).
     * NaN: no plateau.
     */
    static float plateau(int[] h, double[] sum, long samples, float searchLo, float searchHi) {
        final int bg1 = Math.round((0.90f - HIST_T0) / HIST_BIN);
        long bg = 0;
        for (int b = 0; b < bg1; b++) bg += h[b];
        final double bgPerBin = bg / (double) Math.max(1, bg1);
        final int w = PLATEAU_WINDOW;
        final int s0 = Math.max(bg1, Math.round((searchLo - HIST_T0) / HIST_BIN));
        final int s1 = Math.min(HIST_BINS, Math.round((searchHi - HIST_T0) / HIST_BIN)) - w;
        int best = -1;
        long bestSum = 0;
        for (int b = s0; b <= s1; b++) {
            long s = 0;
            for (int j = 0; j < w; j++) s += h[b + j];
            if (s > bestSum) { bestSum = s; best = b; }
        }
        final double expected = w * bgPerBin;
        final long need = Math.max(16L, (long) Math.ceil(samples * 5e-6));
        if (best < 0 || bestSum < need || bestSum < 6.0 * expected + 4.0 * Math.sqrt(expected + 1.0)) return Float.NaN;
        double m = 0;
        for (int j = 0; j < w; j++) m += sum[best + j];
        return (float) (m / bestSum);
    }

    private static void histogram(int[] h, double[] sum, float t) {
        final int b = (int) Math.floor((t - HIST_T0) / HIST_BIN);
        if (b >= 0 && b < HIST_BINS) { h[b]++; sum[b] += t; }
    }

    /**
     * Per-channel clip levels of the hybrid merge. The nominal white of every channel is 1.0 after the worker's per-site
     * black/white normalisation (base frames) and k where the Bento ultrashort itself clipped; a histogram plateau near
     * either level confirms it and gives its exact position (a channel that saturates below the white level, a factor
     * that differs from the plan). Without the worker's clip flags a Bento shot uses only k: real ultrashort content
     * passes smoothly through 1.0 and must not be read as clipped there.
     */
    static ChannelClip channelClip(java.nio.ByteBuffer rgb, boolean bento, float k, float usClipped, boolean flags) {
        return channelClip(rgb, bento, k, usClipped, flags, ParallelWork.threads());
    }

    /** One part of the clip statistics: samples [s0, s1) of the every-17th-pixel walk. */
    private static final class ClipPart {
        final int[][] hLo = new int[3][HIST_BINS], hHi = new int[3][HIST_BINS];
        final double[][] sLo = new double[3][HIST_BINS], sHi = new double[3][HIST_BINS];
        final float[] max = new float[3];
    }

    /**
     * P48 (research/speed/PLAIN_SHOT_SPEED.md): the statistics in {@code parts} parts on {@link ParallelWork} (482 ms on one
     * core for the 50 MP Sabre 2x grid). The same result for any split: counts are integers, the maxima are maxima, and every
     * histogram sum is exact in double (its values lie in [0.8, 1.1), multiples of 2^-24: below 2^28 samples the sum needs
     * fewer than 53 bits), so the order of the additions does not matter. parts 1 = the single walk as before.
     */
    static ChannelClip channelClip(java.nio.ByteBuffer rgb, boolean bento, float k, float usClipped, boolean flags, int parts) {
        ChannelClip cc = new ChannelClip();
        final java.nio.ByteBuffer view = rgb.duplicate().order(java.nio.ByteOrder.nativeOrder());
        final int n = view.asFloatBuffer().limit() / 3;
        final float inv = bento && k > 1f ? 1f / k : 0f;
        final long samples = (n + 16L) / 17L;
        final int chunks = parts <= 1 || samples < 65536 ? 1 : 4 * parts;
        final ClipPart[] part = new ClipPart[chunks];
        final java.util.function.IntConsumer walk = c -> {
            final ClipPart p = new ClipPart();
            final java.nio.FloatBuffer f = view.duplicate().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
            final long s1 = samples * (c + 1) / chunks;
            for (long s = samples * c / chunks; s < s1; s++) {
                final int i = (int) (s * 17L);
                for (int ch = 0; ch < 3; ch++) {
                    final float v = f.get(i * 3 + ch);
                    if (v > p.max[ch]) p.max[ch] = v;
                    histogram(p.hLo[ch], p.sLo[ch], v);
                    if (inv > 0f) histogram(p.hHi[ch], p.sHi[ch], v * inv);
                }
            }
            part[c] = p;
        };
        if (chunks == 1) walk.accept(0); else ParallelWork.forEach(chunks, walk);
        final int[][] hLo = new int[3][HIST_BINS], hHi = new int[3][HIST_BINS];
        final double[][] sLo = new double[3][HIST_BINS], sHi = new double[3][HIST_BINS];
        for (ClipPart p : part)
            for (int c = 0; c < 3; c++) {
                if (p.max[c] > cc.max[c]) cc.max[c] = p.max[c];
                for (int b = 0; b < HIST_BINS; b++) {
                    hLo[c][b] += p.hLo[c][b]; sLo[c][b] += p.sLo[c][b];
                    hHi[c][b] += p.hHi[c][b]; sHi[c][b] += p.sHi[c][b];
                }
            }
        final boolean useLo = !bento || flags;
        final boolean useHi = bento && k > 1f;
        StringBuilder logLo = new StringBuilder(), logHi = new StringBuilder();
        final boolean[] hiMeasured = new boolean[3];
        float hiPlateauMax = 0f, hiPlateauMinFrac = Float.MAX_VALUE;
        for (int c = 0; c < 3; c++) {
            float lo = 0f, hi = 0f;
            if (useLo) {
                final float s = plateau(hLo[c], sLo[c], samples, 0.92f, 1.03f);
                lo = Float.isNaN(s) ? 1f : s;
                logLo.append(c == 0 ? "lo=" : ",").append(lo).append(Float.isNaN(s) ? "(nominal)" : "(plateau)");
            }
            if (useHi) {
                final float s = plateau(hHi[c], sHi[c], samples, 0.90f, 1.08f);
                // No plateau and the ultrashort never clipped: nothing in the data reaches k, the level is unused.
                hi = !Float.isNaN(s) ? s * k : (usClipped > 0f ? k : 0f);
                if (!Float.isNaN(s)) { hiMeasured[c] = true; hiPlateauMax = Math.max(hiPlateauMax, hi); hiPlateauMinFrac = Math.min(hiPlateauMinFrac, s); }
                logHi.append(c == 0 ? "hi=" : ",").append(hi)
                        .append(!Float.isNaN(s) ? "(plateau)" : hi > 0f ? "(nominal)" : "(off)");
            }
            cc.lo[c] = lo;
            cc.hi[c] = hi;
        }
        final String log = (logLo.length() > 0 && logHi.length() > 0 ? logLo + " " + logHi : logLo.toString() + logHi);
        // A level is used for all three channels or for none (the shader divides by every component).
        if (cc.hi[1] <= 0f) java.util.Arrays.fill(cc.hi, 0f);
        else for (int c = 0; c < 3; c++) if (cc.hi[c] <= 0f) {
            // P58: a channel without a plateau next to measured ones (OnePlus 15: R, G at 0.915 k, B "off") takes its own
            // sampled maximum, never below the measured clips and never above k: k (as before) left the blue of a blown
            // sky below its level, so R and G were recovered and B was not -> pink sky with white blotches.
            cc.hi[c] = hiPlateauMax > 0f ? Math.min(k, Math.max(hiPlateauMax, cc.max[c])) : k;
        }
        cc.hiUnflagged = cc.hi[1] > 0f && usClipped <= 0f && hiPlateauMinFrac < 0.975f;
        for (int c = 0; c < 3; c++)
            if ((cc.lo[1] > 0f && cc.max[c] >= 0.85f * cc.lo[c]) || (cc.hi[1] > 0f && cc.max[c] >= 0.85f * cc.hi[c])) cc.nearClip = true;
        cc.log = log + (cc.hiUnflagged ? " hiUnflagged" : "") + " max=" + cc.max[0] + "," + cc.max[1] + "," + cc.max[2] + " samples=" + samples;
        return cc;
    }

    /**
     * LMC hybrid: the worker's signed RGB passes through this node unclamped (nicergb signedU). The merge keeps its noise
     * signed and clips once; a per-pixel max(0) here lifted the mean of every channel near zero by up to half its noise
     * (x1.5 red along the frame edge of a dark teal curtain where fewer donors overlap, OPPO X8U 2026-10-07), and the ARK
     * tone turned that into a red band and red mottling. The stages after it clamp only after the noise reduction or an
     * average. The SCAM HDR route keeps the clamp. nice_dev.txt "post_ab_clamp 1": the old run of post_ab (PostAb) gets the
     * former per-pixel clamp, so the A/B shows this change on the same worker result.
     */
    static boolean signedInput(boolean hybrid) {
        return hybrid && !(PostGlMode.legacy() && PreferenceKeys.niceDevSwitch("post_ab_clamp", false));
    }

    /** Every `stride`-th pixel of the linear RGB: enough for the percentile statistics of the later nodes. */
    private static java.nio.ByteBuffer decimate(java.nio.ByteBuffer rgb,int stride){
        java.nio.FloatBuffer f=rgb.duplicate().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        int n=f.limit()/3,m=(n+stride-1)/stride;
        java.nio.ByteBuffer out=java.nio.ByteBuffer.allocateDirect(m*12).order(java.nio.ByteOrder.nativeOrder());
        java.nio.FloatBuffer o=out.asFloatBuffer();
        for(int i=0,k=0;i<n;i+=stride,k++){o.put(k*3,f.get(i*3));o.put(k*3+1,f.get(i*3+1));o.put(k*3+2,f.get(i*3+2));}
        return out;
    }
    private static final ThreadLocal<int[][]> REPACK_ROW = ThreadLocal.withInitial(() -> new int[][]{new int[0], new int[0]});

    /**
     * P48: rows [y0, y0 + rows) of the w-wide RGB float32 buffer as RGBA (alpha 1.0) into dst from its start: the same bits
     * (copied as raw ints), the rows on {@link ParallelWork}.
     */
    static void rgbToRgbaRows(java.nio.ByteBuffer rgb, int w, int y0, int rows, java.nio.ByteBuffer dst) {
        final java.nio.ByteBuffer src = rgb.duplicate().order(java.nio.ByteOrder.nativeOrder());
        final java.nio.ByteBuffer out = dst.duplicate().order(java.nio.ByteOrder.nativeOrder());
        out.position(0);
        final int one = Float.floatToRawIntBits(1f);
        ParallelWork.forEach(rows, r -> {
            int[][] t = REPACK_ROW.get();
            if (t[0].length < 3 * w) { t[0] = new int[3 * w]; t[1] = new int[4 * w]; }
            final int[] a3 = t[0], a4 = t[1];
            final java.nio.IntBuffer s = src.asIntBuffer();
            s.position((y0 + r) * 3 * w);
            s.get(a3, 0, 3 * w);
            for (int x = 0, i = 0, o = 0; x < w; x++, i += 3, o += 4) {
                a4[o] = a3[i]; a4[o + 1] = a3[i + 1]; a4[o + 2] = a3[i + 2]; a4[o + 3] = one;
            }
            final java.nio.IntBuffer d = out.asIntBuffer();
            d.position(r * 4 * w);
            d.put(a4, 0, 4 * w);
        });
    }

    /**
     * P48: the RGB result uploaded as RGBA32F in row bands of ~32 MB (alpha 1.0), the next band repacked while the driver takes
     * the current one. On Adreno the RGB32F upload is a CPU repack in the driver: 2240 ms for the 50 MP Sabre 2x grid on the
     * vivo X200 Ultra (Adreno 830), 230-258 ms at 12 MP on the Find X7 Ultra. nicergb and chanprep read .rgb with texelFetch, so
     * the texture gives the same floats. Default on Adreno; nice_dev.txt "rgba_upload 0 / 1" overrides. Null when the staging
     * memory is not there (the caller uploads RGB32F as before).
     */
    static GLTexture uploadRgba(Point size, java.nio.ByteBuffer rgb) {
        final int w = size.x, h = size.y;
        final int band = Math.max(1, Math.min(h, (32 << 20) / (16 * w)));
        final java.nio.ByteBuffer[] stage = new java.nio.ByteBuffer[2];
        stage[0] = com.particlesdevs.photoncamera.util.Allocator.allocate(band * w * 16);
        stage[1] = stage[0] == null ? null : com.particlesdevs.photoncamera.util.Allocator.allocate(band * w * 16);
        if (stage[1] == null) {
            if (stage[0] != null) com.particlesdevs.photoncamera.util.Allocator.free(stage[0]);
            return null;
        }
        final java.util.concurrent.ExecutorService repack = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "SCAMERA-rgba-repack");
            t.setDaemon(true);
            return t;
        });
        java.util.concurrent.Future<?> next = null;
        GLTexture tex = null;
        try {
            tex = new GLTexture(size, new GLFormat(GLFormat.DataType.FLOAT_32, 4), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
            next = repack.submit(() -> rgbToRgbaRows(rgb, w, 0, Math.min(band, h), stage[0]));
            for (int y0 = 0, k = 0; y0 < h; y0 += band, k++) {
                next.get();
                next = null;
                final int rows = Math.min(band, h - y0), y1 = y0 + band, k1 = k + 1;
                if (y1 < h) next = repack.submit(() -> rgbToRgbaRows(rgb, w, y1, Math.min(band, h - y1), stage[k1 & 1]));
                glBindTexture(GL_TEXTURE_2D, tex.mTextureID);
                stage[k & 1].position(0);
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, y0, w, rows, GL_RGBA, GL_FLOAT, stage[k & 1]);
            }
            return tex;
        } catch (java.util.concurrent.ExecutionException e) {
            if (tex != null) tex.close();
            final Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(cause);
        } catch (InterruptedException e) {
            if (tex != null) tex.close();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while uploading the RGB", e);
        } finally {
            if (next != null) try { next.get(); } catch (Exception ignored) {} // never free a buffer the repack still writes
            repack.shutdown();
            com.particlesdevs.photoncamera.util.Allocator.free(stage[0]);
            com.particlesdevs.photoncamera.util.Allocator.free(stage[1]);
        }
    }

    /** P48: whether the RGB goes up as RGBA32F bands ({@link #uploadRgba}): nice_dev.txt "rgba_upload", else on Adreno. */
    static boolean rgbaUploadWanted(String renderer) {
        // post_ab's old run is the pipeline before the speed work: the RGB32F upload; "post_ab_upload" runs the old run in the
        // new model with only this upload changed (PostAb.forceRgbUpload)
        if (com.particlesdevs.photoncamera.processing.opengl.PostGlMode.legacy() || PostAb.forceRgbUpload) return false;
        if (PreferenceKeys.niceDevOverrides("rgba_upload")) return PreferenceKeys.niceDevSwitch("rgba_upload", false);
        return renderer != null && renderer.contains("Adreno");
    }

    /** P48: what the helper thread computes from the big RGB while the GL thread uploads it (both only read it). */
    private static final class InputStats {
        ChannelClip cc;
        float[] clip;
        java.nio.ByteBuffer decimated;
        long statMs, decimateMs;
    }

    /** The helper's result; its exception is rethrown here. */
    private static <T> T await(java.util.concurrent.FutureTask<T> task) {
        boolean interrupted = false;
        try {
            for (;;) {
                try {
                    return task.get();
                } catch (InterruptedException e) {
                    interrupted = true; // the helper reads the buffer this thread frees next: wait for it in any case
                }
            }
        } catch (java.util.concurrent.ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(cause);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void awaitQuietly(java.util.concurrent.FutureTask<?> task) {
        try { await(task); } catch (RuntimeException | Error ignored) {}
    }

    @Override public void Run(){
        PostPipeline p=(PostPipeline)basePipeline;
        // Bento is applied only by the hybrid merge (reset for every NICE burst): a safety net for the shot profile.
        final boolean hybrid=PreferenceKeys.isHybridShot()||LmcHybridBurst.lastBentoApplied;
        final Point size=p.mParameters.rawSize;
        java.nio.ByteBuffer flags=lastClipFlags;lastClipFlags=null;
        if(flags!=null&&(!hybrid||flags.capacity()!=(long)size.x*size.y)){
            com.particlesdevs.photoncamera.util.Log.i("NICE_PIPELINE","highlight recovery: clip flags ignored (hybrid="+hybrid
                    +" bytes="+flags.capacity()+" expected="+(long)size.x*size.y+")");
            flags=null;
        }
        // P48: the clip statistics and the decimated copy on a helper thread while this thread uploads the big buffer (both
        // only read it); the big buffer is released once all three are done (2x grid: 604 MB). The same results as one after
        // the other (channelClip parallel: see there).
        float[] clip=null;ChannelClip cc=null;float strength=0f;
        final java.nio.ByteBuffer big=p.mParameters.vivoNiceRgb;
        final boolean owned=p.mParameters.vivoNiceRgbOwned;
        final boolean bento=LmcHybridBurst.lastBentoApplied;
        final float bentoK=LmcHybridBurst.lastBentoFactor,bentoUs=LmcHybridBurst.lastBentoUsClipped;
        final boolean withFlags=flags!=null;
        if(hybrid)strength=Math.max(0f,Math.min(1f,PreferenceKeys.hybridValue("highlight_recovery",100f)/100f));
        final java.util.concurrent.FutureTask<InputStats> side=new java.util.concurrent.FutureTask<>(()->{
            InputStats s=new InputStats();
            final long t0=System.currentTimeMillis();
            if(hybrid)s.cc=channelClip(big,bento,bentoK,bentoUs,withFlags); else s.clip=clipLevels(big);
            final long t1=System.currentTimeMillis();
            if(owned)s.decimated=decimate(big,61);
            s.statMs=t1-t0;s.decimateMs=System.currentTimeMillis()-t1;
            return s;
        });
        final Thread sideThread=new Thread(side,"SCAMERA-nicergb-stats");
        sideThread.setDaemon(true);
        sideThread.start();
        final long uploadStart=System.nanoTime();
        GLTexture input=null;
        boolean rgbaBands=false;
        try{
            if(rgbaUploadWanted(glGetString(GL_RENDERER))){input=uploadRgba(size,big);rgbaBands=input!=null;}
            if(input==null)input=new GLTexture(size,new GLFormat(GLFormat.DataType.FLOAT_32,3),big,GL_NEAREST,GL_CLAMP_TO_EDGE);
        }finally{
            if(input==null)awaitQuietly(side); // never release the buffer under the helper thread (an exception on its way out)
        }
        final long uploadDone=System.nanoTime();
        final InputStats stats=await(side);
        final long sideDone=System.nanoTime();
        if(hybrid){
            cc=stats.cc;
            final float[] top=cc.hi[1]>0f?cc.hi:cc.lo;
            final float[] wp=p.mParameters.whitePoint;
            float white=0f;
            if(top[1]>0f)for(int c=0;c<3;c++)white=Math.max(white,top[c]/Math.max(wp[c],1e-6f));
            lastClipWhite=white;
            lastRealClip=cc.enabled();
            com.particlesdevs.photoncamera.util.Log.i("NICE_PIPELINE","highlight recovery per channel: strength="+strength
                    +" bento="+bento+" k="+bentoK+" usClipped="+bentoUs
                    +" flags="+withFlags+" "+cc.log+" nearClip="+cc.nearClip+" clipWhite="+white
                    +" ms="+stats.statMs);
        } else {
            lastClipWhite=0f;
            clip=stats.clip;
            lastRealClip=clip[1]<1e29f;
        }
        final boolean perChannel=cc!=null&&strength>0f&&cc.enabled();
        if(owned){
            p.mParameters.vivoNiceRgb=stats.decimated;
            p.mParameters.vivoNiceRgbOwned=false;
            com.particlesdevs.photoncamera.util.Allocator.free(big);
        }
        // P33 W2.4 / P48: where the time between the worker result and the lens shading goes (stats and decimate on the helper
        // thread, alongside the upload; wait = what the upload left of them)
        com.particlesdevs.photoncamera.util.Log.i("NICE_PIPELINE","nicergb input ms: upload="+(uploadDone-uploadStart)/1000000
                +(rgbaBands?" (rgba bands)":" (rgb)")+" stats="+stats.statMs+" decimate="+stats.decimateMs
                +" wait="+(sideDone-uploadDone)/1000000+" free="+(System.nanoTime()-sideDone)/1000000);
        GLTexture flagsTex=null,chromaNear=null,chromaWide=null,chromaHuge=null;
        try {
            float[] gm=p.mParameters.gainMap;
            if(gm!=null&&p.mParameters.mapSize!=null&&gm.length>=4*p.mParameters.mapSize.x*p.mParameters.mapSize.y){
                int mw=p.mParameters.mapSize.x,mh=p.mParameters.mapSize.y;
                java.util.function.BiFunction<Integer,Integer,Float> g=(x,y)->{int i=4*(y*mw+x);return (gm[i]+gm[i+1]+gm[i+2]+gm[i+3])/4f;};
                float mean=0;for(int y=0;y<mh;y++)for(int x=0;x<mw;x++)mean+=g.apply(x,y);mean/=mw*mh;
                com.particlesdevs.photoncamera.util.Log.i("NICE_PIPELINE","lens shading map "+mw+"x"+mh+": centre="+g.apply(mw/2,mh/2)
                        +" corner="+g.apply(0,0)+" edge-mid="+g.apply(mw/2,0)+" mean="+mean);
            }
            p.GainMap=new GLTexture(p.mParameters.mapSize,new GLFormat(GLFormat.DataType.FLOAT_16,4),
                    BufferUtils.getFrom(p.mParameters.gainMap),GL_LINEAR,GL_CLAMP_TO_EDGE);
            p.main1=new GLTexture(size,new GLFormat(GLFormat.DataType.FLOAT_16,GLDrawParams.WorkDim),null,GL_LINEAR,GL_CLAMP_TO_EDGE);
            p.main2=new GLTexture(size,new GLFormat(GLFormat.DataType.FLOAT_16,GLDrawParams.WorkDim),null,GL_LINEAR,GL_CLAMP_TO_EDGE);
            p.main3=new GLTexture(size,new GLFormat(GLFormat.DataType.FLOAT_16,GLDrawParams.WorkDim),null,GL_LINEAR,GL_CLAMP_TO_EDGE);
            int ox=0,oy=0;
            if(com.particlesdevs.photoncamera.app.PhotonCamera.getSettings().aspect169){
                int w=size.x,h=size.y;
                if(w>h)oy=2*((h-w*9/16)/4);else ox=2*((w-h*9/16)/4);
            }
            // Sabre 2x grid: the local chroma blocks keep their SENSOR size (8 x 8), sampled one sensor pixel apart.
            final int s=Math.max(1,Math.round(p.mParameters.outputScale));
            final int block=8*s;
            final float chromaLimit=PreferenceKeys.hybridValue("highlight_chroma",0.35f);
            final long gpuStart=System.currentTimeMillis();
            if(perChannel){
                if(flags!=null){
                    flags.rewind();
                    android.opengl.GLES30.glPixelStorei(android.opengl.GLES30.GL_UNPACK_ALIGNMENT,1);
                    // Normalised R8 (SIMPLE_8): the shaders read it through a float sampler2D (code = r * 255). UNSIGNED_8
                    // is R8UI, which GL_RED cannot upload and a sampler2D cannot sample (an unset usampler2D would also
                    // share unit 0 with the float samplers of the same program).
                    flagsTex=new GLTexture(size,new GLFormat(GLFormat.DataType.SIMPLE_8,1),flags,GL_NEAREST,GL_CLAMP_TO_EDGE);
                }
                if(cc.nearClip){
                    GLFormat rgba=new GLFormat(GLFormat.DataType.FLOAT_16,4);
                    Point near=new Point((size.x+block-1)/block,(size.y+block-1)/block);
                    chromaNear=new GLTexture(near,rgba,null,GL_LINEAR,GL_CLAMP_TO_EDGE);
                    Point wide=new Point((near.x+3)/4,(near.y+3)/4);
                    chromaWide=new GLTexture(wide,rgba,null,GL_LINEAR,GL_CLAMP_TO_EDGE);
                    chromaHuge=new GLTexture(new Point((wide.x+3)/4,(wide.y+3)/4),rgba,null,GL_LINEAR,GL_CLAMP_TO_EDGE);
                    glProg.useAssetProgram("hlrecovery/chanprep",false);
                    glProg.setTexture("InputBuffer",input);glProg.setTexture("GainMap",p.GainMap);
                    if(flagsTex!=null)glProg.setTexture("ClipFlags",flagsTex);
                    glProg.setVar("whitePoint",p.mParameters.whitePoint);
                    glProg.setVar("inverseSize",1f/size.x,1f/size.y);
                    glProg.setVar("cropOffset",ox,oy);
                    glProg.setVar("clipLoU",cc.lo);glProg.setVar("clipHiU",cc.hi);
                    glProg.setVar("clipHiUnflaggedU",cc.hiUnflagged?1:0);
                    glProg.setVar("clipFlagsU",flagsTex!=null?1:0);
                    glProg.setVar("blockU",block);glProg.setVar("pxStepU",s);
                    glProg.drawBlocks(chromaNear);
                    // 32- and 128-blocks: the inside of a large clipped area still finds the colour of its light
                    glProg.useAssetProgram("hlrecovery/reduce",false);
                    glProg.setTexture("InputBuffer",chromaNear);
                    glProg.drawBlocks(chromaWide);
                    glProg.useAssetProgram("hlrecovery/reduce",false);
                    glProg.setTexture("InputBuffer",chromaWide);
                    glProg.drawBlocks(chromaHuge);
                }
            }
            glProg.useAssetProgram("vivohdr/nicergb");glProg.setTexture("InputBuffer",input);glProg.setTexture("GainMap",p.GainMap);
            glProg.setVar("whitePoint",p.mParameters.whitePoint);
            final boolean signed=signedInput(hybrid);
            glProg.setVar("signedU",signed?1:0);
            p.signedRgb=signed;
            if(perChannel){
                glProg.setVar("hlModeU",1);
                glProg.setVar("clipLoU",cc.lo);glProg.setVar("clipHiU",cc.hi);
                glProg.setVar("clipHiUnflaggedU",cc.hiUnflagged?1:0);
                glProg.setVar("hlStrengthU",strength);
                glProg.setVar("clipFlagsU",flagsTex!=null?1:0);
                if(flagsTex!=null)glProg.setTexture("ClipFlags",flagsTex);
                glProg.setVar("chromaU",chromaNear!=null?1:0);
                if(chromaNear!=null){glProg.setTexture("Chroma8",chromaNear);glProg.setTexture("Chroma32",chromaWide);glProg.setTexture("Chroma128",chromaHuge);}
                glProg.setVar("blockU",block);
                glProg.setVar("chromaLimitU",chromaLimit);
                float defringe=PreferenceKeys.hybridValue("highlight_defringe",0.85f);
                glProg.setVar("defringeU",defringe>0f?defringe:-1f);
            } else {
                // SCAM HDR: neutralise at the plateau; hybrid with the recovery off: plain WB and lens shading.
                if(clip==null)clip=new float[]{1e30f,1e30f,1e30f};
                glProg.setVar("hlModeU",0);
                glProg.setVar("clipLevel",clip[0],clip[1],clip[2]);
            }
            glProg.setVar("cropOffset",ox,oy);
            glProg.setVar("inverseSize",1f/size.x,1f/size.y);
            // Keep the ping-pong cursor in sync for every following postprocessing pass.
            WorkingTexture=p.getMain();glProg.drawBlocks(WorkingTexture);glProg.closed=true;p.regenerationSense=1;
            // Clip band: the dashed orange/blue line where a slanted clip edge alternates between clipped-mean and
            // unclipped-sample colours (assets/shaders/vivohdr/clipband.glsl). pref_lmc_hybrid_highlight_band 0..1.
            final float band=perChannel&&flagsTex!=null?Math.max(0f,Math.min(1f,PreferenceKeys.hybridValue("highlight_band",1f))):0f;
            if(band>0f){
                GLTexture banded=p.getMain();
                glProg.useAssetProgram("vivohdr/clipband",false);
                glProg.setTexture("InputBuffer",WorkingTexture);
                glProg.setTexture("ClipFlags",flagsTex);
                glProg.setVar("radiusU",3*s);glProg.setVar("zoneU",2*s);
                glProg.setVar("strengthU",band);
                glProg.drawBlocks(banded);glProg.closed=true;
                WorkingTexture=banded;
            }
            if(hybrid)com.particlesdevs.photoncamera.util.Log.i("NICE_PIPELINE","nicergb input "+(signed?"signed (clamped after the noise reduction or the ARK colour averages)":"clamped per pixel (post_ab_clamp)"));
            if(perChannel)com.particlesdevs.photoncamera.util.Log.i("NICE_PIPELINE","highlight recovery per channel: chroma="
                    +(chromaNear!=null?chromaNear.mSize.x+"x"+chromaNear.mSize.y+" block "+block:"neutral")
                    +" limit="+chromaLimit+" band="+band+" gpu ms="+(System.currentTimeMillis()-gpuStart));
        }finally{
            input.close();
            if(flagsTex!=null)flagsTex.close();
            if(chromaNear!=null)chromaNear.close();
            if(chromaWide!=null)chromaWide.close();
            if(chromaHuge!=null)chromaHuge.close();
        }
    }
}

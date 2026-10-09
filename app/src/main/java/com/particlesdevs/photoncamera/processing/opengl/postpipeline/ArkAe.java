package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Locale;

/**
 * Smart-HDR statistics and auto exposure of the ArkCam 1.23 / SCAM 9.6 photo tone: libfc_suppressor.so
 * run_unified_hdr_pipeline (0x724e0), ported line by line from research/hybrid5/ref/tone/ark_ae.py (that reference
 * matches the native code under emulation within 5e-5). Pure Java on a linear Rec.709 buffer (the G_CLEAN equivalent
 * "arkLow"), no GL and no Android, so it is unit-tested against the Python reference.
 */
public final class ArkAe {
    private ArkAe() {}

    /** AgX look presets @0x7720..0x7840 [code 0x73070-0x730e0]: slope, sp, tp, min_ev, max_ev, sat, ev. Look 4 = Custom. */
    static final float[][] LOOKS = {
            {2.7f, 1.35f, 1.6f, -10.0f, 3.5f, 1.0f, 0.25f},
            {2.8f, 1.5f, 1.5f, -7.5f, 3.5f, 1.2f, 0.0f},
            {2.1f, 1.4f, 1.4f, -8.0f, 4.0f, 0.95f, 0.0f},
            {2.4f, 1.35f, 1.4f, -7.5f, 3.5f, 1.05f, 0.0f}};

    /** Effective GCamSettings fields read by the AE (defaults: ArkCam 2.85 X8U, tone_port.md section 3). */
    public static final class Settings {
        public float aeTarget = 0.15f, maxBoost = 5.0f, minLimit = 0.5f;
        public float hlOverflow = 2.0f, hlBlend = 0.33f;
        public float nightThresh = 100f, nightDim = 1.0f, facePriority = 0.7f;
        public int metering = 0;
        public float brightThresh = 0.8f, darkThresh = 15f, darkPixelThresh = 10f, hlBoost = 1.0f, contrastBoost = 0.5f;
        public float shadowStr = 2.0f, highlightStr = 2.0f;
        /** pref_sharp_ef_enabled_key: the adaptive fusion strengths (always on in 2.85). */
        public boolean efEnabled = true;
        public int look = 4;
        public float slope = 2.7f, sp = 1.35f, tp = 1.6f, minEv = -8.5f, maxEv = 3.5f, sat = 1.0f, ev = 0.30f;
    }

    /** Exposure, ceiling, fusion strengths and the effective AgX parameters of one shot (+ the logged statistics). */
    public static final class Result {
        public float ae = 1f, clip = 1f, deficit = 1f, effS, effH, nf = 1f;
        public float p10, p50, p98, p995, mean, geo, gmax, ovArea, flat, spread, r, m, realPeak;
        public boolean bento;
        public float slope, sp, tp, minEv, maxEv, sat, ev;
        public int look;

        /** One line in the spirit of the mod's SMART_HDR_STAT log (for a side-by-side with logcat FC_HOOK of ArkCam). */
        public String describe() {
            return String.format(Locale.ROOT,
                    "clip=%.3f gmax=%.3f ae=%.4f geo=%.5f mean=%.5f p10=%.5f p50=%.5f p98=%.5f p995=%.5f nf=%.3f deficit=%.4f"
                            + " effS=%.4f effH=%.4f flat=%.3f spread=%.4f ov=%.4f bento=%d agx(slope=%.3f sp=%.2f tp=%.2f ev=[%.2f,%.2f]+%.2f sat=%.2f look=%d)",
                    clip, gmax, ae, geo, mean, p10, p50, p98, p995, nf, deficit, effS, effH, flat, spread, ovArea, bento ? 1 : 0,
                    slope, sp, tp, minEv, maxEv, ev, sat, look);
        }
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    /** log2 of a positive double. */
    private static double log2(double v) { return Math.log(v) / Math.log(2.0); }

    /**
     * ark_ae.smart_hdr: px holds width*height pixels of {@code channels} floats (RGB or RGBA, linear Rec.709, rows in
     * order); iso / maxIso: ISO of the last preview frame and SENSOR_MAX_ANALOG_SENSITIVITY (the mod falls back to
     * 100 / 10000). No face metering (fp = 0).
     */
    public static Result smartHdr(FloatBuffer px, int channels, int width, int height, Settings s, int iso, int maxIso) {
        // Rows are read in bulk (one copy per row instead of a buffer call per sample: ~3 Mpixel on the phone).
        final FloatBuffer src = px.duplicate();
        final int base = px.position(), rowLen = width * channels;
        return smartHdr((y, row) -> {
            src.position(base + y * rowLen);
            src.get(row, 0, rowLen);
        }, channels, width, height, s, iso, maxIso);
    }

    /** Source of the rows of {@link #smartHdr(Rows, int, int, int, Settings, int, int)}: row y into dst (width * channels floats). */
    public interface Rows {
        void get(int y, float[] dst);
    }

    /**
     * Shot speed (W1.6): {@link #smartHdr(FloatBuffer, int, int, int, Settings, int, int)} on an RGBA16F read-back kept as IEEE
     * halves (half the bytes of the GL_FLOAT read-back and no driver conversion): every half is widened exactly through
     * {@link HalfFloat#TABLE}, so the statistics see the same floats.
     */
    public static Result smartHdrHalf(ShortBuffer px, int channels, int width, int height, Settings s, int iso, int maxIso) {
        final ShortBuffer src = px.duplicate();
        final int base = px.position(), rowLen = width * channels;
        final short[] halves = new short[rowLen];
        final float[] table = HalfFloat.TABLE;
        return smartHdr((y, row) -> {
            src.position(base + y * rowLen);
            src.get(halves, 0, rowLen);
            for (int i = 0; i < rowLen; i++) row[i] = table[halves[i] & 0xffff];
        }, channels, width, height, s, iso, maxIso);
    }

    /** ark_ae.smart_hdr on rows from any source (see the FloatBuffer variant). */
    public static Result smartHdr(Rows rows, int channels, int width, int height, Settings s, int iso, int maxIso) {
        final int rowLen = width * channels;
        final float[] row = new float[rowLen];
        // 0x725a8 loop: maximum over all finite samples
        float gmax = -1000f;
        for (int y = 0; y < height; y++) {
            rows.get(y, row);
            for (int o = 0; o < rowLen; o += channels) {
                for (int c = 0; c < 3; c++) {
                    float v = row[o + c];
                    if (!Float.isNaN(v) && !Float.isInfinite(v) && v > gmax) gmax = v;
                }
            }
        }
        final double clip = gmax > 1.05f ? Math.min(gmax, 4.0) : 1.0;                     // 0x72608-0x72634
        final float invClip = (float) (1.0 / clip);
        final int mode = s.metering;
        final float halfW = width * 0.5f, halfH = height * 0.5f;
        final double[] hy = new double[4096], hm = new double[4096];
        double tot = 0, sumY = 0, sumLog = 0;
        for (int y = 0; y < height; y++) {
            rows.get(y, row);
            for (int x = 0; x < width; x++) {
                int o = x * channels;
                float rr = row[o], gg = row[o + 1], bb = row[o + 2];
                float yv = gg * 0.7152f + rr * 0.2126f + bb * 0.0722f;                    // 0x728c4-0x728e0
                float mv = Math.max(Math.max(Math.max(gg, bb), rr), yv);                    // 0x728d0-0x728f8
                double w = 1.0;
                if (mode >= 1) {                                                             // 0x72924-0x729a0
                    float dx = (x - halfW) / halfW, dy = (y - halfH) / halfH, d2 = dx * dx + dy * dy;
                    if (mode == 2) w = d2 < 0.04f ? 10 : d2 < 0.16f ? 2 : 0;
                    else if (mode == 1) w = d2 < 0.25f ? 4 : d2 < 0.5625f ? 2 : 1;
                }
                if (Float.isNaN(yv)) yv = 0f;
                if (Float.isNaN(mv)) mv = 0f;
                int iy = (int) (clamp(yv * invClip, 0f, 1f) * 4095.99f);                       // 0x727f8-0x72834
                int im = (int) (clamp(mv * invClip, 0f, 1f) * 4095.99f);
                hy[iy] += w;
                hm[im] += w;
                tot += w;
                if (w != 0) {
                    sumY += yv * w;
                    sumLog += (float) Math.log(Math.max(yv, 1e-4f)) * w;
                }
            }
        }
        tot = Math.max(tot, 1);
        Result r = new Result();
        r.gmax = gmax;
        r.clip = (float) clip;
        r.bento = gmax > 1.05f;
        r.mean = (float) (sumY / tot);
        r.geo = (float) Math.exp(sumLog / tot);                                              // 0x72b3c
        final double step = clip / 4096.0;
        double p10 = 0, p50 = 0, p98 = -1, p995 = -1;
        boolean f10 = false, f50 = false;
        double cy = 0, cm = 0;
        for (int i = 0; i < 4096; i++) {                                                     // 0x72a4c-0x72b30
            cy += hy[i];
            cm += hm[i];
            if (!f10 && cy / tot >= 0.1) { p10 = i * step; f10 = true; }
            if (!f50 && cy / tot >= 0.5) { p50 = i * step; f50 = true; }
            if (p98 < 0 && cm / tot >= 0.98) p98 = i * step;
            if (p995 < 0 && cm / tot >= 0.995) p995 = i * step;
        }
        if (p98 < 0) p98 = clip * 0.99975586;
        if (p995 < 0) p995 = p98;
        if (p98 == clip) p98 = clip * 0.99975586;
        r.p10 = (float) p10; r.p50 = (float) p50; r.p98 = (float) p98; r.p995 = (float) p995;
        final double meter = r.geo;
        final double fp = 0.0;                                                               // no face metering
        double nf = 1.0;                                                                     // iso ratio 0x72bac-0x72c3c
        if (iso >= 1) {
            double thr = Math.max(s.nightThresh, 1.0), isof = Math.max(iso, 1);
            if (isof > thr) {
                double mx = Math.max(maxIso, thr + 1);
                double t = Math.min(Math.max(Math.max(log2(isof / thr), 0) / Math.max(log2(mx / thr), 0.001), 0), 1);
                nf = Math.max(1 - t * t, 0);
            }
        }
        r.nf = (float) nf;
        final double mxB = s.maxBoost, mnL = s.minLimit;
        double deficit = 1.0, ae;
        if (meter > 1e-4) {                                                                  // 0x72c50
            double target = (0.2857143 + 0.7142857 * Math.pow(nf, 0.6)) * s.aeTarget;          // 0x72c74-0x72ca4
            ae = target / meter;                                                             // 0x72cb0
            double nd = s.nightDim;
            if (nd > 0.01 && Math.abs(nd - 1) > 0.001) ae = Math.pow(ae, nd);                  // 0x72cb4-0x72cd8
            double aeC = Math.max(Math.min(ae, mxB), mnL);                                     // 0x72cdc-0x72d08
            double aeF = aeC;
            if (p995 > 0.01 && p995 * aeC > s.hlOverflow) {                                    // 0x72d0c-0x72d48
                double lim = s.hlOverflow / p995;
                double blend = s.hlBlend + (1 - s.hlBlend) * fp;
                aeF = lim + (aeC - lim) * blend;
            }
            aeF = Math.max(aeF, mnL);                                                          // 0x72d4c
            if (aeF < aeC && aeF > 0.001 && aeC > 0.001) deficit = aeC / aeF;                  // 0x72d60-0x72d74
            ae = Math.max(Math.min(mxB, aeF), mnL);                                            // 0x72da8-0x72dc4
        } else {
            ae = Math.max(Math.min(mxB, 1.0), mnL);
        }
        r.ae = (float) ae;
        r.deficit = (float) deficit;
        final double bt = s.brightThresh;
        int j = (int) (Math.min(Math.max(bt / Math.max(ae, 0.001) / clip, 0), 1) * 4095.99);       // 0x72dd4-0x72e90
        double ov = 0;
        for (int i = j; i < 4096; i++) ov += hm[i];
        r.ovArea = (float) (ov / tot);
        // AgX parameters: Custom (look 4) unless a preset look overrides them.
        r.look = s.look;
        r.slope = s.slope; r.sp = s.sp; r.tp = s.tp; r.minEv = s.minEv; r.maxEv = s.maxEv; r.sat = s.sat; r.ev = s.ev;
        r.effS = s.shadowStr;
        r.effH = s.highlightStr;
        if (s.efEnabled) {                                                                   // 0x72e98
            double a = ae;
            double sp10 = Math.sqrt(Math.max(p10 * a, 0)), sp50 = Math.sqrt(Math.max(p50 * a, 0));
            double sp98 = Math.sqrt(Math.min(Math.max(p98 * a, 0), a));                         // 0x72edc-0x72f28
            double spread = sp98 - sp10;
            double rr = spread / Math.max(s.darkThresh * 0.15, 0.001);                          // 0x72f04-0x72f2c
            double m = Math.min(Math.max((sp50 - 0.2) / 0.22, 0), 1);                          // 0x72f3c-0x72f6c
            double shadowF = (1 - 0.35 * m * m) * (rr + 0.5 * rr * rr) / (1 + 1.5 * rr);        // 0x72f50-0x72f78
            double effS = Math.max(nf * (s.shadowStr * shadowF + 0.5 * log2(Math.max(deficit, 1))), 0);   // 0x72f7c-0x72fd4
            double e = Math.min(Math.max(p995 * a - bt, 0) / Math.max(bt, 0.5), 0.5);           // 0x72f98-0x72fb8
            double realPeak = a * (p98 * (1 - e) + p995 * e);                                   // 0x72fc0-0x72fd8
            double need = realPeak > bt ? log2(realPeak / bt) / 1.5 : 0.0;                      // 0x72fdc-0x72ffc
            double effH = Math.min(need * Math.max(s.hlBoost, 0), s.highlightStr);              // 0x73038-0x7305c
            double q = Math.max(1 - spread / Math.max(s.darkPixelThresh * 0.05, 0.001), 0);      // 0x73000-0x73058
            double flat = q * q * (3 - 2 * q);                                                  // 0x73060-0x73068
            if (s.look >= 0 && s.look <= 3) {                                                  // 0x7306c-0x730e0
                float[] l = LOOKS[s.look];
                r.slope = l[0]; r.sp = l[1]; r.tp = l[2]; r.minEv = l[3]; r.maxEv = l[4]; r.sat = l[5]; r.ev = l[6];
            }
            double cb = flat * s.contrastBoost;                                               // 0x730e4-0x73108
            if (cb > 1e-4) r.slope += (float) cb;                                              // AgX (operator 0): 0x73128
            r.effS = (float) effS;
            r.effH = (float) effH;
            r.realPeak = (float) realPeak;
            r.flat = (float) flat;
            r.spread = (float) spread;
            r.r = (float) rr;
            r.m = (float) m;
        }
        return r;
    }

    /**
     * Exposure multipliers of the four synthetic exposures of the fusion [code 0x78ac4-0x78b34]:
     * (2^(-1.5 effH), 1, min(2^effS, 8), min(2^(2 effS), 16)).
     */
    public static float[] exposures(Result r) {
        return new float[]{(float) Math.pow(2.0, -1.5 * r.effH), 1f,
                (float) Math.min(Math.pow(2.0, r.effS), 8.0), (float) Math.min(Math.pow(2.0, 2.0 * r.effS), 16.0)};
    }
}

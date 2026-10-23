package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import java.util.Arrays;

/**
 * Tables and host arithmetic of {@link ScamDenoise} (no Android or GL state, so it runs in unit tests).
 * <p>
 * Tables: SCAM 9.6 (reference finish) as WhyCam 2.85 PRO leaves them for the main camera (research/hybrid5/denoise_port.md
 * §2.4). Luma tiers by the SNR of the finish input: {5: SabreLow, 10: GID14_T10, 20: SabreMed, 40: SabreHigh,
 * 80: GID14_T80} (FinishShot writes the Sabre tiers over luma_denoise_004 for Sabre shots); per band b0..b4 (b0 finest)
 * strength (sigma multiplier), revert factor (share of the unfiltered level, b4 has none) and outlier threshold
 * (x16 = share of the strict 3x3 weights). Chroma tiers {1: Low, 5: Med, 10: High, 20: VeryHigh}: strength and the
 * integer outlier threshold. Between tiers everything is linear in SNR, outside the first/last tier it is clamped
 * (0x33ec5d4 + 0x54b36e8).
 * <p>
 * P135 (owner, 2026-10-15: very strong grain, made stronger by the sharpening, in poor light on every phone): WhyCam's
 * SabreLow tier (SNR 5) is {1, 3, 0, 0, 0} with revert {9, 9, ...}: the fine levels put the removed noise back amplified
 * (out = d + rf (recon - d), rf' = 9 / 2 at 4 effective frames) and the coarse levels are not denoised at all, so the darker
 * the shot the grainier it came out (SCA_20261015_024212: SNR 3.04, 0.8 s ISO 6400, 4 frames). The owner wants the grain,
 * not its abnormal amount: the tier now denoises every level with the stock SabreLow strengths (the coarse levels, where
 * the blotches and colour mottle are, as the reference) and puts back 60 / 50 % of the fine grain (revert 0.6 / 0.5 on b0 / b1,
 * under the 0.75 / 0.7 of the SNR-10 tier, so a darker shot is never grainier than a brighter one); and no revert above 1
 * by default (dn_revert_max 1: the removed noise never comes back stronger than it was). The tiers from SNR 10 up stay
 * WhyCam's.
 */
final class ScamDenoiseTables {
    private ScamDenoiseTables() {}
    static final int BANDS = 5, LEVELS = 4;

    static final float[] LUMA_SNR = {5f, 10f, 20f, 40f, 80f};
    /** Tier source: the Sabre tiers (multiplier dn_sabre_luma_mult) and luma_denoise_004 = "GID14" (dn_luma_gid14_mult). */
    static final boolean[] LUMA_SABRE = {true, false, true, true, false};
    static final float[][] LUMA_STRENGTH = {
            {1.2f, 1.1f, 1.2f, 1.1f, 1.0f}, {2f, 1f, 0.2f, 0f, 0f}, {1f, 3f, 0f, 0f, 0f}, {1f, 3f, 0f, 0f, 0f}, {3f, 0.75f, 0.38f, 0f, 0f}};
    static final float[][] LUMA_REVERT = {
            {0.6f, 0.5f, 0.08f, 0.12f, 0f}, {0.75f, 0.7f, 0.7f, 0.0625f, 0f}, {1f, 1f, 0.1f, 0.14f, 0f}, {1f, 1f, 0.1f, 0.1f, 0f},
            {0.95f, 0.95f, 0.95f, 0.1f, 0f}};
    static final float[][] LUMA_OUTLIER = {
            {0.6f, 0.4f, 0.2447f, 0.2383f, 0.3473f}, {1f, 1f, 1f, 0.3731f, 0.4647f}, {0.7f, 0.4f, 0.322f, 0.508f, 0.582f},
            {0.6f, 0.6f, 0.4725f, 0.363f, 0.0777f}, {1f, 1f, 1f, 0.363f, 0.0777f}};
    /** stock values of the same tiers (unpatched sabre_luma_denoise / luma_denoise_004), for dn_coarse_stock on b2..b4. */
    static final float[][] LUMA_STOCK_STRENGTH = {
            {1.2f, 1.1f, 1.2f, 1.1f, 1.0f}, {1.5f, 1.32f, 0.8f, 0.6f, 0.7f}, {1.2f, 1.0f, 1.0f, 0.7f, 0.1f},
            {1.2f, 0.7f, 0.401f, 0.557f, 0.379f}, {1.0f, 0.95f, 0.36f, 0.4f, 0.28f}};
    static final float[][] LUMA_STOCK_REVERT = {
            {0.15f, 0.1f, 0.08f, 0.12f, 0f}, {0.1f, 0.1f, 0.075f, 0.0625f, 0f}, {0.15f, 0.1f, 0.1f, 0.14f, 0f},
            {0.2f, 0.1f, 0.1f, 0.1f, 0f}, {0.15f, 0.1f, 0.1f, 0.1f, 0f}};
    static final float[] CHROMA_SNR = {1f, 5f, 10f, 20f};
    static final float[][] CHROMA_STRENGTH = {{5f, 5f, 5f, 4f, 4f}, {5f, 5f, 5f, 1f, 2f}, {1f, 1f, 1f, 1f, 1.5f}, {0.5f, 0.5f, 0f, 0f, 0f}};
    static final float[][] CHROMA_OUTLIER = {{0f, 5f, 5f, 4f, 4f}, {0f, 5f, 5f, 4f, 4f}, {0f, 5f, 5f, 4f, 4f}, {0f, 5f, 5f, 4f, 4f}};

    /** User configuration (defaults = WhyCam 2.85 effective values, SabreLow = stock and the revert limit 1, P135). */
    static final class Config {
        float[] lumaSnr = LUMA_SNR.clone();
        float[][] lumaStrength = copy(LUMA_STRENGTH), lumaRevert = copy(LUMA_REVERT), lumaOutlier = copy(LUMA_OUTLIER);
        float[] chromaSnr = CHROMA_SNR.clone();
        float[][] chromaStrength = copy(CHROMA_STRENGTH), chromaOutlier = copy(CHROMA_OUTLIER);
        float lumaMult = 1f, sabreMult = 1f, gid14Mult = 1f, chromaMult = 1f;
        float chromaFloor = 0f, revertMult = 1f, revertMax = 1f, coarseStock = 0f;   // P135: no amplified revert
    }

    static float[][] copy(float[][] a) {
        float[][] out = new float[a.length][];
        for (int i = 0; i < a.length; i++) out[i] = a[i].clone();
        return out;
    }

    /** The two tiers around snr and the weight of the upper one (0x33ec5d4: upper_bound, clamp outside). */
    static final class Pick {
        int lo, hi;
        float t;
        @Override public String toString() { return lo == hi ? "t" + (lo + 1) : "t" + (lo + 1) + "+" + (hi + 1) + "@" + Math.round(t * 100) / 100f; }
    }

    static Pick pick(float[] keys, float snr) {
        Integer[] order = new Integer[keys.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Float.compare(keys[a], keys[b]));
        Pick p = new Pick();
        int upper = 0;
        while (upper < order.length && !(keys[order[upper]] > snr)) upper++;
        if (upper == 0) { p.lo = p.hi = order[0]; return p; }
        if (upper == order.length) { p.lo = p.hi = order[order.length - 1]; return p; }
        p.lo = order[upper - 1]; p.hi = order[upper];
        float k0 = keys[p.lo], k1 = keys[p.hi];
        p.t = k1 > k0 ? (snr - k0) / (k1 - k0) : 0f;
        return p;
    }

    static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    /** Per band {strength, revert, outlier} of the luma denoise at this SNR. */
    static float[][] luma(Config c, float snr, Pick[] pickOut) {
        final int tiers = c.lumaSnr.length;
        float[][][] t = new float[tiers][BANDS][3];
        for (int i = 0; i < tiers; i++) {
            boolean sabre = i < LUMA_SABRE.length ? LUMA_SABRE[i] : true;
            float mult = c.lumaMult * (sabre ? c.sabreMult : c.gid14Mult);
            for (int b = 0; b < BANDS; b++) {
                float s = c.lumaStrength[i][b], r = c.lumaRevert[i][b];
                if (b >= 2 && i < LUMA_STOCK_STRENGTH.length && c.coarseStock > 0f) {
                    s = lerp(s, LUMA_STOCK_STRENGTH[i][b], c.coarseStock);
                    r = lerp(r, LUMA_STOCK_REVERT[i][b], c.coarseStock);
                }
                t[i][b][0] = Math.max(0f, s * mult);
                t[i][b][1] = r;
                t[i][b][2] = c.lumaOutlier[i][b];
            }
        }
        Pick p = pick(c.lumaSnr, snr);
        if (pickOut != null) pickOut[0] = p;
        float[][] out = new float[BANDS][3];
        for (int b = 0; b < BANDS; b++) for (int f = 0; f < 3; f++) out[b][f] = lerp(t[p.lo][b][f], t[p.hi][b][f], p.t);
        for (int b = 0; b < BANDS; b++) out[b][1] = Math.max(0f, Math.min(c.revertMax, out[b][1] * c.revertMult));
        return out;
    }

    /** Per band {strength, outlier} of the chroma denoise at this SNR (floor on b0..b2 relative to the unscaled table). */
    static float[][] chroma(Config c, float snr, Pick[] pickOut) {
        Pick p = pick(c.chromaSnr, snr);
        if (pickOut != null) pickOut[0] = p;
        float[][] out = new float[BANDS][2];
        for (int b = 0; b < BANDS; b++) {
            float s = lerp(c.chromaStrength[p.lo][b], c.chromaStrength[p.hi][b], p.t);
            if (b <= 2) s = Math.max(s, c.chromaFloor);
            out[b][0] = Math.max(0f, s * c.chromaMult);
            out[b][1] = lerp(c.chromaOutlier[p.lo][b], c.chromaOutlier[p.hi][b], p.t);
        }
        return out;
    }

    /**
     * SNR of the finish input as the reference EstimateSnr computes it for the merged frame: mean signal mu = 0.18 / display gain,
     * noise from the frame's model (green channel) scaled by the measured level-0 difference variance (g01 / 2 = variance
     * of one pixel for a white spectrum) and by rho (the model WhyCam hands the reference is noisier than the real one).
     */
    static float snr(float mu, float g01, float sG, float rG, float rhoS, float rhoR) {
        double var = Math.max(g01, 1e-6) * 0.5 * (rhoS * sG * Math.max(mu, 0f) + rhoR * rG);
        return var > 0 ? (float) (mu / Math.sqrt(var)) : 1000f;
    }

    /**
     * P320: the noise terms of a DCG shot with measured zones in ScamDenoise's white-balanced RGB. {@code zones} = {slope S,
     * high-gain offset, low-gain offset, knee t} (NoiseModeler.dcgZones: validated), measured by the worker on one green phase
     * in normalised units; per channel S_c = S camS_c / camS_G and O_c = O camO_c / camO_G (the camera model's channel ratios,
     * 1 without a model or for a non-positive green term), weighted as the single curve: sY = sum wY^2 g S_c, rY = sum
     * wY^2 g^2 O_c, the green terms for the SNR, g = 1 / white point (1 for a missing or non-positive one); the knee in Y is
     * green's level g_G t (a neutral grey's luminance; 0 for one measured zone, whose two offsets are equal). Offsets at least
     * 1e-12 and slopes at least 1e-9 per channel (as the camera model's).
     *
     * @return {sY, rY high gain, rY low gain, sG, rG high gain, rG low gain, knee in Y}
     */
    static float[] dcgTerms(float[] zones, float[] camS, float[] camO, float[] whitePoint) {
        final float[] wY = {0.2126f, 0.7152f, 0.0721996f};
        final float sG0 = camS != null && camS.length > 2 && camS[1] > 0f ? camS[1] : 0f;
        final float oG0 = camO != null && camO.length > 2 && camO[1] > 0f ? camO[1] : 0f;
        final float[] out = new float[7];
        for (int c = 0; c < 3; c++) {
            final float rs = sG0 > 0f && camS[c] > 0f && Float.isFinite(camS[c] / sG0) ? camS[c] / sG0 : 1f;
            final float ro = oG0 > 0f && camO[c] > 0f && Float.isFinite(camO[c] / oG0) ? camO[c] / oG0 : 1f;
            final float s = Math.max(1e-9f, zones[0] * rs), oh = Math.max(1e-12f, zones[1] * ro), ol = Math.max(1e-12f, zones[2] * ro);
            final float wp = whitePoint != null && whitePoint.length > c && whitePoint[c] > 1e-6f ? whitePoint[c] : 1f;
            final float g = 1f / wp;
            out[0] += wY[c] * wY[c] * g * s;
            out[1] += wY[c] * wY[c] * g * g * oh;
            out[2] += wY[c] * wY[c] * g * g * ol;
            if (c == 1) {
                out[3] = g * s;
                out[4] = g * g * oh;
                out[5] = g * g * ol;
                out[6] = g * zones[3];
            }
        }
        return out;
    }

    /** IEEE half to float (packHalf2x16 halves of the statistics readback). */
    static float half(int h) {
        int s = (h >> 15) & 1, e = (h >> 10) & 31, m = h & 1023;
        float v;
        if (e == 0) v = m * (1f / 16777216f);
        else if (e == 31) v = m == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
        else v = Float.intBitsToFloat(((e - 15 + 127) << 23) | (m << 13));
        return s != 0 ? -v : v;
    }

    /**
     * Noise of one pyramid level from the block statistics (8 floats per block: mean Y, texture T, gY1, gY2, gU1, gU2,
     * gV1, gV2): the median of each g over the flattest quarter of the valid blocks (lowest T; valid = 1e-5 < mean Y <
     * clip), and the colour scale uvs = sqrt(gY / gU), sqrt(gY / gV) per stride as the median of the block ratios over the
     * darker half of those blocks (the reference: the read-noise ratio). Returns null when fewer than 16 blocks are valid.
     * Layout of the result: {gY1, gY2, gU1, gU2, gV1, gV2, uvsU1, uvsU2, uvsV1, uvsV2, blocks used}.
     */
    static float[] reduce(float[] blocks, int count, float clip) {
        int[] valid = new int[count];
        int n = 0;
        for (int i = 0; i < count; i++) {
            int o = 8 * i;
            float y = blocks[o];
            boolean ok = y > 1e-5f && y < clip && blocks[o + 2] > 0f;
            for (int k = 1; k < 8 && ok; k++) ok = Float.isFinite(blocks[o + k]);
            if (ok) valid[n++] = i;
        }
        if (n < 16) return null;
        // Shot speed: the two stable sorts of reduceLegacy (boxed Integer[] with a Float.compare lambda) as sorts of primitive
        // keys: the float's Float.compare order in the high 32 bits, the position in the input in the low 32 bits. The keys
        // are distinct, so any sort gives the order of the stable sort, element by element.
        final long[] keys = new long[n];
        for (int i = 0; i < n; i++) keys[i] = ((long) sortableBits(blocks[8 * valid[i] + 1]) << 32) | i;
        Arrays.sort(keys);
        final int[] byT = new int[n];
        for (int i = 0; i < n; i++) byT[i] = valid[(int) keys[i]];
        int m = Math.max(16, n / 4);
        float[] out = new float[11];
        float[] tmp = new float[m];
        for (int k = 0; k < 6; k++) {
            for (int i = 0; i < m; i++) tmp[i] = blocks[8 * byT[i] + 2 + k];
            out[k] = median(tmp, m);
        }
        final long[] keysY = new long[m];
        for (int i = 0; i < m; i++) keysY[i] = ((long) sortableBits(blocks[8 * byT[i]]) << 32) | i;
        Arrays.sort(keysY);
        final int[] byY = new int[m];
        for (int i = 0; i < m; i++) byY[i] = byT[(int) keysY[i]];
        int d = Math.max(8, m / 2);
        float[] r = new float[d];
        for (int s = 0; s < 2; s++) {
            for (int c = 0; c < 2; c++) {
                for (int i = 0; i < d; i++) {
                    int o = 8 * byY[i];
                    r[i] = blocks[o + 2 + s] / Math.max(blocks[o + 4 + 2 * c + s], 1e-6f);
                }
                out[6 + 2 * c + s] = (float) Math.sqrt(Math.max(median(r, d), 1e-4f));
            }
        }
        out[10] = m;
        return out;
    }

    /**
     * Signed int whose order is Float.compare's order of the float: -0.0 before 0.0, every NaN (canonical bits) after
     * +Infinity.
     */
    static int sortableBits(float f) {
        final int bits = Float.floatToIntBits(f);
        return bits ^ ((bits >> 31) & 0x7fffffff);
    }

    /** The original {@link #reduce} (boxed sorts), the reference of the primitive one (ShotSpeedBitExactTest) and the old post mode. */
    static float[] reduceLegacy(float[] blocks, int count, float clip) {
        int[] valid = new int[count];
        int n = 0;
        for (int i = 0; i < count; i++) {
            int o = 8 * i;
            float y = blocks[o];
            boolean ok = y > 1e-5f && y < clip && blocks[o + 2] > 0f;
            for (int k = 1; k < 8 && ok; k++) ok = Float.isFinite(blocks[o + k]);
            if (ok) valid[n++] = i;
        }
        if (n < 16) return null;
        Integer[] byT = new Integer[n];
        for (int i = 0; i < n; i++) byT[i] = valid[i];
        Arrays.sort(byT, (a, b) -> Float.compare(blocks[8 * a + 1], blocks[8 * b + 1]));
        int m = Math.max(16, n / 4);
        float[] out = new float[11];
        float[] tmp = new float[m];
        for (int k = 0; k < 6; k++) {
            for (int i = 0; i < m; i++) tmp[i] = blocks[8 * byT[i] + 2 + k];
            out[k] = median(tmp, m);
        }
        Integer[] byY = Arrays.copyOf(byT, m);
        Arrays.sort(byY, (a, b) -> Float.compare(blocks[8 * a], blocks[8 * b]));
        int d = Math.max(8, m / 2);
        float[] r = new float[d];
        for (int s = 0; s < 2; s++) {
            for (int c = 0; c < 2; c++) {
                for (int i = 0; i < d; i++) {
                    int o = 8 * byY[i];
                    r[i] = blocks[o + 2 + s] / Math.max(blocks[o + 4 + 2 * c + s], 1e-6f);
                }
                out[6 + 2 * c + s] = (float) Math.sqrt(Math.max(median(r, d), 1e-4f));
            }
        }
        out[10] = m;
        return out;
    }

    static float median(float[] a, int n) {
        float[] c = Arrays.copyOf(a, n);
        Arrays.sort(c);
        return (n & 1) == 1 ? c[n / 2] : 0.5f * (c[n / 2 - 1] + c[n / 2]);
    }

    /** White-noise level gains when nothing could be measured: difference variance 2 sigma^2, kernel energy per level. */
    static float whiteGain(int level, boolean chroma) {
        return (float) (2.0 * Math.pow(chroma ? 0.09765625 : 0.19269013, level));
    }

    static String fmt(float[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) { if (i > 0) sb.append('/'); sb.append(Math.round(v[i] * 100f) / 100f); }
        return sb.toString();
    }
}

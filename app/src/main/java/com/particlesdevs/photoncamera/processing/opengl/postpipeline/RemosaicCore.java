package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_NEAREST;
import static android.opengl.GLES20.GL_MIRRORED_REPEAT;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * The remosaic reconstruction itself, without a pipeline around it.
 *
 * <p>Split out of the post-pipeline node so the merge can use it too. Running
 * it after the merge cannot work: the merge packs one bayer quad per texel and
 * leans on that packing to keep each colour to its own channel, which is false
 * for a mosaic - a texel then holds four samples of ONE colour. Every
 * displacement finer than a colour block mixes neighbouring blocks, and the
 * merged frame arrives with its colours already crossed, measurably so: on a
 * merged frame 10.6% of blocks come out magenta against 0.02% on a single one,
 * with no interpolation involved in the measurement at all. Remosaicking each
 * frame as it is uploaded hands the merge ordinary bayer, which is the layout
 * its packing was written for, and it keeps its sub-pixel alignment and its
 * kernel regression.
 */
public class RemosaicCore {

    /** Reduction tile for the channel means; 32x32 keeps the grid small enough to read back. */
    private static final int MEAN_TILE = 32;

    private static final String Name = "RemosaicCore";

    private final GLProg glProg;
    /** Per-site gains inside a block, quadrant-major; all ones until measured. */
    private float[] blockGain = flatProfile();
    /**
     * White balance gains and the block profile, kept across frames.
     *
     * <p>Both are whole-frame statistics and both are read back to the CPU,
     * which stalls the GPU. Over a burst shot in a fraction of a second the
     * scene does not change enough to matter, and the block profile is a
     * property of the sensor, not of the frame - so they are measured on the
     * first frame and reused. That removes a pipeline stall per frame, which is
     * most of what a second and third frame would otherwise cost.
     */
    private float[] sharedGains;
    private boolean profileMeasured;
    private TetraResponseProfile.GainMap detailMap;
    private String activeBackend;

    public RemosaicCore(GLProg glProg) {
        this.glProg = glProg;
    }

    /** The plain bayer layout the assembly writes, for the given sensor pattern. */
    public static int emittedCfaPattern(int cfaPattern) {
        return cfaPatternFor(quadColorsFor(cfaPattern));
    }

    /**
     * Rearranges one mosaic frame into plain bayer.
     *
     * @return a new texture the caller owns; the input is left untouched.
     */
    public GLTexture run(GLTexture raw, Point rawSize, int cfaPattern, float black, float white,
                         boolean verbose) {
        if (activeBackend == null) activeBackend = PreferenceKeys.getRemosaicBackend();
        if ("tetra_detail".equals(activeBackend)) {
            int[] phase = PreferenceKeys.getRemosaicPhase();
            if (PreferenceKeys.getRemosaicBlockSize() != 4 || rawSize.x < 8 || rawSize.y < 8
                    || rawSize.x % 8 != 0 || rawSize.y % 8 != 0) {
                throw new IllegalArgumentException("Tetra Detail requires 4x4 colour blocks and dimensions divisible by 8");
            }
            // Normalize phase for signed/imported values as well as UI values.
            phase[0] = Math.floorMod(phase[0], 8);
            phase[1] = Math.floorMod(phase[1], 8);
            int[] quad = quadColorsFor(cfaPattern);
            if (sharedGains == null) sharedGains = measureGains(raw, rawSize, 4, phase, quad, black, white);
            if (detailMap == null) detailMap = PreferenceKeys.isTetraResponseCorrection()
                    ? measureDetailMap(raw, rawSize, phase, black, white) : new TetraResponseProfile.GainMap();
            if (verbose) Log.d(Name, "backend=Tetra Detail v2 block=4 phase=" + phase[0] + "," + phase[1]
                    + " spatial response=" + java.util.Arrays.toString(detailMap.spatial));
            return TetraDetailRemosaic.run(glProg, raw, rawSize, phase, quad, black, white, sharedGains, detailMap);
        }
        if (!"scamera".equals(activeBackend)) {
            throw new IllegalStateException("Selected remosaic backend is unavailable; select SCAMERA in settings");
        }
        int blockSize = PreferenceKeys.getRemosaicBlockSize();
        int profile = PreferenceKeys.getRemosaicProfile();
        // Profile picks how much the interpolation smooths. The widest kernel
        // gives the cleanest single frame; the narrowest keeps per-pixel noise
        // independent, which matters when a merge runs after this.
        int kernelSize;
        boolean useMedian;
        boolean steered = PreferenceKeys.isRemosaicSteered();
        boolean clampDiffs = PreferenceKeys.isRemosaicClampDiffs();
        boolean flatField = PreferenceKeys.isRemosaicFlatField();
        switch (profile) {
            case 0: kernelSize = 1; useMedian = false; break;                  // nearest
            case 1: kernelSize = blockSize == 2 ? 3 : 5; useMedian = false; break; // sharp
            case 3: kernelSize = blockSize == 2 ? 5 : 9; useMedian = true;  break; // smooth
            default: kernelSize = blockSize == 2 ? 5 : 9; useMedian = false; break; // balanced
        }
        int[] phase = PreferenceKeys.getRemosaicPhase();
        int[] quad = quadColorsFor(cfaPattern);

        if (verbose) {
            Log.d(Name, "remosaic: block=" + blockSize + " profile=" + profile
                    + " kernel=" + kernelSize + " median=" + useMedian
                    + " steered=" + steered + " clampDiffs=" + clampDiffs
                    + " flatField=" + flatField
                    + " phase=" + phase[0] + "," + phase[1]
                    + " cfa=" + cfaPattern
                    + " black=" + black + " white=" + white);
        }

        GLFormat rg = new GLFormat(GLFormat.DataType.FLOAT_16, 2);
        GLTexture masked = new GLTexture(rawSize, rg, null, GL_NEAREST, GL_CLAMP_TO_EDGE);
        GLTexture tmp = new GLTexture(rawSize, rg, null, GL_NEAREST, GL_CLAMP_TO_EDGE);
        GLTexture green = new GLTexture(rawSize, rg, null, GL_NEAREST, GL_CLAMP_TO_EDGE);
        GLTexture diffB = new GLTexture(rawSize, rg, null, GL_NEAREST, GL_CLAMP_TO_EDGE);
        GLTexture diffR = new GLTexture(rawSize, rg, null, GL_NEAREST, GL_CLAMP_TO_EDGE);

        try {
            if (sharedGains == null) {
                sharedGains = measureGains(raw, rawSize, blockSize, phase, quad, black, white);
            }
            float[] gains = sharedGains;
            float gainB = gains[0], gainR = gains[1];
            if (verbose) {
                Log.d(Name, "white balance alignment: gainB=" + gainB + " gainR=" + gainR);
                // Green coverage tells whether the mask actually matched the
                // mosaic: a correct block size and phase put green on half the
                // sites. Far from 0.5 means the pattern assumed here is not the
                // one in the frame.
                Log.d(Name, "green coverage=" + gains[2] + " (0.5 expected)");
            }

            if (!profileMeasured) {
                blockGain = flatField
                        ? measureBlockProfile(raw, rawSize, blockSize, phase, black, white, false)
                        : flatProfile();
                profileMeasured = true;
            }

            // Green first: the differences below are taken against it, so an
            // error here becomes a colour error there.
            maskStage(raw, null, masked, rawSize, 0, blockSize, phase, quad, black, white, 1.f, 1.f);
            if (steered) {
                greenSteer(masked, green, rawSize, blockSize * 2);
            } else {
                maskBlur(masked, tmp, green, rawSize, kernelSize);
            }

            maskStage(raw, green, masked, rawSize, 1, blockSize, phase, quad, black, white, gainB, gainR);
            maskBlur(masked, tmp, diffB, rawSize, kernelSize);
            if (clampDiffs) {
                clampDiff(diffB, masked, tmp, rawSize, blockSize * 2);
                swapInto(tmp, diffB, rawSize);
            }
            if (useMedian) {
                median(diffB, tmp, rawSize);
                swapInto(tmp, diffB, rawSize);
            }

            maskStage(raw, green, masked, rawSize, 2, blockSize, phase, quad, black, white, gainB, gainR);
            maskBlur(masked, tmp, diffR, rawSize, kernelSize);
            if (clampDiffs) {
                clampDiff(diffR, masked, tmp, rawSize, blockSize * 2);
                swapInto(tmp, diffR, rawSize);
            }
            if (useMedian) {
                median(diffR, tmp, rawSize);
                swapInto(tmp, diffR, rawSize);
            }

            // Integer target: the assembly has its own shader declaring uvec4,
            // since writing vec4 into R16UI does not land as the float suggests.
            glProg.useAssetProgram("remosaic/assemble");
            glProg.setTexture("RawBuffer", raw);
            glProg.setTexture("GreenBuffer", green);
            glProg.setTexture("DiffBBuffer", diffB);
            glProg.setTexture("DiffRBuffer", diffR);
            glProg.setVar("blockSize", blockSize);
            glProg.setVar("phase", phase[0], phase[1]);
            glProg.setVar("quadColors", quad[0], quad[1], quad[2], quad[3]);
            glProg.setVar("blackLevel", black);
            glProg.setVar("whiteLevel", white);
            glProg.setVar("gainB", gainB);
            glProg.setVar("gainR", gainR);
            glProg.setVarFloats("blockGain", blockGain);
            GLTexture out = new GLTexture(rawSize, new GLFormat(GLFormat.DataType.UNSIGNED_16),
                    null, GL_NEAREST, GL_MIRRORED_REPEAT);
            glProg.drawBlocks(out);
            glProg.closed = true;
            return out;
        } finally {
            masked.close();
            tmp.close();
            green.close();
            diffB.close();
            diffR.close();
        }
    }


    /**
     * Channel means over the whole frame, in two steps: a tile grid on the GPU,
     * then the grid summed on the CPU. The grid is a few thousand texels, small
     * enough that reading it back costs nothing next to the passes around it.
     */
    private float[] measureGains(GLTexture raw, Point rawSize, int blockSize, int[] phase,
                                 int[] quad, float black, float white) {
        Point gridSize = new Point(
                (rawSize.x + MEAN_TILE - 1) / MEAN_TILE,
                (rawSize.y + MEAN_TILE - 1) / MEAN_TILE);
        GLTexture grid = new GLTexture(gridSize, new GLFormat(GLFormat.DataType.FLOAT_32, 4),
                null, GL_NEAREST, GL_CLAMP_TO_EDGE);
        try {
            glProg.useAssetProgram("remosaic/means");
            glProg.setTexture("RawBuffer", raw);
            glProg.setVar("rawWidth", rawSize.x);
            glProg.setVar("rawHeight", rawSize.y);
            glProg.setVar("blockSize", blockSize);
            glProg.setVar("phase", phase[0], phase[1]);
            glProg.setVar("quadColors", quad[0], quad[1], quad[2], quad[3]);
            glProg.setVar("blackLevel", black);
            glProg.setVar("whiteLevel", white);
            glProg.setVar("tileSize", MEAN_TILE);
            glProg.drawBlocks(grid);
            glProg.closed = true;

            ByteBuffer buf = grid.textureBuffer(new GLFormat(GLFormat.DataType.FLOAT_32, 4));
            FloatBuffer f = buf.order(ByteOrder.nativeOrder()).asFloatBuffer();
            double sumG = 0, sumB = 0, sumR = 0, cntG = 0;
            int n = gridSize.x * gridSize.y;
            for (int i = 0; i < n; i++) {
                sumG += f.get(i * 4);
                sumB += f.get(i * 4 + 1);
                sumR += f.get(i * 4 + 2);
                cntG += f.get(i * 4 + 3);
            }
            if (cntG <= 0) return new float[]{1.f, 1.f, 0.f, 0.f};
            // Green samples are twice as many as B or R in every pattern here,
            // so their count follows from the green count.
            double cntC = cntG * 0.5;
            double meanG = sumG / cntG;
            double meanB = sumB / Math.max(cntC, 1);
            double meanR = sumR / Math.max(cntC, 1);
            float gb = (float) (meanG / (meanB + 1e-6));
            float gr = (float) (meanG / (meanR + 1e-6));
            // A frame that is almost one colour can produce an absurd ratio;
            // clamping keeps a pathological scene from wrecking the frame.
            gb = Math.min(Math.max(gb, 0.1f), 10.f);
            gr = Math.min(Math.max(gr, 0.1f), 10.f);
            double total = (double) rawSize.x * rawSize.y;
            return new float[]{gb, gr, (float) (cntG / Math.max(total, 1)), (float) meanG};
        } finally {
            grid.close();
        }
    }


    /** The response map is measured once and frozen across the entire RAW burst. */
    private TetraResponseProfile.GainMap measureDetailMap(GLTexture raw, Point size, int[] phase,
                                                        float black, float white) {
        Point tiles = new Point((size.x + MEAN_TILE - 1) / MEAN_TILE, (size.y + MEAN_TILE - 1) / MEAN_TILE);
        Point gridSize = new Point(tiles.x * 4, tiles.y * 4);
        GLTexture grid = new GLTexture(gridSize, new GLFormat(GLFormat.DataType.FLOAT_32, 4),
                null, GL_NEAREST, GL_CLAMP_TO_EDGE);
        try {
            glProg.useAssetProgram("remosaic/blockprofile");
            glProg.setTexture("RawBuffer",raw);
            glProg.setVar("rawWidth",size.x); glProg.setVar("rawHeight",size.y);
            glProg.setVar("blockSize",4); glProg.setVar("phase",phase[0],phase[1]);
            glProg.setVar("blackLevel",black); glProg.setVar("whiteLevel",white);
            glProg.setVar("tileSize",MEAN_TILE);
            glProg.drawBlocks(grid); glProg.closed=true;
            FloatBuffer f=grid.textureBuffer(new GLFormat(GLFormat.DataType.FLOAT_32,4))
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            TetraResponseProfile.GainMap map=TetraResponseProfile.estimateMap(f,gridSize.x,gridSize.y);
            Log.d(Name,"Tetra v2 spatial response quadrant support="+java.util.Arrays.toString(map.spatial));
            return map;
        } finally { grid.close(); }
    }

    private void maskStage(GLTexture raw, GLTexture green, GLTexture out, Point size, int stage,
                           int blockSize, int[] phase, int[] quad, float black, float white,
                           float gainB, float gainR) {
        glProg.useAssetProgram("remosaic/stages");
        glProg.setTexture("RawBuffer", raw);
        glProg.setTexture("GreenBuffer", green != null ? green : raw);
        setCommon(size, blockSize, phase, quad, black, white, gainB, gainR);
        glProg.setVar("stage", stage);
        glProg.drawBlocks(out);
        glProg.closed = true;
    }


    private void setCommon(Point size, int blockSize, int[] phase, int[] quad,
                           float black, float white, float gainB, float gainR) {
        glProg.setVar("rawWidth", size.x);
        glProg.setVar("rawHeight", size.y);
        glProg.setVar("blockSize", blockSize);
        glProg.setVar("phase", phase[0], phase[1]);
        glProg.setVar("quadColors", quad[0], quad[1], quad[2], quad[3]);
        glProg.setVar("blackLevel", black);
        glProg.setVar("whiteLevel", white);
        glProg.setVar("gainB", gainB);
        glProg.setVar("gainR", gainR);
        glProg.setVarFloats("blockGain", blockGain);
    }


    /** Separable blur, then the divide on the second axis. */
    private void maskBlur(GLTexture in, GLTexture tmp, GLTexture out, Point size, int kernelSize) {
        glProg.useAssetProgram("remosaic/maskblur");
        glProg.setTexture("InputBuffer", in);
        glProg.setVar("size", size.x, size.y);
        glProg.setVar("axis", 0);
        glProg.setVar("kernelSize", kernelSize);
        glProg.setVar("divide", 0);
        glProg.drawBlocks(tmp);
        glProg.closed = true;

        glProg.useAssetProgram("remosaic/maskblur");
        glProg.setTexture("InputBuffer", tmp);
        glProg.setVar("size", size.x, size.y);
        glProg.setVar("axis", 1);
        glProg.setVar("kernelSize", kernelSize);
        glProg.setVar("divide", 1);
        glProg.drawBlocks(out);
        glProg.closed = true;
    }


    /** Gradient-steered green, in place of the isotropic blur. */
    private void greenSteer(GLTexture in, GLTexture out, Point size, int reach) {
        glProg.useAssetProgram("remosaic/greensteer");
        glProg.setTexture("InputBuffer", in);
        glProg.setVar("size", size.x, size.y);
        glProg.setVar("reach", reach);
        glProg.setVar("steer", 8.0f);
        glProg.drawBlocks(out);
        glProg.closed = true;
    }


    /** Bound an interpolated difference by the measured ones around it. */
    private void clampDiff(GLTexture interp, GLTexture masked, GLTexture out,
                           Point size, int reach) {
        glProg.useAssetProgram("remosaic/clampdiff");
        glProg.setTexture("InterpBuffer", interp);
        glProg.setTexture("MaskedBuffer", masked);
        glProg.setVar("size", size.x, size.y);
        glProg.setVar("reach", reach);
        glProg.drawBlocks(out);
        glProg.closed = true;
    }


    private void median(GLTexture in, GLTexture out, Point size) {
        glProg.useAssetProgram("remosaic/median3");
        glProg.setTexture("InputBuffer", in);
        glProg.setVar("size", size.x, size.y);
        glProg.drawBlocks(out);
        glProg.closed = true;
    }


    /** Copy back, since median cannot read and write the same texture. */
    private void swapInto(GLTexture from, GLTexture to, Point size) {
        glProg.useAssetProgram("remosaic/copyfloat");
        glProg.setTexture("InputBuffer", from);
        glProg.drawBlocks(to);
        glProg.closed = true;
    }


    /**
     * CFA order as quadrant colours, 0=R 1=G 2=B.
     * Android's pattern values: 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR.
     */
    /** No correction: every site of every block weighs the same. */
    private static float[] flatProfile() {
        float[] g = new float[64];
        java.util.Arrays.fill(g, 1.f);
        return g;
    }


    /**
     * Measures the per-site response profile inside a colour block.
     *
     * <p>Averaged over every block of the frame the scene cancels and what
     * remains is the fixed pattern: crosstalk and microlens geometry, different
     * for each of the four quadrants. The gains returned divide it out, so the
     * interpolation downstream sees sites that really are interchangeable.
     *
     * <p>The reduction keeps the sample count equal across sub-positions within
     * a tile, so normalising each quadrant by its own mean needs no counts at
     * all - whatever a partial tile contributes, it contributes to all sites of
     * that quadrant alike.
     */
    private float[] measureBlockProfile(GLTexture raw, Point rawSize, int blockSize, int[] phase,
                                        float black, float white, boolean robust) {
        int sites = blockSize * blockSize;
        Point tiles = new Point(
                (rawSize.x + MEAN_TILE - 1) / MEAN_TILE,
                (rawSize.y + MEAN_TILE - 1) / MEAN_TILE);
        Point gridSize = new Point(tiles.x * blockSize, tiles.y * blockSize);
        GLTexture grid = new GLTexture(gridSize, new GLFormat(GLFormat.DataType.FLOAT_32, 4),
                null, GL_NEAREST, GL_CLAMP_TO_EDGE);
        try {
            glProg.useAssetProgram("remosaic/blockprofile");
            glProg.setTexture("RawBuffer", raw);
            glProg.setVar("rawWidth", rawSize.x);
            glProg.setVar("rawHeight", rawSize.y);
            glProg.setVar("blockSize", blockSize);
            glProg.setVar("phase", phase[0], phase[1]);
            glProg.setVar("blackLevel", black);
            glProg.setVar("whiteLevel", white);
            glProg.setVar("tileSize", MEAN_TILE);
            glProg.drawBlocks(grid);
            glProg.closed = true;

            ByteBuffer buf = grid.textureBuffer(new GLFormat(GLFormat.DataType.FLOAT_32, 4));
            FloatBuffer f = buf.order(ByteOrder.nativeOrder()).asFloatBuffer();
            if (robust) return TetraResponseProfile.estimate(f, gridSize.x, gridSize.y);
            double[][] sums = new double[4][sites];
            for (int y = 0; y < gridSize.y; y++) {
                int sy = y % blockSize;
                for (int x = 0; x < gridSize.x; x++) {
                    int sx = x % blockSize;
                    int site = sy * blockSize + sx;
                    int base = (y * gridSize.x + x) * 4;
                    for (int q = 0; q < 4; q++) sums[q][site] += f.get(base + q);
                }
            }

            float[] gains = flatProfile();
            float spread = 0.f;
            for (int q = 0; q < 4; q++) {
                double mean = 0;
                for (int k = 0; k < sites; k++) mean += sums[q][k];
                mean /= sites;
                // A frame too dark to measure carries no profile to divide out.
                if (mean <= 1e-6) return flatProfile();
                for (int k = 0; k < sites; k++) {
                    double g = mean / Math.max(sums[q][k], 1e-6);
                    // The real spread is a few percent; anything beyond this is
                    // a scene that defeated the averaging, not a sensor trait.
                    g = Math.min(Math.max(g, 0.75), 1.35);
                    gains[q * 16 + k] = (float) g;
                    spread = Math.max(spread, (float) Math.abs(g - 1.0));
                }
            }
            Log.d(Name, "block profile: max deviation=" + String.format("%.3f", spread)
                    + " gains(q0)=" + java.util.Arrays.toString(
                            java.util.Arrays.copyOfRange(gains, 0, sites)));
            return gains;
        } finally {
            grid.close();
        }
    }


    /**
     * The plain bayer layout the assembly writes, as a cfaPattern index.
     *
     * <p>The target grid is the same quadColors mapping laid on a 2x2, so what
     * leaves this node carries the layout the quadrants describe. Downstream
     * has to be told: it arrived holding -2, the quad bayer marker, and would
     * otherwise keep decoding an ordinary bayer frame as a mosaic - and
     * baseCfaPattern is no help, since it falls back to RGGB for any pattern
     * outside 0..3, which is exactly the -2 case.
     */
    private static int cfaPatternFor(int[] quad) {
        for (int p = 0; p <= 3; p++) {
            int[] q = quadColorsFor(p);
            if (q[0] == quad[0] && q[1] == quad[1] && q[2] == quad[2] && q[3] == quad[3]) return p;
        }
        return 3;
    }


    private static int[] quadColorsFor(int cfaPattern) {
        switch (cfaPattern) {
            case 0:  return new int[]{0, 1, 1, 2}; // RGGB
            case 1:  return new int[]{1, 0, 2, 1}; // GRBG
            case 2:  return new int[]{1, 2, 0, 1}; // GBRG
            default: return new int[]{2, 1, 1, 0}; // BGGR
        }
    }
}

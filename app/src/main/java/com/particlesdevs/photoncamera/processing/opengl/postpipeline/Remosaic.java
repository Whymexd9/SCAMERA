package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_NEAREST;
import static android.opengl.GLES20.GL_MIRRORED_REPEAT;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Turns a quad-bayer or tetra-squared mosaic into a plain 2x2 bayer frame.
 *
 * <p>The sensor delivers colour in blocks - 2x2 samples of one colour for quad
 * bayer, 4x4 for tetra squared. Feeding that to a demosaic written for a 2x2
 * pattern decodes every block at the wrong phase, which is the magenta cast the
 * telephoto showed at 4x ISZ. Rearranging the mosaic first means the rest of the
 * pipeline needs no knowledge of it at all: what leaves this node is ordinary
 * bayer.
 *
 * <p>Method follows the RemosaicApp reference implementation:
 * <ol>
 *   <li>align white balance, so interpolation does not drag colour across block
 *       edges - channels a stop apart would otherwise bleed into each other</li>
 *   <li>interpolate green over its own mask, normalising by the blurred mask so
 *       each estimate is the mean of the samples that actually exist nearby</li>
 *   <li>form B-G and R-G where those samples are, interpolate the differences,
 *       median them - differences are flat where the channels are not, so this
 *       costs no detail</li>
 *   <li>add green back, write on a plain bayer grid, undo the white balance</li>
 * </ol>
 *
 * <p>Runs before Bayer2Float and replaces the frame it reads.
 */
public class Remosaic extends Node {

    /** Reduction tile for the channel means; 32x32 keeps the grid small enough to read back. */
    private static final int MEAN_TILE = 32;

    /** Per-site gains inside a block, quadrant-major; all ones until measured. */
    private float[] blockGain = flatProfile();

    public Remosaic() {
        super("", "Remosaic");
    }

    @Override
    public void Compile() {
    }

    @Override
    public void Run() {
        PostPipeline pipeline = (PostPipeline) basePipeline;
        Point rawSize = basePipeline.mParameters.rawSize;

        int blockSize = PreferenceKeys.getRemosaicBlockSize();
        int profile = PreferenceKeys.getRemosaicProfile();
        // Profile picks how much the interpolation smooths. The widest kernel
        // gives the cleanest single frame; the narrowest keeps per-pixel noise
        // independent, which matters when a merge runs after this.
        int kernelSize;
        boolean useMedian;
        // Both defences against coloured fringes at edges. The reference
        // implementation had neither: it was written for a single raw frame,
        // where a wide isotropic kernel was needed to beat the noise. Here the
        // remosaic runs after the merge, so the noise is already down and the
        // smoothing only costs accuracy at edges.
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
        int[] quad = quadColorsFor(basePipeline.mParameters.cfaPattern);
        float black = averageBlackLevel();
        float white = (float) basePipeline.mParameters.whiteLevel;

        if (!PreferenceKeys.isRemosaicEnabled()) {
            WorkingTexture = previousNode.WorkingTexture;
            return;
        }
        Log.d(Name, "remosaic: block=" + blockSize + " profile=" + profile
                + " kernel=" + kernelSize + " median=" + useMedian
                + " steered=" + steered + " clampDiffs=" + clampDiffs
                + " flatField=" + flatField
                + " phase=" + phase[0] + "," + phase[1]
                + " cfa=" + basePipeline.mParameters.cfaPattern
                + " black=" + black + " white=" + white);

        GLTexture raw = new GLTexture(rawSize, new GLFormat(GLFormat.DataType.UNSIGNED_16),
                pipeline.stackFrame, GL_NEAREST, GL_MIRRORED_REPEAT);

        GLFormat rg = new GLFormat(GLFormat.DataType.FLOAT_16, 2);
        GLTexture masked = new GLTexture(rawSize, rg, null, GL_NEAREST, GL_CLAMP_TO_EDGE);
        GLTexture tmp = new GLTexture(rawSize, rg, null, GL_NEAREST, GL_CLAMP_TO_EDGE);
        GLTexture green = new GLTexture(rawSize, rg, null, GL_NEAREST, GL_CLAMP_TO_EDGE);
        GLTexture diffB = new GLTexture(rawSize, rg, null, GL_NEAREST, GL_CLAMP_TO_EDGE);
        GLTexture diffR = new GLTexture(rawSize, rg, null, GL_NEAREST, GL_CLAMP_TO_EDGE);

        try {
            float[] gains = measureGains(raw, rawSize, blockSize, phase, quad, black, white);
            float gainB = gains[0], gainR = gains[1];
            Log.d(Name, "white balance alignment: gainB=" + gainB + " gainR=" + gainR);
            // Green coverage tells whether the mask actually matched the mosaic:
            // a correct block size and phase put green on half the sites. Far
            // from 0.5 means the pattern assumed here is not the one in the frame,
            // and the interpolation below is working on the wrong sites.
            Log.d(Name, "green coverage=" + gains[2] + " (0.5 expected)");

            // The sites of a block are not interchangeable: each colour has its
            // own fixed response profile across the block, up to 12% on this
            // sensor. Measuring it over the whole frame averages the scene out
            // and leaves the pattern; without this the profile rides into the
            // green estimate and, with the opposite sign, into the differences.
            blockGain = flatProfile();
            if (flatField) {
                blockGain = measureBlockProfile(raw, rawSize, blockSize, phase, black, white);
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

            // Assemble straight into the stack frame the rest of the pipeline reads.
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
            GLTexture out = new GLTexture(rawSize, new GLFormat(GLFormat.DataType.UNSIGNED_16),
                    null, GL_NEAREST, GL_MIRRORED_REPEAT);
            glProg.drawBlocks(out);
            glProg.closed = true;

            // From here the frame is ordinary bayer: report the 2x2 pattern so
            // the demosaic and the DNG writer stop treating it as a mosaic.
            basePipeline.mSettings.cfaPattern = (byte) basePipeline.mParameters.cfaPattern;
            // Bayer2Float builds its input from stackFrame, so hand the result
            // over explicitly - WorkingTexture alone would be ignored.
            pipeline.remosaicOutput = out;
            WorkingTexture = out;
            raw.close();
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
            if (cntG <= 0) return new float[]{1.f, 1.f, 0.f};
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
            return new float[]{gb, gr, (float) (cntG / Math.max(total, 1))};
        } finally {
            grid.close();
        }
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
        glProg.useAssetProgram("remosaic/median3");
        glProg.setTexture("InputBuffer", from);
        glProg.setVar("size", size.x, size.y);
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
                                        float black, float white) {
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

    private static int[] quadColorsFor(int cfaPattern) {
        switch (cfaPattern) {
            case 0:  return new int[]{0, 1, 1, 2}; // RGGB
            case 1:  return new int[]{1, 0, 2, 1}; // GRBG
            case 2:  return new int[]{1, 2, 0, 1}; // GBRG
            default: return new int[]{2, 1, 1, 0}; // BGGR
        }
    }

    private float averageBlackLevel() {
        float[] bl = basePipeline.mParameters.blackLevel;
        if (bl == null || bl.length < 4) return 0.f;
        return (bl[0] + bl[1] + bl[2] + bl[3]) * 0.25f;
    }
}

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
        int kernelSize = blockSize == 2 ? 5 : 9;
        int[] phase = PreferenceKeys.getRemosaicPhase();
        int[] quad = quadColorsFor(basePipeline.mParameters.cfaPattern);
        float black = averageBlackLevel();
        float white = (float) basePipeline.mParameters.whiteLevel;

        if (!PreferenceKeys.isRemosaicEnabled()) {
            WorkingTexture = previousNode.WorkingTexture;
            return;
        }
        Log.d(Name, "remosaic: block=" + blockSize + " kernel=" + kernelSize
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

            // Green first: the differences below are taken against it.
            maskStage(raw, null, masked, rawSize, 0, blockSize, phase, quad, black, white, 1.f, 1.f);
            maskBlur(masked, tmp, green, rawSize, kernelSize);

            maskStage(raw, green, masked, rawSize, 1, blockSize, phase, quad, black, white, gainB, gainR);
            maskBlur(masked, tmp, diffB, rawSize, kernelSize);
            median(diffB, tmp, rawSize);
            swapInto(tmp, diffB, rawSize);

            maskStage(raw, green, masked, rawSize, 2, blockSize, phase, quad, black, white, gainB, gainR);
            maskBlur(masked, tmp, diffR, rawSize, kernelSize);
            median(diffR, tmp, rawSize);
            swapInto(tmp, diffR, rawSize);

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

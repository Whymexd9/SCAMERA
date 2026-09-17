package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static android.opengl.GLES20.GL_NEAREST;
import static android.opengl.GLES20.GL_MIRRORED_REPEAT;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.ImagePath;
import com.particlesdevs.photoncamera.processing.ImageSaver;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;
import java.nio.file.Path;

/**
 * Turns a quad-bayer or tetra-squared mosaic into a plain bayer frame, for the
 * captures the merge did not already handle.
 *
 * <p>With several frames the remosaic runs inside the merge instead, once per
 * frame as each is uploaded: the merge packs one bayer quad per texel and every
 * displacement finer than a colour block would otherwise cross between blocks
 * of different colours. A single frame never reaches the merge, so it is
 * rearranged here, and {@link com.particlesdevs.photoncamera.processing.render.Parameters#remosaicDone}
 * says which of the two happened.
 *
 * <p>The reconstruction itself lives in {@link RemosaicCore}.
 */
public class Remosaic extends Node {

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

        if (!PreferenceKeys.isRemosaicEnabled() || basePipeline.mParameters.remosaicDone) {
            if (basePipeline.mParameters.remosaicDone) {
                Log.d(Name, "already rearranged before the merge, nothing to do here");
                basePipeline.remosaicApplied = true;
            }
            // First in the pipeline there is no previous node to inherit from,
            // so hand on the stack frame itself rather than a null texture.
            WorkingTexture = previousNode != null
                    ? previousNode.WorkingTexture
                    : new GLTexture(rawSize, new GLFormat(GLFormat.DataType.UNSIGNED_16),
                            pipeline.stackFrame, GL_NEAREST, GL_MIRRORED_REPEAT);
            return;
        }

        float black = averageBlackLevel();
        float white = (float) basePipeline.mParameters.whiteLevel;
        GLTexture raw = new GLTexture(rawSize, new GLFormat(GLFormat.DataType.UNSIGNED_16),
                pipeline.stackFrame, GL_NEAREST, GL_MIRRORED_REPEAT);
        GLTexture out;
        try {
            out = new RemosaicCore(glProg).run(raw, rawSize,
                    basePipeline.mParameters.cfaPattern, black, white, true);
        } finally {
            raw.close();
        }

        // From here the frame is ordinary bayer: report the 2x2 pattern the
        // assembly wrote, so nothing downstream keeps decoding it as a mosaic,
        // and clear the flag the DNG writer keys its quad metadata off.
        basePipeline.mParameters.cfaPattern = (byte) RemosaicCore.emittedCfaPattern(
                basePipeline.mParameters.cfaPattern);
        basePipeline.mParameters.quadCfa = false;
        basePipeline.mParameters.remosaicDone = true;
        basePipeline.remosaicApplied = true;

        // A dump of exactly what the node produced, for comparing the device
        // against a model of the same chain.
        if (PreferenceKeys.isRemosaicDump()) {
            try {
                ByteBuffer dump = out.textureBuffer(new GLFormat(GLFormat.DataType.UNSIGNED_16));
                Path dumpPath = ImagePath.newDNGFilePath();
                boolean saved = ImageSaver.Util.saveSingleRaw(
                        dumpPath, dump, basePipeline.mParameters);
                Log.d(Name, "remosaic dump " + (saved ? "saved: " : "failed: ") + dumpPath);
            } catch (Exception t) {
                Log.e(Name, "remosaic dump failed: " + Log.getStackTraceString(t));
            }
        }

        // Bayer2Float builds its input from stackFrame, so hand the result over
        // explicitly - WorkingTexture alone would be ignored.
        pipeline.remosaicOutput = out;
        WorkingTexture = out;
    }

    private float averageBlackLevel() {
        float[] bl = basePipeline.mParameters.blackLevel;
        if (bl == null || bl.length < 4) return 0.f;
        return (bl[0] + bl[1] + bl[2] + bl[3]) * 0.25f;
    }
}

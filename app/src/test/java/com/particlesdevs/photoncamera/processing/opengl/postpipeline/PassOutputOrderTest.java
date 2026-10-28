package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A GLTexture binds itself on unit 1 + its name when it is made (GLTexture's constructors), and a main released above
 * 100 MP or with early release (GLBasePipeline.releaseMain, ScamRgb's lazy main2) is made again by getMain(). FilmHalation's
 * halation pass, ScamLocalContrast's apply pass and ScamHdrDenoise's apply pass (its NLM path) took their output after their
 * samplers were bound on units 0, 2, 4...: an odd new name took a sampler's unit and the pass read its own output. As in
 * WhyCombine (WhyCombineTextureOrderTest), the output is taken before the program, and no texture is made between the program
 * and the draw.
 */
public class PassOutputOrderTest {
    private static final String SRC = "src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/";

    private static String read(String name) throws IOException {
        File f = new File(SRC + name);
        if (!f.isFile()) f = new File("app/" + SRC + name);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static void outputBeforeSamplers(String file, String method, String program, String take) throws IOException {
        final String run = WhyCombineUniformParityTest.method(read(file), method);
        final int at = run.indexOf("glProg.useAssetProgram(\"" + program + "\"");
        final int draw = run.indexOf("glProg.drawBlocks(WorkingTexture)", at);
        assertTrue(file + ": the " + program + " program and its draw", at >= 0 && draw > at);
        final String bound = run.substring(at, draw);
        assertFalse(file + ": a texture made after the " + program + " samplers are bound", bound.contains("new GLTexture("));
        assertFalse(file + ": the output made after the " + program + " samplers are bound", bound.contains("getMain("));
        assertTrue(file + ": the draw writes the output taken before " + program, bound.contains("WorkingTexture = out;"));
        final int main = run.lastIndexOf("final GLTexture out = " + take + ".getMain();", at);
        assertTrue(file + ": the output taken before the " + program + " program", main >= 0);
        assertFalse(file + ": a program between the output and " + program,
                run.substring(main, at).contains("useAssetProgram("));
    }

    @Test
    public void halationOutputMadeBeforeItsSamplers() throws IOException {
        outputBeforeSamplers("FilmHalation.java", "public void Run()", "film/halation", "basePipeline");
    }

    @Test
    public void localContrastOutputMadeBeforeItsSamplers() throws IOException {
        outputBeforeSamplers("ScamLocalContrast.java", "public void Run()", "scamlc/apply", "pipeline");
    }

    @Test
    public void hdrDenoiseOutputMadeBeforeItsSamplers() throws IOException {
        outputBeforeSamplers("ScamHdrDenoise.java", "void runNlm()", "chromadn/apply", "pipeline");
    }
}

package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A GLTexture binds itself on unit 1 + its name when it is made (GLTexture's constructors). WhyCombine made its output
 * (pipeline.getMain(), lazily above 100 MP or with early release) after the combine's samplers were bound on units 0, 2, 4...:
 * an odd new name took a sampler's unit, and the combine pass read its own output. No texture is made between the combine
 * program and its draw.
 */
public class WhyCombineTextureOrderTest {
    private static String read(String path) throws IOException {
        File f = new File(path);
        if (!f.isFile()) f = new File("app/" + path);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void outputMadeBeforeTheCombineSamplersAreBound() throws IOException {
        final String run = WhyCombineUniformParityTest.method(
                read("src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/WhyCombine.java"), "public void Run()");
        final int program = run.indexOf("glProg.useAssetProgram(\"why/combine\"");
        final int draw = run.indexOf("glProg.drawBlocks(WorkingTexture)", program);
        assertTrue("the combine program and its draw", program >= 0 && draw > program);
        final String bound = run.substring(program, draw);
        assertFalse("a texture made after the samplers are bound", bound.contains("new GLTexture("));
        assertFalse("the output made after the samplers are bound", bound.contains("getMain("));
        assertFalse("detailRef makes a texture", bound.contains("detailRef("));
        final int main = run.lastIndexOf("WorkingTexture = pipeline.getMain();", program);
        assertTrue("the output made before the combine program", main >= 0);
    }
}

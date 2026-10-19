package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** P289: the lens filter node's Gaussian passes - every uniform the node sets is declared in one of its shaders. */
public class LensFilterNodeTest {
    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }

    @Test public void everyUniformTheNodeSetsIsDeclared() throws Exception {
        String node = read("src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/LensFilter.java");
        StringBuilder shaders = new StringBuilder();
        for (String s : new String[]{"blur", "up", "down"})
            shaders.append(read("src/main/assets/shaders/lensgauss/" + s + ".glsl"));
        Matcher m = Pattern.compile("glProg\\.set(?:Var|Texture)\\(\"(\\w+)\"").matcher(node);
        int n = 0;
        while (m.find()) {
            n++;
            assertTrue("uniform " + m.group(1), Pattern.compile("uniform\\s+\\w+\\s+" + m.group(1) + "\\s*;").matcher(shaders).find());
        }
        assertTrue(n > 10);
        assertTrue("fragment passes only (the compute FFT is gone)", !node.contains("glDispatchCompute"));
    }
}

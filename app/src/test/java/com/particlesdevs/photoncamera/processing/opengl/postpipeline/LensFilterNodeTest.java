package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** P228 fixes (owner, 2026-10-18): the photo's turn as RotateWatermark makes it, the frame's level for the knee and the lift. */
public class LensFilterNodeTest {
    @Test public void theTurnIsRotateWatermarks() {
        assertEquals(0, LensFilter.turn(0));
        assertEquals(3, LensFilter.turn(90));
        assertEquals(2, LensFilter.turn(180));
        assertEquals(1, LensFilter.turn(270));
        assertEquals(1, LensFilter.turn(-90));
    }

    private static FloatBuffer grey(float level, int n) {
        FloatBuffer b = FloatBuffer.allocate(n * 4);
        for (int i = 0; i < n; i++) b.put(level).put(level).put(level).put(1f);
        b.rewind();
        return b;
    }

    @Test public void theLevelBringsTheFrameToMiddleGrey() {
        assertEquals("a frame at 0.045: two stops up", 4f, LensFilter.exposure(grey(0.045f, 64), 64), 1e-3f);
        assertEquals("already at middle grey", 1f, LensFilter.exposure(grey(0.18f, 64), 64), 1e-3f);
        assertEquals("clamped at 32", 32f, LensFilter.exposure(grey(0.0005f, 64), 64), 1e-3f);
        assertEquals("clamped at 1/4", 0.25f, LensFilter.exposure(grey(5f, 64), 64), 1e-3f);
        assertEquals("nothing: 1", 1f, LensFilter.exposure(FloatBuffer.allocate(0), 0), 0f);
        // the log average: half at 0.09, half at 0.36 -> 0.18
        FloatBuffer mixed = FloatBuffer.allocate(8 * 4);
        for (int i = 0; i < 8; i++) {
            float v = i < 4 ? 0.09f : 0.36f;
            mixed.put(v).put(v).put(v).put(1f);
        }
        mixed.rewind();
        assertEquals(1f, LensFilter.exposure(mixed, 8), 1e-3f);
        // a dark room with a lit screen (OPPO, 2026-10-18: level x28, the whole screen glowed): the screen goes to white, no further
        FloatBuffer room = FloatBuffer.allocate(1000 * 4);
        for (int i = 0; i < 1000; i++) {
            float v = i < 900 ? 0.002f : 0.3f;
            room.put(v).put(v).put(v).put(1f);
        }
        room.rewind();
        assertEquals("the screen at white", 1f / 0.3f, LensFilter.exposure(room, 1000), 1e-3f);
        // a few sources (under half a percent) do not hold an ordinary frame down
        FloatBuffer lamps = FloatBuffer.allocate(1000 * 4);
        for (int i = 0; i < 1000; i++) {
            float v = i < 3 ? 20f : 0.045f;
            lamps.put(v).put(v).put(v).put(1f);
        }
        lamps.rewind();
        assertEquals(4f, LensFilter.exposure(lamps, 1000), 0.1f);
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }

    /**
     * Owner, 2026-10-18 (photos with a filter not saved: «'KNEE' : undeclared identifier»): GLInterface only rewrites a
     * {@code #define} line that the shader already has, so every define the node sets must be declared in the shader.
     */
    @Test public void everyDefineTheNodeSetsIsDeclaredInItsShader() throws Exception {
        String node = read("src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/LensFilter.java");
        String apply = read("src/main/assets/shaders/lensfilter/apply.glsl");
        Matcher m = Pattern.compile("setDefine\\(\"(\\w+)\"").matcher(node);
        int n = 0;
        while (m.find()) {
            n++;
            assertTrue("#define " + m.group(1) + " in apply.glsl", apply.contains("#define " + m.group(1) + " "));
        }
        assertTrue(n > 0);
        // the star's knee is a uniform of the highlight pass the node sets (the diffusion has none: all the light scatters)
        assertTrue(read("src/main/assets/shaders/lensfilter/extract.glsl").contains("uniform float knee;"));
        assertTrue("the diffusion: the image's weight and its levels' sum", apply.contains("c = w0 * c + bspline(DiffAcc, uv);"));
        assertTrue(node.contains("glProg.setVar(\"knee\", knee)"));
    }
}

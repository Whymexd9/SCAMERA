package com.particlesdevs.photoncamera.processing.opengl;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * P317: GLProg binds a compute shader's buffers and images by the bindings GLInterface.getLayouts reads from its source. A
 * block declared on one line ("layout(std430, binding = 2) buffer aeHistY { uint histY[]; };", ark/aestats and
 * ultrahdr/gainmax) was keyed "}", so setBufferCompute bound nothing: the GPU AE histograms of every Mali shot came back
 * 0 / 0 (vivo X300 Pro log 2026-10-21) and only the GLBuffer's bind at index = its GL name hid it on Adreno.
 */
public class GLInterfaceLayoutsTest {
    private static final String SHADERS = "src/main/assets/shaders";

    private static File src(String path) {
        File f = new File(path);
        return f.exists() ? f : new File("app/" + path);
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static List<File> shaders(File dir) {
        List<File> out = new ArrayList<>();
        File[] files = dir.listFiles();
        assertNotNull(dir.toString(), files);
        for (File f : files) {
            if (f.isDirectory()) out.addAll(shaders(f));
            else if (f.getName().endsWith(".glsl")) out.add(f);
        }
        return out;
    }

    /** The source as GLProg.setLayout hands it to getLayouts (the LAYOUT define replaced). */
    private static String withLayout(String source) {
        return source.replace("#define LAYOUT //", "#define LAYOUT layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;");
    }

    private static final Pattern BLOCK = Pattern.compile(
            "layout\\s*\\(([^)]*)\\)\\s*(?:(?:readonly|writeonly|coherent|restrict|volatile)\\s+)*buffer\\s+(\\w+)");
    private static final Pattern IMAGE = Pattern.compile(
            "layout\\s*\\(([^)]*)\\)\\s*(?:(?:readonly|writeonly|coherent|restrict|volatile|uniform|highp|mediump|lowp)\\s+)*"
                    + "[iu]?image\\w+\\s+(\\w+)\\s*;");
    private static final Pattern BINDING = Pattern.compile("binding\\s*=\\s*(\\d+)");

    @Test public void everyBufferAndImageOfEveryShaderHasItsBinding() throws IOException {
        int blocks = 0;
        for (File f : shaders(src(SHADERS))) {
            final String source = withLayout(read(f));
            final Map<String, GLComputeLayout> layouts = GLInterface.getLayouts(source);
            assertFalse(f + ": a block keyed by its closing brace " + layouts.keySet(), layouts.containsKey("}"));
            for (Pattern p : new Pattern[]{BLOCK, IMAGE}) {
                Matcher m = p.matcher(source);
                while (m.find()) {
                    Matcher b = BINDING.matcher(m.group(1));
                    if (!b.find()) continue;
                    GLComputeLayout l = layouts.get(m.group(2));
                    assertNotNull(f + ": " + m.group(2) + " missing from " + layouts.keySet(), l);
                    assertEquals(f + ": " + m.group(2), Integer.parseInt(b.group(1)), l.binding);
                    blocks++;
                }
            }
        }
        // aestats 5, gainmax 1, halfrgba 2, histogram 4, align 1
        assertTrue("blocks and images found: " + blocks, blocks >= 13);
    }

    @Test public void theOneLineBlocksOfTheGpuAeAndGainMapResolve() throws IOException {
        Map<String, GLComputeLayout> ae = GLInterface.getLayouts(withLayout(read(src(SHADERS + "/ark/aestats.glsl"))));
        String[] names = {"aeMax", "aeHistY", "aeHistM", "aeSums", "aeTail"};
        for (int i = 0; i < names.length; i++) {
            assertNotNull(names[i], ae.get(names[i]));
            assertEquals(names[i], i + 1, ae.get(names[i]).binding);
        }
        assertNotNull("local size", ae.get("in"));
        assertEquals(1, ae.get("in").z);
        Map<String, GLComputeLayout> gain = GLInterface.getLayouts(withLayout(read(src(SHADERS + "/ultrahdr/gainmax.glsl"))));
        assertEquals(1, gain.get("gainMax").binding);
        Map<String, GLComputeLayout> half = GLInterface.getLayouts(withLayout(read(src(SHADERS + "/scamhdr/halfrgba.glsl"))));
        assertEquals(1, half.get("halfRgb").binding);
        assertEquals(0, half.get("outImage").binding);
    }

    @Test public void multiLineBlocksAndImagesKeepTheirKeys() throws IOException {
        Map<String, GLComputeLayout> hist = GLInterface.getLayouts(withLayout(read(src(SHADERS + "/GLHistogram/histogram.glsl"))));
        String[] names = {"histogramRed", "histogramGreen", "histogramBlue", "histogramAlpha"};
        for (int i = 0; i < names.length; i++) assertEquals(names[i], i + 1, hist.get(names[i]).binding);
        assertNotNull("local size", hist.get("in"));
        // the forms the parser met before P317 give the keys of before
        Map<String, GLComputeLayout> l = GLInterface.getLayouts("layout(rgba16f, binding = 0) uniform highp writeonly image2D outTexture;\n"
                + "layout(std430, binding = 3) buffer   spaced   \n{\n uint a[];\n};\n"
                + "layout(std430, binding = 4) readonly buffer tabbed\t{ uint b[]; };   // a comment { }\n"
                + "#define LAYOUT layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;\n");
        assertEquals(0, l.get("outTexture").binding);
        assertEquals(3, l.get("spaced").binding);
        assertEquals(4, l.get("tabbed").binding);
        assertEquals(1, l.get("in").z);
        assertEquals(4, l.size());
    }

    @Test public void filmGrainHashesAreHighpInt() throws IOException {
        // P317: a fragment shader's ints default to mediump (fp16 on Mali): the grain's 32-bit hash and its / 65536.0 made
        // the noise +inf and every film photo white on the X300 Pro. Every asset shader that hashes with uints keeps them highp.
        int hashed = 0;
        for (File f : shaders(src(SHADERS))) {
            final String s = read(f);
            if (!s.contains("uint hash(")) continue;
            hashed++;
            assertTrue(f + " hashes uints without precision highp int", Pattern.compile("(?m)^\\s*precision\\s+highp\\s+int\\s*;").matcher(s).find());
        }
        assertTrue("film/noise and film/grain", hashed >= 2);
        for (String film : new String[]{"film/noise.glsl", "film/grain.glsl", "film/halation.glsl"}) {
            assertTrue(film, read(src(SHADERS + "/" + film)).contains("\nprecision highp int;"));
        }
        // a broken noise never whitens the photo
        assertTrue(read(src(SHADERS + "/film/grain_sf.glsl")).contains("if (any(isnan(n)) || any(isinf(n))) n = vec3(0.0);"));
    }

    @Test public void noBufferIsBoundAtItsOwnName() throws IOException {
        // a GL buffer name is not a binding index; every SSBO is bound by setBufferCompute at the shader's binding
        final String buffer = read(src("src/main/java/com/particlesdevs/photoncamera/processing/opengl/GLBuffer.java"));
        assertFalse(buffer.contains("glBindBufferBase(GL_SHADER_STORAGE_BUFFER, mBufferID, mBufferID)"));
        final String prog = read(src("src/main/java/com/particlesdevs/photoncamera/processing/opengl/GLProg.java"));
        assertFalse("a missing block must not pass silently", prog.contains("new Exception(\"Wrong computeLayout:\"+var).printStackTrace()"));
    }
}

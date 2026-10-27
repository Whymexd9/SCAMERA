package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P365 (owner, 2026-10-27: «В арк камере тоже есть живой видоискатель, сделай его такимже по требованиям как там»): the fused WHY
 * pass's program - its vertex shader (the RAW viewfinder's, as GLSL ES 3.10 like why/combine), its defines, and the uniforms
 * LiveRawRenderer.bindFrame sets, declared by utils/import_vfraw.glsl (tools/check_vf_fused.py renders the program itself).
 */
public class LiveWhyToneFusedTest {
    private static String asset(String path) throws IOException {
        File f = new File("src/main/assets/shaders/" + path);
        if (!f.isFile()) f = new File("app/src/main/assets/shaders/" + path);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void theFusedVertexShaderIsTheRawViewfindersAs310() throws IOException {
        final String vs = asset("preview/rawdevelop_vs.glsl");
        final String fused = LiveWhyTone.fusedVertexShader(vs);
        assertTrue(fused.startsWith("#version 310 es\n"));
        assertEquals("only the version line changes", vs.substring(vs.indexOf('\n')), fused.substring(fused.indexOf('\n')));
        assertTrue("texCoord and the attributes at locations 0 and 1", fused.contains("out vec2 texCoord;")
                && fused.contains("layout(location = 0) in vec2 vPosition;") && fused.contains("layout(location = 1) in vec2 vTexCoord;"));
        assertEquals("a 3.10 source stays as it is", "#version 310 es\nvoid main() {}", LiveWhyTone.fusedVertexShader("#version 310 es\nvoid main() {}"));
    }

    @Test
    public void onlyRawSourceOneIsTheFusedPass() {
        assertTrue(LiveWhyTone.rawSource(new String[][]{{"POINT_FUSED", "1"}, {"RAW_SOURCE", "1"}, {"FILM_LUT", "1"}}));
        assertFalse(LiveWhyTone.rawSource(new String[][]{{"POINT_FUSED", "1"}}));
        assertFalse(LiveWhyTone.rawSource(new String[][]{{"RAW_SOURCE", "0"}}));
        assertFalse(LiveWhyTone.rawSource(null));
    }

    @Test
    public void theImportDeclaresEveryUniformTheRendererSets() throws IOException {
        final String vfraw = asset("utils/import_vfraw.glsl");
        // LiveRawRenderer.bindFrame: the RAW uniforms of rawdevelop_fs's linear frame (texRotate is the vertex shader's)
        final String[] set = {"sTexture16", "u_lsc_map", "width", "height", "bayer_pattern", "cfa_mode", "cfa_block_size", "crop_left",
                "crop_top", "crop_width", "crop_height", "u_black_level", "u_white_level", "r_gain", "g_gain", "b_gain", "color_transform",
                "u_lsc_intensity", "mirror", "u_ldc_enabled"};
        for (String name : set)
            assertTrue(name, Pattern.compile("(?m)^uniform \\w+ " + name + ";").matcher(vfraw).find());
        assertTrue(asset("preview/rawdevelop_vs.glsl").contains("uniform mat4 texRotate;"));
        // why/combine RAW_SOURCE 1: the import, texCoord and the viewport (LiveWhyTone.drawFused)
        final String combine = asset("why/combine.glsl");
        assertTrue(combine.contains("\n#define RAW_SOURCE 0\n"));
        final Matcher block = Pattern.compile("#if RAW_SOURCE == 1\n#import vfraw\nin vec2 texCoord;[^\n]*\nuniform ivec2 vpOriginU;[^\n]*\n"
                + "uniform ivec2 vpSizeU;[^\n]*\n#endif\n").matcher(combine);
        assertTrue(block.find());
        // an import carries no version (GLInterface.readProgram gives the program GLProg's)
        assertFalse(vfraw.contains("#version"));
    }
}

package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * P214e R1: ArkLumaSharpen sharpens a mosaic shot (the worker merged a Quad / Tetra stream on its mosaic) on the 3x3-smoothed
 * luma, Ya + S(B(Ya)) - B(Ya) (ark/sharp_r1.glsl). A plain Bayer shot - block 1, a mosaic binned into plain Bayer, a block nobody
 * measured, every shot that is not a hybrid merge - keeps the passes of before: the gate is false, no scam_dev line is read,
 * the sharp_r1 program is only drawn inside the R1 branches and every other pass reads ya as before. The numbers (the shader
 * against numpy, the lattice and the detail response) are in tools/check_ark_sharpen.py.
 */
public class ArkSharpenR1Test {
    private static File src(String path) {
        File f = new File(path);
        return f.exists() ? f : new File("app/" + path);
    }

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(src(path).toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static final String SHARPEN = "src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/ArkLumaSharpen.java";
    private static final String SHADER = "src/main/assets/shaders/ark/sharp_r1.glsl";

    @Test public void bayerShotsKeepTheirSharpening() throws IOException {
        // block 1 (and 0 / anything but 2 or 4) is false whatever the switch says; the one-argument form never reads the
        // scam_dev file for it (PreferenceKeys is not touched: this runs without an app context)
        for (int block : new int[]{-1, 0, 1, 3, 8}) {
            assertFalse("block " + block, ArkLumaSharpen.smoothedSource(block, true));
            assertFalse("block " + block, ArkLumaSharpen.smoothedSource(block, false));
        }
        assertFalse(ArkLumaSharpen.smoothedSource(1));
        assertFalse(ArkLumaSharpen.smoothedSource(0));
        // every shot that is not a hybrid merge is Bayer (Parameters needs the app to be built; its declaration is read)
        assertTrue(read("src/main/java/com/particlesdevs/photoncamera/processing/render/Parameters.java").contains("public int mosaicBlock = 1;"));
        assertTrue(read("src/main/java/com/particlesdevs/photoncamera/processing/render/Parameters.java").contains("params.mosaicBlock = mosaicBlock;"));
    }

    @Test public void mosaicShotsTakeR1UnlessSwitchedOff() {
        assertTrue("Quad", ArkLumaSharpen.smoothedSource(2, true));
        assertTrue("Tetra", ArkLumaSharpen.smoothedSource(4, true));
        assertFalse("scam_dev sharpen_r1 0", ArkLumaSharpen.smoothedSource(2, false));
        assertFalse("scam_dev sharpen_r1 0", ArkLumaSharpen.smoothedSource(4, false));
    }

    @Test public void aGridAbove100MpKeepsThePassesOfBefore() {
        // P214z: D = Ya - B is one full R16F plane more through the RL stages; 200 MP (16320x12288) keeps the old peak.
        // Above the limit the answer is false before the scam_dev file is read (no app context here).
        assertTrue("50 MP", ArkLumaSharpen.r1Fits(8160, 6144));
        assertTrue("100 MP exactly", ArkLumaSharpen.r1Fits(10000, 10000));
        assertFalse("100 MP + 1 row", ArkLumaSharpen.r1Fits(10000, 10001));
        assertFalse("200 MP", ArkLumaSharpen.r1Fits(16320, 12288));
        assertFalse("int overflow", ArkLumaSharpen.r1Fits(65536, 65536));
        for (int block : new int[]{1, 2, 4}) assertFalse("200 MP block " + block, ArkLumaSharpen.smoothedSource(block, 16320, 12288));
        assertFalse("a Bayer shot of 50 MP", ArkLumaSharpen.smoothedSource(1, 8160, 6144));
        assertTrue(ArkLumaSharpen.HUGE_GF_PIXELS == 100_000_000L);
    }

    // the worker's lines as scam-hybrid.h prints them (hybridReconstructMosaicNative, hybridReconstructMosaic,
    // hybridReconstructMosaicBinned, hybridMosaicBlock)
    private static final String NATIVE_QUAD = "HYBRID MOSAIC NATIVE: block 2, 8 of 10 frames (one GPU slot each, binned 2048x1536, merged mosaic"
            + " 4096x3072 block 2), window 3 px full, kernel scale 1.000, edge scale 1.000, ks G 0.700 R/B 0.700, R/B fill 0.50: 0N 1N 2N 3N\n";
    private static final String NATIVE_TETRA = "HYBRID MOSAIC NATIVE: block 4 (Tetra T1: native), 6 of 8 frames (one GPU slot each, binned 1020x768,"
            + " merged mosaic 4080x3072 block 4), window 4 px full, kernel scale 1.000, edge scale 1.000, ks G 0.700 R/B 0.700, R/B fill 0.50: 0N 1N\n";
    private static final String SPLIT = "HYBRID MOSAIC: block 2, 4 plain-Bayer sub-frames 2048x1536 per frame, 6 of 6 frames (GPU holds 64 sub-frames): 0N 1N\n";
    private static final String BINNED = "HYBRID MOSAIC BINNED: block 2 without remosaic (mosaicRemosaic 0): 8 frames\n";
    private static final String MEASURED = "HYBRID MOSAIC: header block 0, measured block 2 (confident; tile votes b1/b2/b4 3/120/1 of 130 tiles)\n";

    @Test public void theBlockIsTheOneTheWorkerMergedWith() {
        assertEquals("a Bayer merge", 1, ScamHybridBurst.mergedBlock(1, "HYBRID GPU: merge 10 frames\n"));
        assertEquals("a Bayer stream, no report (CPU tier)", 1, ScamHybridBurst.mergedBlock(1, null));
        assertEquals("native Quad (ISZ 2x, FULLRES 50 MP)", 2, ScamHybridBurst.mergedBlock(2, NATIVE_QUAD));
        assertEquals("native Tetra (ISZ 4x, 200 MP)", 4, ScamHybridBurst.mergedBlock(4, NATIVE_TETRA));
        assertEquals("the sub-frame split", 2, ScamHybridBurst.mergedBlock(2, SPLIT));
        assertEquals("native failed, then the split", 2, ScamHybridBurst.mergedBlock(2,
                NATIVE_QUAD + "HYBRID MOSAIC NATIVE: failed (out of memory); merging on the sub-frame split (mosaicPath 0) instead\n" + SPLIT));
        // binned into plain Bayer by the worker (its line)
        assertEquals(1, ScamHybridBurst.mergedBlock(2, "HYBRID MOSAIC: block 2 forced by tuning\n" + BINNED));
        // the app had no confident block: the worker's merge says which
        assertEquals(2, ScamHybridBurst.mergedBlock(0, MEASURED + SPLIT));
        assertEquals("measured but no mosaic merge line", 1, ScamHybridBurst.mergedBlock(0, MEASURED));
        assertEquals("unknown, no report", 1, ScamHybridBurst.mergedBlock(0, null));
        // a report without the head lines (CPU tier after a failed worker): the block the app sent
        assertEquals(2, ScamHybridBurst.mergedBlock(2, "WORKER EXIT: signal 9\n"));
        assertEquals(4, ScamHybridBurst.mergedBlock(4, null));
    }

    /** Text ranges of every "if (r1) {" block of ArkLumaSharpen (brace counting). */
    private static List<int[]> r1Blocks(String java) {
        List<int[]> out = new ArrayList<>();
        int at = 0;
        while ((at = java.indexOf("if (r1) {", at)) >= 0) {
            int open = java.indexOf('{', at), depth = 0, i = open;
            for (; i < java.length(); i++) {
                char c = java.charAt(i);
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) break;
            }
            out.add(new int[]{open, i});
            at = i;
        }
        return out;
    }

    @Test public void theSmoothedPassesRunOnlyOnAMosaicShot() throws IOException {
        final String java = read(SHARPEN);
        final List<int[]> blocks = r1Blocks(java);
        assertEquals("the source / residual branch and the last pass", 2, blocks.size());
        int uses = 0;
        Matcher m = Pattern.compile("useAssetProgram\\(\"ark/sharp_r1\"").matcher(java);
        while (m.find()) {
            boolean inside = false;
            for (int[] b : blocks) inside |= m.start() > b[0] && m.start() < b[1];
            assertTrue("sharp_r1 drawn outside the R1 branches at " + m.start(), inside);
            uses++;
        }
        assertEquals("B, D = Ya - B, S + D", 3, uses);
        // without R1 the passes read ya itself (the source is ya), and it is closed after the compose as before
        assertTrue(java.contains("GLTexture src = ya, residual = null;"));
        assertTrue(java.contains("final boolean r1 = smoothedSource(block, size.x, size.y);"));
        assertTrue(java.contains("src.close();"));
        // ya is closed by src.close() without R1, by the R1 branch (once its residual is drawn) otherwise
        Matcher close = Pattern.compile("\\bya\\.close\\(\\)").matcher(java);
        while (close.find()) {
            boolean inside = false;
            for (int[] b : blocks) inside |= close.start() > b[0] && close.start() < b[1];
            assertTrue("ya closed outside the R1 branch", inside);
        }
    }

    /**
     * A uniform name the host sets that the shader does not declare is silently ignored by GL; both R1 blocks set only the
     * shader's uniforms, and together all of them.
     */
    @Test public void hostSetsTheShadersUniforms() throws IOException {
        final String java = read(SHARPEN), glsl = read(SHADER);
        Set<String> declared = new TreeSet<>();
        Matcher m = Pattern.compile("(?m)^uniform\\s+\\w+\\s+([A-Za-z0-9_]+)\\s*;").matcher(glsl);
        while (m.find()) declared.add(m.group(1));
        Set<String> set = new TreeSet<>();
        for (int[] b : r1Blocks(java)) {
            Matcher u = Pattern.compile("set(?:Var|Texture)\\(\"([A-Za-z0-9_]+)\"").matcher(java.substring(b[0], b[1]));
            while (u.find()) {
                assertTrue(u.group(1), declared.contains(u.group(1)));
                set.add(u.group(1));
            }
        }
        assertEquals(declared, set);
        for (String u : new String[]{"InputBuffer", "Smoothed", "Sharpened", "Residual", "modeU"}) assertTrue(u, set.contains(u));
        // B = [1 2 1]^T [1 2 1] / 16; the residual is Ya - B of the stored B; the result S(B) + (Ya - B)
        assertTrue(glsl.contains("(dx == 0 ? 2.0 : 1.0)") && glsl.contains("(dy == 0 ? 2.0 : 1.0)") && glsl.contains("s * 0.0625"));
        assertTrue(glsl.contains("texelFetch(InputBuffer, xy, 0).r - texelFetch(Smoothed, xy, 0).r"));
        assertTrue(glsl.contains("max(texelFetch(Sharpened, xy, 0).r + texelFetch(Residual, xy, 0).r, 0.0)"));
        // the passes in the order the shader's modes need them: B, then D (Ya goes), the chain on B, then S + D
        final int b = java.indexOf("glProg.setVar(\"modeU\", 0);\n            glProg.drawBlocks(src);");
        final int d = java.indexOf("glProg.drawBlocks(residual);");
        final int compose = java.indexOf("useAssetProgram(\"ark/sharp_compose\"");
        final int last = java.indexOf("glProg.setVar(\"modeU\", 2);");
        assertTrue(b > 0 && d > b && compose > d && last > compose);
    }
}

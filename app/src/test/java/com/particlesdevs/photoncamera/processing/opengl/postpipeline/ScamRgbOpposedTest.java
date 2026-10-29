package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** P362: the «Inpaint opposed» chrominance from the compute pass's group sums (float bits) and counts. */
public class ScamRgbOpposedTest {
    private static int[] group(float r, float g, float b, int nr, int ng, int nb) {
        return new int[]{Float.floatToIntBits(r), Float.floatToIntBits(g), Float.floatToIntBits(b), nr, ng, nb};
    }

    @Test
    public void sumsOverCounts() {
        final int[] a = group(1.5f, -0.5f, 0f, 3, 1, 0), b = group(0.5f, -0.5f, 0f, 1, 1, 0);
        final int[] v = new int[12];
        System.arraycopy(a, 0, v, 0, 6);
        System.arraycopy(b, 0, v, 6, 6);
        // R (1.5 + 0.5) / 4, G -1 / 2, B nothing counted: 0
        assertArrayEquals(new float[]{0.5f, -0.5f, 0f}, ScamRgb.opposedMeans(v, 2), 1e-6f);
    }

    @Test
    public void onlyClippedPixelsWithoutUltrashortContentCount() {
        // bits 0-2 clipped means, 3 border, 4 Bento mask, 5 ultrashort clipped mean
        final java.nio.ByteBuffer f = java.nio.ByteBuffer.wrap(new byte[]{0, 1, 2, 7, 8, 9, 16 | 1, 32 | 2, 16, (byte) 0x84});
        assertEquals(5L, ScamRgb.opposedPixels(f)); // 1, 2, 7, 9, 0x84 (bit 2 with bit 7)
    }

    @Test
    public void theWordCountIsTheByteCount() {
        // P364: eight flags a read: every byte value at every place of a word and of the tail, and odd sizes of random flags
        for (int v = 0; v < 256; v++) {
            for (int at = 0; at < 19; at++) {
                final java.nio.ByteBuffer f = java.nio.ByteBuffer.allocateDirect(19);
                f.put(at, (byte) v);
                assertEquals("byte 0x" + Integer.toHexString(v) + " at " + at, byteCount(f), ScamRgb.opposedPixels(f));
            }
        }
        final java.util.Random r = new java.util.Random(364);
        for (int n : new int[]{0, 1, 7, 8, 9, 255, 4097, 1 << 20, (1 << 20) + 3}) {
            final java.nio.ByteBuffer f = java.nio.ByteBuffer.allocateDirect(n);
            for (int i = 0; i < n; i++) f.put(i, (byte) r.nextInt(256));
            assertEquals("n " + n, byteCount(f), ScamRgb.opposedPixels(f));
        }
    }

    /** The count byte by byte, as before P364. */
    private static long byteCount(java.nio.ByteBuffer f) {
        long c = 0;
        for (int i = 0; i < f.capacity(); i++) {
            final int v = f.get(i) & 0xFF;
            if ((v & 7) != 0 && (v & 48) == 0) c++;
        }
        return c;
    }

    @Test
    public void aNonFiniteGroupSumIsLeftOut() {
        final int[] v = group(Float.NaN, 2f, Float.POSITIVE_INFINITY, 0, 4, 0);
        assertArrayEquals(new float[]{0f, 0.5f, 0f}, ScamRgb.opposedMeans(v, 1), 1e-6f);
    }

    private static String read(String path) throws java.io.IOException {
        java.io.File f = new java.io.File(path);
        if (!f.isFile()) f = new java.io.File("app/" + path);
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void theLocalLightGoesWithTheOpposedBranchOnly() throws java.io.IOException {
        // P393: a clipped channel of a coloured surface takes the local light's colour (oppLocalU) only where «Inpaint opposed» runs
        // (the chroma blocks are made whenever it can: perChannel && nearClip), scam_dev hybrid_highlight_opposed_local 0 = before;
        // unset in the shader = 0 = RawTherapee's reference alone (the checks that do not set it render the shader before P393)
        final String rgb = read("src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/ScamRgb.java");
        assertEquals(true, rgb.contains("glProg.setVar(\"oppLocalU\",opposed&&PreferenceKeys.hybridSwitch(\"highlight_opposed_local\",true)?1:0);"));
        final String shader = read("src/main/assets/shaders/scamhdr/scamrgb.glsl");
        assertEquals(true, shader.contains("uniform int oppLocalU;"));
        assertEquals(true, shader.contains("if (oppLocalU != 0 && chromaU != 0) {"));
        // P393 review: two channels clipped - the light at the measured channel's scale, only where that scale meets both clipped bounds
        // (a blue sky round yellow leaves made them lime green; the neutral prior set G = R)
        assertEquals(true, shader.contains("estK = qK * measK;"));
        assertEquals(true, shader.contains("wK = 1.0 - smoothstep(0.05, 0.20, log(max(boundK, 1.0e-6) / max(measK, 1.0e-6)));"));
        final String keys = read("src/main/java/com/particlesdevs/photoncamera/settings/DevKeys.java");
        assertEquals(true, keys.contains("\"hybrid_highlight_opposed_local\", true"));
    }
}

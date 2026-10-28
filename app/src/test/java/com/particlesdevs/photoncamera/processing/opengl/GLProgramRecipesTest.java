package com.particlesdevs.photoncamera.processing.opengl;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/**
 * P371: the post programs' recipes. The first shot after every install compiled all its programs (X8U: 46 in 836 ms) because
 * the disk cache is keyed by the processed source; a recipe (asset, defines, compute) made again from the installed APK gives
 * the key that shot asks for, so the idle warm-up can build it first.
 */
public class GLProgramRecipesTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String FRAGMENT = "precision highp float;\n#define SIZE 1, 1\n#define GAIN 1.0\nuniform sampler2D tex;\n"
            + "out vec4 c;\nvoid main(){ c = texelFetch(tex, ivec2(gl_FragCoord.xy), 0) * GAIN; }\n";
    private static final String COMPUTE = "precision highp float;\n#define LAYOUT //\nLAYOUT\nvoid main(){}\n";

    private static ArrayList<String[]> defines(String... nameValue) {
        ArrayList<String[]> d = new ArrayList<>();
        for (int i = 0; i + 1 < nameValue.length; i += 2) d.add(new String[]{nameValue[i], nameValue[i + 1]});
        return d;
    }

    @Test public void aRecipeKeepsKindNameComputeAndTheDefinesInOrder() {
        ArrayList<String[]> d = defines("SIZE", "4096, 3072", "LAYOUT", "layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;",
                "GAIN", "1.25");
        String r = GLProg.recipe('A', "why/combine", true, d);
        List<ArrayList<String[]>> out = new ArrayList<>();
        String[] p = GLProg.parseRecipe(r, out);
        assertNotNull(p);
        assertEquals("A", p[0]);
        assertEquals("why/combine", p[1]);
        assertEquals("1", p[2]);
        assertEquals(3, out.get(0).size());
        for (int i = 0; i < 3; i++) assertArrayEquals(d.get(i), out.get(0).get(i));
        // no defines given is not the same recipe as an empty list (loadShader(source) vs loadShader(source, defines))
        out.clear();
        p = GLProg.parseRecipe(GLProg.recipe('U', "gaussdown", false, null), out);
        assertNotNull(p);
        assertNull(out.get(0));
        assertEquals("0", p[2]);
    }

    @Test public void damagedRecipesGiveNothing() {
        assertNull(GLProg.parseRecipe(null, null));
        assertNull(GLProg.parseRecipe("R0\u0001A\u00010\u0001-\u0001x", null));
        assertNull(GLProg.parseRecipe("R1\u0001A\u00012\u0001-\u0001x", null));
        assertNull(GLProg.parseRecipe("R1\u0001A\u00010\u00012\u0001SIZE\u00021\u0001x", null)); // two defines announced, one given
        assertNull(GLProg.recipeKey("R1\u0001Q\u00010\u0001-\u0001x", path -> FRAGMENT));
        assertNull(GLProg.recipeKey(GLProg.recipe('A', "gone", false, null), path -> null)); // an asset the APK no longer has
    }

    /** The rebuilt key is the one useShader asks the cache for: the same loadShader on the same source and defines. */
    @Test public void theRebuiltKeyIsTheKeyOfTheShotsSource() {
        Map<String, String> assets = new HashMap<>();
        assets.put("shaders/why/combine.glsl", FRAGMENT);
        assets.put("shaders/utils/reduce.glsl", COMPUTE);
        ArrayList<String[]> d = defines("SIZE", "4096, 3072", "GAIN", "2.0");
        String asset = GLProg.recipeKey(GLProg.recipe('A', "why/combine", false, d), assets::get);
        assertEquals(GLProg.programKey(GLInterface.loadShader(FRAGMENT, defines("SIZE", "4096, 3072", "GAIN", "2.0")), false), asset);
        assertTrue(asset.startsWith("F\n" + GLProg.VERTEX_SOURCE + "\n"));
        assertTrue(asset.contains("#define SIZE 4096, 3072\n"));
        assertTrue(asset.contains("#define GAIN 2.0\n"));
        String util = GLProg.recipeKey(GLProg.recipe('U', "reduce", true,
                defines("LAYOUT", "layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;")), assets::get);
        assertTrue(util.startsWith("C\n"));
        assertTrue(util.contains("#define LAYOUT layout(local_size_x = 16"));
        String source = GLProg.recipeKey(GLProg.recipe('S', FRAGMENT, false, null), path -> null);
        assertEquals(GLProg.programKey(GLInterface.loadShader(FRAGMENT), false), source);
        // a changed asset (a new install) gives the new source's key, not the old one
        assets.put("shaders/why/combine.glsl", FRAGMENT.replace("* GAIN", "* GAIN * 0.5"));
        assertNotEquals(asset, GLProg.recipeKey(GLProg.recipe('A', "why/combine", false, d), assets::get));
    }

    @Test public void recipesAreKeptOnceNewestFirstAndDamagedOnesGo() throws Exception {
        File dir = tmp.newFolder("glprog");
        GLProgramDiskCache cache = new GLProgramDiskCache(dir);
        String a = GLProg.recipe('A', "a", false, null), b = GLProg.recipe('A', "b", false, null);
        cache.keepRecipe(a);
        cache.keepRecipe(a);
        cache.flush();
        File fa = new File(dir, GLProgramDiskCache.recipeName(a));
        assertTrue(fa.isFile());
        assertTrue(fa.setLastModified(System.currentTimeMillis() - 60000));
        cache.keepRecipe(b);
        cache.flush();
        List<String> kept = new GLProgramDiskCache(dir).recipes(10);
        assertEquals(2, kept.size());
        assertEquals(b, kept.get(0));
        assertEquals(a, kept.get(1));
        assertEquals(1, new GLProgramDiskCache(dir).recipes(1).size());
        File bad = new File(dir, GLProgramDiskCache.recipeName("x"));
        try (FileOutputStream out = new FileOutputStream(bad)) { out.write("y".getBytes(StandardCharsets.UTF_8)); }
        assertEquals(2, cache.recipes(10).size());
        assertFalse(bad.exists());
        // the recipes are not program sources: P237's missing-source list stays as it was
        assertTrue(cache.missingSources("d", 10).isEmpty());
        assertFalse(cache.has("d", "k"));
        cache.store("d", "k", 1, new byte[]{1});
        cache.flush();
        assertTrue(cache.has("d", "k"));
    }

    @Test public void theOldestRecipesGoBeyondTheLimit() throws Exception {
        File dir = tmp.newFolder("glprog");
        GLProgramDiskCache cache = new GLProgramDiskCache(dir);
        for (int i = 0; i < GLProgramDiskCache.MAX_RECIPES + 4; i++) cache.keepRecipe(GLProg.recipe('A', "p" + i, false, null));
        cache.flush();
        File[] files = dir.listFiles((d, n) -> n.startsWith("glr-") && n.endsWith(".rcp"));
        assertNotNull(files);
        assertEquals(GLProgramDiskCache.MAX_RECIPES, files.length);
    }

    /** A shot that needs the program the warm-up is compiling waits for it instead of compiling it a second time. */
    @Test public void aShotWaitsForTheWarmUpsCompileOfTheSameProgram() throws Exception {
        assertFalse(GLProg.awaitWarmUp("F\nnobody compiles this"));
        final String key = "F\nwarming";
        final CountDownLatch compiling = new CountDownLatch(1);
        GLProg.COMPILING.put(key, compiling);
        final AtomicBoolean waited = new AtomicBoolean();
        final CountDownLatch started = new CountDownLatch(1);
        Thread shot = new Thread(() -> { started.countDown(); waited.set(GLProg.awaitWarmUp(key)); });
        shot.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        Thread.sleep(50);
        assertTrue(shot.isAlive()); // still waiting
        GLProg.COMPILING.remove(key);
        compiling.countDown();
        shot.join(5000);
        assertFalse(shot.isAlive());
        assertTrue(waited.get());
    }

    /** Review m5: a process keeps a bounded set of recipes, the Java-generated sources ('S') a small share of it. */
    @Test public void theRecordedRecipesAreBoundedAndGeneratedSourcesASmallShare() {
        final String run = Long.toHexString(System.nanoTime());
        int sources = 0, all = 0;
        for (int i = 0; i < 200; i++)
            if (GLProg.recordable(GLProg.recipe('S', "void main(){} // " + run + " " + i, false, null))) sources++;
        assertTrue(sources <= GLProg.MAX_RECORDED_SOURCES);
        for (int i = 0; i < 2 * GLProg.MAX_RECORDED; i++) if (GLProg.recordable(GLProg.recipe('A', "p" + run + "_" + i, false, null))) all++;
        assertTrue(all + sources <= GLProg.MAX_RECORDED);
        assertTrue("asset recipes still kept after the generated ones", all > 0);
        assertFalse("once a process", GLProg.recordable(GLProg.recipe('A', "p" + run + "_0", false, null)));
    }
}

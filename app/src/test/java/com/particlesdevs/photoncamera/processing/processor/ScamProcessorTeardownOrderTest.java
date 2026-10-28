package com.particlesdevs.photoncamera.processing.processor;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The wiring behind ScamProcessorFailureTest's helpers, read from ScamProcessor's source (Run and processBurst need a camera).
 * Run()'s finally tears down the pipeline a failed shot left open, then the deferred one, and frees the reference RAW only
 * after both (a pass may still read it). Every path that frees the reference RAW itself clears referenceRaw first: a free left
 * without it is freed a second time by Run()'s finally, and the arena's view count (Allocator.arenaSlotRefs, which ZslRing
 * relies on) drops below the views still in use. A pipeline that is deferred or closed is no longer the open one, so a later
 * failure does not tear it down again.
 */
public class ScamProcessorTeardownOrderTest {
    private static String source() throws IOException {
        final String path = "src/main/java/com/particlesdevs/photoncamera/processing/processor/ScamProcessor.java";
        File f = new File(path);
        if (!f.isFile()) f = new File("app/" + path);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String method(String src, String signature) {
        final int at = src.indexOf(signature);
        assertTrue("no " + signature, at >= 0);
        int depth = 0;
        for (int j = src.indexOf('{', at); j < src.length(); j++) {
            final char c = src.charAt(j);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(at, j + 1);
        }
        throw new AssertionError("unbalanced " + signature);
    }

    private static int count(String s, String what) {
        int n = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + what.length())) n++;
        return n;
    }

    @Test
    public void runTearsDownTheOpenPipelineThenFreesTheReference() throws IOException {
        final String run = method(source(), "public void Run()");
        final int end = run.indexOf("ScamLab.endShot();");
        final int fin = run.lastIndexOf("} finally {", end);
        assertTrue("the shot's finally, after its catch", end > 0 && fin > run.indexOf("} catch (Exception | OutOfMemoryError e) {"));
        final int open = run.indexOf("releaseOpenPipeline();", end);
        final int deferred = run.indexOf("finishDeferredTeardown();", open);
        final int take = run.indexOf("final ByteBuffer reference = referenceRaw;", deferred);
        final int clear = run.indexOf("referenceRaw = null;", take);
        final int free = run.indexOf("if (reference != null) Allocator.free(reference);", clear);
        assertTrue("finally, endShot, the open pipeline, the deferred one, the reference RAW",
                fin > 0 && end > fin && open > end && deferred > open && take > deferred && clear > take && free > clear);
        assertEquals("one reference free in Run", 1, count(run, "Allocator.free(reference)"));
    }

    @Test
    public void everyReferenceFreeClearsTheFieldFirst() throws IOException {
        final String burst = method(source(), "private void processBurst()");
        final int handoff = burst.indexOf("ByteBuffer output=ref.buffer;ref.buffer=null;");
        assertTrue("the reference handoff", handoff > 0);
        assertTrue("tracked at the handoff and after outOfArena", burst.substring(handoff).startsWith(
                "ByteBuffer output=ref.buffer;ref.buffer=null;\n        referenceRaw = output;\n"
                        + "        if ((long) width * height > 16_000_000L) output = outOfArena(output);\n        referenceRaw = output;\n"));
        final String after = burst.substring(handoff);
        final int tail = after.indexOf("final ByteBuffer freeInput = jpegInput");
        assertTrue("the tail free", tail > 0);
        // the RAW-only return and the hybrid free above 16 MP: each one directly after its own clear
        int frees = 0, last = 0;
        for (int at = after.indexOf("Allocator.free(output);"); at >= 0 && at < tail; at = after.indexOf("Allocator.free(output);", at + 1)) {
            final int clear = after.lastIndexOf("referenceRaw = null;", at);
            assertTrue("referenceRaw cleared before the free at " + at, clear > last);
            assertTrue("nothing between the clear and the free at " + at,
                    after.substring(clear + "referenceRaw = null;".length(), at).trim().isEmpty());
            last = at;
            frees++;
        }
        assertEquals("the RAW-only free and the hybrid free above 16 MP", 2, frees);
        // the tail: one clear after those two, before both its branches
        assertTrue("the tail free after those two", tail > last);
        final int clear = after.lastIndexOf("referenceRaw = null;", tail);
        assertTrue("referenceRaw cleared before the tail free", clear > last);
        assertTrue("the tail's other branch", after.indexOf("Allocator.free(jpegInput);", tail) > tail);
        assertEquals("no further reference free",
                -1, after.indexOf("referenceRaw = ", after.indexOf("Allocator.free(jpegInput);", tail)));
    }

    @Test
    public void aDeferredOrClosedPipelineIsNoLongerOpen() throws IOException {
        final String burst = method(source(), "private void processBurst()");
        assertEquals("one post pipeline", 1, count(burst, "new PostPipeline()"));
        assertTrue("tracked when made", burst.contains("PostPipeline pipeline = new PostPipeline();\n        openPipeline = pipeline;\n"));
        assertTrue("not open once deferred",
                burst.contains("if (pipeline.deferTeardown) { deferredPipeline = pipeline; openPipeline = null; }"));
        final int close = burst.indexOf("if (deferredPipeline == null) try {");
        assertTrue("the success-path close", close > 0);
        assertTrue("not open before its close", burst.startsWith("if (deferredPipeline == null) try {\n            openPipeline = null;\n"
                + "            pipeline.close();\n", close));
        assertEquals("one close in processBurst", 1, count(burst, "pipeline.close();"));
    }
}

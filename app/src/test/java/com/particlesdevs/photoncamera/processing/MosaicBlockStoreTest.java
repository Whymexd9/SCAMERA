package com.particlesdevs.photoncamera.processing;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

/** P35: the remembered colour block of a module's RAW stream. */
public class MosaicBlockStoreTest {
    private static final String QUAD = "2|4096x3072|sig|29";

    private static MosaicBlockStore fresh() {
        return new MosaicBlockStore(MosaicBlockStore.memoryBacking());
    }

    @After
    public void tearDown() {
        MosaicBlockStore.setInstance(null);
        MosaicBlockStore.setShotKey("");
        MosaicStream.reset();
    }

    @Test
    public void confidentAnswerIsStored() {
        MosaicBlockStore s = fresh();
        assertEquals(0, s.stored(QUAD));
        assertEquals(2, s.observe(QUAD, true, 2, "test"));
        assertEquals(2, s.stored(QUAD));
    }

    @Test
    public void unconfidentAnswerKeepsWhatIsStored() {
        MosaicBlockStore s = fresh();
        assertEquals("nothing stored from an unconfident answer", 0, s.observe(QUAD, false, 1, "dark"));
        assertEquals(0, s.stored(QUAD));
        s.observe(QUAD, true, 2, "day");
        for (int i = 0; i < 10; i++) assertEquals(2, s.observe(QUAD, false, 1, "dark"));
        assertEquals(2, s.stored(QUAD));
    }

    @Test
    public void threeAgreeingConfidentDisagreementsReplace() {
        MosaicBlockStore s = fresh();
        s.observe(QUAD, true, 2, "day");
        assertEquals(2, s.observe(QUAD, true, 1, "vf"));
        assertEquals(2, s.observe(QUAD, true, 1, "vf"));
        assertEquals(1, s.observe(QUAD, true, 1, "vf"));
        assertEquals(1, s.stored(QUAD));
    }

    @Test
    public void disagreementsMustAgreeInARow() {
        MosaicBlockStore s = fresh();
        s.observe(QUAD, true, 2, "day");
        s.observe(QUAD, true, 1, "vf");
        s.observe(QUAD, true, 1, "vf");
        s.observe(QUAD, true, 2, "vf");      // agrees with the stored block: the count restarts
        s.observe(QUAD, true, 1, "vf");
        assertEquals(2, s.observe(QUAD, true, 1, "vf"));
        s.observe(QUAD, true, 4, "vf");      // another block: its own count
        s.observe(QUAD, true, 4, "vf");
        assertEquals(2, s.stored(QUAD));
        assertEquals(4, s.observe(QUAD, true, 4, "vf"));
    }

    @Test
    public void newKeyStartsFresh() {
        MosaicBlockStore s = fresh();
        s.observe(QUAD, true, 2, "day");
        assertEquals("another sensor mode is another stream", 0, s.stored("2|4096x3072|sig|0"));
        assertEquals("another request set is another stream", 0, s.stored("2|4096x3072|sig2|29"));
        assertEquals(0, s.stored("off"));
        assertEquals(0, s.observe("off", true, 2, "disabled measurement"));
        assertEquals(0, s.observe("", true, 2, "no key"));
    }

    @Test
    public void invalidBlocksAreIgnored() {
        MosaicBlockStore s = fresh();
        assertEquals(0, s.observe(QUAD, true, 3, "test"));
        assertEquals(0, s.observe(QUAD, true, 0, "test"));
        assertEquals(0, s.stored(QUAD));
    }

    @Test
    public void shotUsesStoredThenDeclaredBlock() {
        MosaicBlockStore.setInstance(fresh());
        MosaicBlockStore.setShotKey(QUAD);
        assertEquals("nothing known: measure", 0, MosaicBlockStore.blockForShot(0));
        assertEquals("a declared Quad sensor mode applies before the first detection", 2, MosaicBlockStore.blockForShot(2));
        MosaicBlockStore.get().observe(QUAD, true, 4, "test");
        assertEquals(4, MosaicBlockStore.blockForShot(2));
    }

    @Test
    public void declaredMosaicModeNeverTakesAStoredOne() {
        MosaicBlockStore.setInstance(fresh());
        MosaicBlockStore.get().observe(QUAD, true, 1, "flat scene");
        MosaicBlockStore.setShotKey(QUAD, 2);
        assertEquals("sensor mode 5 declares Quad: a stored 1 is not used", 2, MosaicBlockStore.blockForShot(0));
        MosaicBlockStore.setShotKey(QUAD, 0);
        assertEquals("plain module: the stored 1 skips the detector", 1, MosaicBlockStore.blockForShot(2));
        assertEquals(4, MosaicBlockStore.choose(1, 4));
        assertEquals(4, MosaicBlockStore.choose(4, 2));
        assertEquals(0, MosaicBlockStore.choose(0, 0));
    }

    @Test
    public void shotDeclaredBlockWinsOverTheFallback() {
        MosaicBlockStore.setInstance(fresh());
        MosaicBlockStore.setShotKey(QUAD, 0);
        assertEquals("the shot's module declared nothing: the active module's block is not used", 0, MosaicBlockStore.blockForShot(2));
        MosaicBlockStore.setShotKey(QUAD);
        assertEquals("no declared block given: the fallback", 2, MosaicBlockStore.blockForShot(2));
        assertEquals(2, MosaicBlockStore.declaredBlock(5));
        assertEquals(4, MosaicBlockStore.declaredBlock(7));
        assertEquals(0, MosaicBlockStore.declaredBlock(29));
    }

    @Test
    public void storedOneIsCorrectedByThreeConfidentAnswers() {
        MosaicBlockStore s = fresh();
        s.observe(QUAD, true, 1, "first shot");
        s.observe(QUAD, true, 2, "vf");
        s.observe(QUAD, false, 1, "vf dark");   // unconfident: no vote either way
        s.observe(QUAD, true, 2, "vf");
        assertEquals(2, s.observe(QUAD, true, 2, "vf"));
    }

    @Test
    public void viewfinderSessionStartsFromTheStoredBlock() {
        MosaicBlockStore.setInstance(fresh());
        MosaicBlockStore.get().observe(QUAD, true, 2, "earlier session");
        MosaicStream.startSession(QUAD);
        assertEquals("the first viewfinder frame already uses the stored block", 2, MosaicStream.block());
        MosaicStream.startSession("other");
        assertEquals(0, MosaicStream.block());
    }

    @Test
    public void moduleBlockForThePrewarm() {
        MosaicBlockStore.setInstance(fresh());
        assertEquals("nothing known", 0, MosaicBlockStore.blockForModule(QUAD, 0));
        assertEquals("a declared Quad mode", 2, MosaicBlockStore.blockForModule(QUAD, 2));
        MosaicBlockStore.get().observe(QUAD, true, 4, "earlier session");
        assertEquals("the stored block", 4, MosaicBlockStore.blockForModule(QUAD, 0));
        assertEquals(0, MosaicBlockStore.blockForModule(null, 0));
    }
}

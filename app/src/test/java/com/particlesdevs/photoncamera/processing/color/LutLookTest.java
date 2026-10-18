package com.particlesdevs.photoncamera.processing.color;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** SIMPLE_UI §4c «Что меняется»: the analysis of a LUT by reference colours (the concept's lutAnalyse). */
public class LutLookTest {
    private static Map<LutLook.Aspect, String> tags(FilmLut lut, float strength) {
        List<LutLook.Tag> list = new LutLook(lut::sampleInto, strength).analyse();
        Map<LutLook.Aspect, String> out = new HashMap<>();
        for (LutLook.Tag t : list) out.put(t.aspect, t.verdict);
        return out;
    }

    @Test
    public void identityChangesNothing() {
        Map<LutLook.Aspect, String> t = tags(FilmLut.identity(17), 1f);
        assertEquals("same", t.get(LutLook.Aspect.CONTRAST));
        assertEquals("kept", t.get(LutLook.Aspect.COLOURS));
        for (LutLook.Aspect a : new LutLook.Aspect[]{LutLook.Aspect.BLACK, LutLook.Aspect.WHITE, LutLook.Aspect.SHADOWS,
                LutLook.Aspect.HIGHLIGHTS, LutLook.Aspect.SKIN, LutLook.Aspect.GREENS})
            assertFalse(a.name(), t.containsKey(a));
    }

    @Test
    public void filmLooksReadAsTheirDescriptions() {
        Map<LutLook.Aspect, String> mono = tags(FilmLooks.lut("scam:mono400"), 1f);
        assertEquals("mono", mono.get(LutLook.Aspect.COLOURS));
        assertFalse("no tint read from a grey look", mono.containsKey(LutLook.Aspect.SHADOWS));
        Map<LutLook.Aspect, String> slide = tags(FilmLooks.lut("scam:slide100"), 1f);
        assertTrue(slide.get(LutLook.Aspect.CONTRAST), slide.get(LutLook.Aspect.CONTRAST).equals("stronger")
                || slide.get(LutLook.Aspect.CONTRAST).equals("midtones"));
        Map<LutLook.Aspect, String> warm = tags(FilmLooks.lut("scam:warm400"), 1f);
        assertEquals("lifted", warm.get(LutLook.Aspect.BLACK));
        assertEquals("calmer", warm.get(LutLook.Aspect.COLOURS));
    }

    @Test
    public void strengthMixesLinearly() {
        FilmLut mono = FilmLooks.lut("scam:mono400");
        LutLook none = new LutLook(mono::sampleInto, 0f), half = new LutLook(mono::sampleInto, .5f), full = new LutLook(mono::sampleInto, 1f);
        float[] in = {.8f, .3f, .2f};
        assertArrayEquals(in, none.apply(in[0], in[1], in[2]), 1e-6f);
        float[] f = full.apply(in[0], in[1], in[2]), h = half.apply(in[0], in[1], in[2]);
        for (int c = 0; c < 3; c++) assertEquals((in[c] + f[c]) / 2, h[c], 1e-5f);
        assertEquals("kept", tags(mono, 0f).get(LutLook.Aspect.COLOURS));
    }

    @Test
    public void sampleIntoMatchesSample() {
        FilmLut lut = FilmLooks.lut("scam:cine500");
        float[] out = new float[3];
        for (float v = 0f; v <= 1f; v += .137f) {
            lut.sampleInto(v, 1f - v, v * .5f, out);
            assertArrayEquals(lut.sample(v, 1f - v, v * .5f), out, 1e-6f);
        }
    }
}

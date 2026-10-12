package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class DcgSearchTest {
    @Test
    public void dcgTriesTheForcedHintsFirstThenTheVideoScenarioThenTheSweep() {
        List<DcgSearch.Step> s = DcgSearch.steps(false);
        assertEquals(-1, s.get(0).cameraScenario);
        assertEquals(133, s.get(0).hint);
        assertEquals(118, s.get(1).hint);
        assertTrue(s.get(2).sweep.contains(2));
        assertFalse(s.get(2).sweep.contains(DcgSearch.BINNED));
        assertEquals(DcgSearch.VIDEO_SCENARIO, s.get(3).cameraScenario);
        DcgSearch.Step last = s.get(s.size() - 1);
        assertEquals(DcgSearch.SCENARIO_TO, last.cameraScenario);
        assertEquals(133, last.hint);
        // 2 hints + sweep, 2 hints + sweep on 501, 60 other camera scenarios
        assertEquals(66, s.size());
    }

    @Test
    public void loficUsesTheLoficSensorModes() {
        List<DcgSearch.Step> s = DcgSearch.steps(true);
        assertEquals(119, s.get(0).hint);
        assertEquals(124, s.get(1).hint);
        assertEquals(129, s.get(2).hint);
    }

    @Test
    public void fourteenBitFromTheWhiteLevelOrTheRawSamples() {
        assertFalse(DcgSearch.fourteenBit(1023, 0, 1023));
        assertFalse(DcgSearch.fourteenBit(null, 0, 1023));
        assertTrue(DcgSearch.fourteenBit(16383, 0, 1023));
        assertTrue(DcgSearch.fourteenBit(1023, 16383, 1023));
        assertTrue(DcgSearch.fourteenBit(4095, 0, 0));
    }

    @Test
    public void scenarioMovesOnlyForThisCamera() {
        assertFalse(DcgSearch.scenarioMoved(new int[]{2, 1}, 2));
        assertTrue(DcgSearch.scenarioMoved(new int[]{2, 18}, 2));
        assertFalse(DcgSearch.scenarioMoved(new int[]{6, 18}, 2));
        assertTrue(DcgSearch.scenarioMoved(new int[]{6, 1, 2, 33}, 2));
        assertFalse(DcgSearch.scenarioMoved(null, 2));
    }

    @Test
    public void modeCyclesOffDcgLofic() {
        assertEquals(Dcg14Mode.DCG, Dcg14Mode.next(Dcg14Mode.OFF));
        assertEquals(Dcg14Mode.LOFIC, Dcg14Mode.next(Dcg14Mode.DCG));
        assertEquals(Dcg14Mode.OFF, Dcg14Mode.next(Dcg14Mode.LOFIC));
    }
}

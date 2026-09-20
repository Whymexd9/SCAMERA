package com.particlesdevs.photoncamera.processing.parameters;

import org.junit.Test;
import static org.junit.Assert.*;

public class HdrBracketFactorsTest {
    @Test public void absentLongFrameDoesNotConsumeShortExposureBudget() {
        assertEquals(9.8, HdrBracketFactors.limit(16, 1, 9.8), 1e-9);
        assertEquals(4, HdrBracketFactors.limit(4, 1, 9.8), 1e-9);
    }
    @Test public void twoActiveEndsShareTheReductionInStops() {
        assertEquals(Math.sqrt(9.8), HdrBracketFactors.limit(16, 16, 9.8), 1e-9);
    }
    @Test public void asymmetricBracketNeverExceedsTheCeiling() {
        for(int a=0;a<=8;a++)for(int b=0;b<=8;b++) {
            double shortRequested=Math.scalb(1.0,a),longRequested=Math.scalb(1.0,b);
            double s=HdrBracketFactors.limit(shortRequested,longRequested,9.8);
            double l=HdrBracketFactors.limit(longRequested,shortRequested,9.8);
            assertTrue(s>=1 && l>=1 && s*l<=9.800001);
            assertTrue(s<=shortRequested+1e-9 && l<=longRequested+1e-9);
        }
        assertEquals(9.8,HdrBracketFactors.limit(256,2,9.8),1e-9);
    }
    @Test public void disabledCeilingKeepsRequestedBracket() {
        assertEquals(16,HdrBracketFactors.limit(16,16,0),0);
        assertEquals(16,HdrBracketFactors.limit(16,16,1),0);
    }
}

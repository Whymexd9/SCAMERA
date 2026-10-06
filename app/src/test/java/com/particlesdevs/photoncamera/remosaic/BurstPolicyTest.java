package com.particlesdevs.photoncamera.remosaic;

import org.junit.Test;
import static org.junit.Assert.*;

public class BurstPolicyTest {
    @Test public void originalCalibrationCoversFiveIsoLevelsAtBothExposures() {
        CalibrationPlan p=new CalibrationPlan(100,1600,100_000,1_000_000_000);
        int[] expected={100,100,200,200,400,400,800,800,1600,1600};
        assertArrayEquals(expected,p.isos);
        for(int i=0;i<10;i++)assertEquals((i&1)==0?1_000_000:66_666_667,p.exposures[i]);
        assertEquals(40,CalibrationPlan.PROFILES*CalibrationPlan.FRAMES);
        CalibrationPlan limited=new CalibrationPlan(200,200,2_000_000,20_000_000);
        for(int i=0;i<10;i++) {assertEquals(200,limited.isos[i]);assertEquals((i&1)==0?2_000_000:20_000_000,limited.exposures[i]);}
        assertThrows(IllegalArgumentException.class,()->new CalibrationPlan(0,100,1,2));
    }
    @Test public void fpnMatchingUsesFloatRatiosAndOriginalExposureWeight() {
        assertEquals(0,CalibrationPlan.distance(100,1_000_000,100,1_000_000),0);
        assertEquals(Math.log(2)*Math.log(2)*.35,CalibrationPlan.distance(100,2_000_000,100,1_000_000),1e-10);
        assertTrue(CalibrationPlan.distance(150,1_000_000,200,1_000_000)>0);
        assertEquals(.75f,CalibrationPlan.scale(150,200),0);
        assertEquals(.7f,CalibrationPlan.scale(100,1600),0);
        assertEquals(1.4f,CalibrationPlan.scale(1600,100),0);
    }

    @Test public void memoryBudgetAppliesBeforeCapture() {
        assertEquals(15,BurstPolicy.frameCount(15,4096,3072));
        int full=BurstPolicy.frameCount(40,8192,6144);
        assertTrue(full>=3);assertTrue(full<10);
        long pixels=8192L*6144;
        assertTrue(full*pixels*9/4+pixels*4<=BurstPolicy.INPUT_BUDGET);
        assertThrows(IllegalArgumentException.class,()->BurstPolicy.frameCount(15,0,100));
        assertThrows(IllegalArgumentException.class,()->BurstPolicy.frameCount(15,8193,6144));
    }
}

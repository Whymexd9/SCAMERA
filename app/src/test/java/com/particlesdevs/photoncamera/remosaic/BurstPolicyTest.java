package com.particlesdevs.photoncamera.remosaic;

import com.particlesdevs.photoncamera.capture.HexQuadZslSelector;
import com.particlesdevs.photoncamera.settings.SettingsAvailability;
import java.util.*;
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
    @Test public void actualMetadataControlsZslMembership() {
        List<HexQuadZslSelector.Sample> samples=new ArrayList<>();
        for(int i=0;i<17;i++)samples.add(new HexQuadZslSelector.Sample(1_000_000_000L+i*33_000_000L,8_000_000,100));
        assertEquals(15,HexQuadZslSelector.select(samples,15).length);
        // A final image lacking its result is skipped, never assigned invented ISO.
        samples.add(new HexQuadZslSelector.Sample(2_000_000_000L,0,0));
        assertEquals(15,HexQuadZslSelector.select(samples,15).length);
        samples.set(8,new HexQuadZslSelector.Sample(samples.get(8).timestamp,9_000_000,100));
        assertEquals(0,HexQuadZslSelector.select(samples,15).length);
        // Existing six-frame HexQuad policy still works on the latest six.
        assertEquals(6,HexQuadZslSelector.select(samples).length);
    }
    @Test public void sourceControlsAndConflicts() {
        assertEquals(1,BurstPolicy.block("1"));assertEquals(2,BurstPolicy.block("2"));assertEquals(4,BurstPolicy.block("4"));
        assertEquals("BGGR",BurstPolicy.cfa("auto",3));assertEquals("RGGB",BurstPolicy.cfa("RGGB",3));
        assertThrows(IllegalArgumentException.class,()->BurstPolicy.cfa("auto",4));
        Map<String,Object> m=new HashMap<>();m.put("pref_raw_mfsr_enabled_key",true);m.put("pref_remosaic_enabled_key",true);m.put("pref_remosaic_backend_key","hp9_hexquad");
        SettingsAvailability a=new SettingsAvailability(m);
        assertNull(a.reason("pref_mfsr_source_key"));assertNotNull(a.reason("pref_remosaic_backend_key"));
        assertNull(a.reason("pref_short_frame_count_key"));assertNotNull(a.reason("pref_frame_count_key"));assertNotNull(a.reason("hexquad_luma"));
    }
    @Test public void bracketsReserveSpaceWithoutUsingGeneralFrameCount() {
        assertEquals(15,BurstPolicy.bracketBaseCount(15,1,1,4096,3072));
        assertEquals(3,BurstPolicy.bracketBaseCount(15,1,1,8192,6144));
        assertThrows(IllegalArgumentException.class,()->BurstPolicy.bracketBaseCount(15,8,8,8192,6144));
        Map<String,Object> settings=new HashMap<>();settings.put("pref_raw_mfsr_enabled_key",true);
        settings.put("pref_short_frame_count_key","1");
        assertNull(new SettingsAvailability(settings).reason("pref_short_exposure_ev_key"));
        assertNotNull(new SettingsAvailability(settings).reason("pref_frame_count_key"));
        settings.put("pref_mfsr_calibrate_key",true);
        assertNotNull(new SettingsAvailability(settings).reason("pref_short_frame_count_key"));
    }
    @Test public void bracketGroupsSeparateExposureAndRejectIncompleteBase() {
        List<ExposureGroups.Sample> f=Arrays.asList(
                new ExposureGroups.Sample(1,100,100,1),
                new ExposureGroups.Sample(2,400,100,0),
                new ExposureGroups.Sample(3,400,100,0),
                new ExposureGroups.Sample(4,400,100,0),
                new ExposureGroups.Sample(5,800,100,2),
                new ExposureGroups.Sample(6,800,200,2));
        List<List<Integer>> groups=ExposureGroups.split(f);
        assertEquals(Arrays.asList(1,2,3),groups.get(0));
        assertEquals(4,groups.size()); // Even two long donors with different ISO stay separate.
        assertThrows(IllegalArgumentException.class,()->ExposureGroups.split(f.subList(0,3)));
        List<ExposureGroups.Sample> mixed=new ArrayList<>(f);
        mixed.set(2,new ExposureGroups.Sample(3,500,100,0));
        assertThrows(IllegalArgumentException.class,()->ExposureGroups.split(mixed));
        mixed.set(2,f.get(1));
        assertThrows(IllegalArgumentException.class,()->ExposureGroups.split(mixed));
    }
}

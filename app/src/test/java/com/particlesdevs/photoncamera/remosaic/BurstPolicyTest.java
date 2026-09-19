package com.particlesdevs.photoncamera.remosaic;

import com.particlesdevs.photoncamera.capture.HexQuadZslSelector;
import com.particlesdevs.photoncamera.settings.SettingsAvailability;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class BurstPolicyTest {
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
        assertNotNull(a.reason("pref_short_frame_count_key"));assertNotNull(a.reason("hexquad_luma"));
    }
}

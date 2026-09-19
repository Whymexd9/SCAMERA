package com.particlesdevs.photoncamera.capture;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;
public class PreviewFrameMatcherTest {
    @Test public void matchesBothArrivalOrdersWithoutUsingAnotherExposure() {
        List<String> pairs=new ArrayList<>(),released=new ArrayList<>();
        PreviewFrameMatcher<String,String> m=new PreviewFrameMatcher<>((i,r)->pairs.add(i+":"+r),released::add);
        m.image(2,"raw2");m.result(1,"metadata1");assertTrue(pairs.isEmpty());
        m.result(2,"metadata2");m.image(1,"raw1");
        assertEquals(java.util.Arrays.asList("raw2:metadata2","raw1:metadata1"),pairs);assertTrue(released.isEmpty());
    }
    @Test public void missingMetadataCannotStarveReaderAndClearDropsOldSession() {
        List<Integer> released=new ArrayList<>(),delivered=new ArrayList<>();
        PreviewFrameMatcher<Integer,Integer> m=new PreviewFrameMatcher<>((i,r)->delivered.add(i),released::add);
        m.image(1,1);m.image(2,2);m.image(3,3);assertEquals(java.util.Arrays.asList(1),released);
        m.clear();assertEquals(java.util.Arrays.asList(1,2,3),released);
        m.result(3,3);assertTrue(delivered.isEmpty());
    }
}

package com.particlesdevs.photoncamera.capture;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class TimestampFrameRouterTest {
    @Test public void previewAndStillNeverDependOnArrivalOrder(){
        List<String> delivered=new ArrayList<>(),released=new ArrayList<>();
        TimestampFrameRouter<String> router=new TimestampFrameRouter<>((image,still)->delivered.add(image+":"+still),released::add);
        router.image(10,"preview");router.request(20,true);router.image(20,"photo");router.request(10,false);
        assertEquals(Arrays.asList("photo:true","preview:false"),delivered);assertTrue(released.isEmpty());
    }
    @Test public void missingMetadataCannotExhaustTheImageReader(){
        List<Integer> released=new ArrayList<>();TimestampFrameRouter<Integer> router=new TimestampFrameRouter<>((i,s)->fail(),released::add);
        for(int i=0;i<6;i++)router.image(i,i);assertEquals(Arrays.asList(0,1,2),released);router.clear();assertEquals(6,released.size());
    }
}

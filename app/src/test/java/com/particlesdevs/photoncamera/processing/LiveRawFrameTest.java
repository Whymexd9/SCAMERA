package com.particlesdevs.photoncamera.processing;
import java.nio.ByteBuffer;
import org.junit.Test;
import static org.junit.Assert.*;
public class LiveRawFrameTest {
    @Test public void captureCannotOverwriteFrameBeingUploaded(){
        LiveRawFrame.setEnabled(true);
        try{
            publish(7);LiveRawFrame.Frame read=LiveRawFrame.acquire();
            publish(11);publish(13);publish(17);
            assertEquals(7,read.buffer.get(0));
            assertEquals(17,LiveRawFrame.acquire().buffer.get(0));
        }finally{LiveRawFrame.setEnabled(false);}
        assertNull(LiveRawFrame.acquire());
    }
    private void publish(int value){LiveRawFrame.publish(ByteBuffer.wrap(new byte[]{(byte)value,0}),1,1,2,0,1023,new float[4],new float[]{1,1,1},new float[]{1,0,0,0,1,0,0,0,1});}
}

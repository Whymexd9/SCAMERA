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
    @Test public void truncatedPlaneCannotReplaceAValidFrame(){
        LiveRawFrame.setEnabled(true);
        try{publish(9);int version=LiveRawFrame.getVersion();
            LiveRawFrame.publish(ByteBuffer.wrap(new byte[7]),2,2,4,0,1023,null,null,null);
            assertEquals(version,LiveRawFrame.getVersion());assertEquals(9,LiveRawFrame.acquire().buffer.get(0));
        }finally{LiveRawFrame.setEnabled(false);}
    }
    @Test public void repeatedDisableDoesNotAdvanceTheSession(){
        LiveRawFrame.setEnabled(false);int version=LiveRawFrame.getVersion();LiveRawFrame.setEnabled(false);assertEquals(version,LiveRawFrame.getVersion());
        LiveRawFrame.setEnabled(true);publish(8);int session=LiveRawFrame.acquire().session;
        LiveRawFrame.setEnabled(false);LiveRawFrame.setEnabled(true);assertNull(LiveRawFrame.acquire());publish(9);
        assertNotEquals(session,LiveRawFrame.acquire().session);LiveRawFrame.setEnabled(false);
    }
    @Test public void paddedRowsWithShortFinalRowArePackedWithoutOverread() {
        LiveRawFrame.setEnabled(true);
        try {
            ByteBuffer plane=ByteBuffer.wrap(new byte[]{3,0,4,0,99,99,5,0,6,0});
            LiveRawFrame.publish(plane,2,2,6,0,1023,null,null,null);
            LiveRawFrame.Frame f=LiveRawFrame.acquire();assertEquals(4,f.rowStride);assertEquals(8,f.buffer.remaining());
            assertEquals(5,f.buffer.get(4));assertEquals(0,plane.position());
        } finally {LiveRawFrame.setEnabled(false);}
    }
    private void publish(int value){LiveRawFrame.publish(ByteBuffer.wrap(new byte[]{(byte)value,0,0,0,0,0,0,0}),2,2,4,0,1023,new float[4],new float[]{1,1,1},new float[]{1,0,0,0,1,0,0,0,1});}
}

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
    @Test public void raw10FramesAreUnpackedForTheViewfinder() {
        // two rows of 4 pixels: 5 packed bytes + 1 padding byte per row (MIPI RAW10)
        int[][] px={{1023,512,3,700},{0,1,2,1022}};
        byte[] data=new byte[12];
        for(int y=0;y<2;y++){int o=y*6;int b4=0;for(int i=0;i<4;i++){data[o+i]=(byte)(px[y][i]>>2);b4|=(px[y][i]&3)<<(2*i);}data[o+4]=(byte)b4;data[o+5]=(byte)0x5a;}
        LiveRawFrame.setEnabled(true);
        try {
            LiveRawFrame.publish(ByteBuffer.wrap(data),4,2,6,0,1023,null,null,null,null,1,1,null,1,100,6400,true,0,0,android.graphics.ImageFormat.RAW10);
            LiveRawFrame.Frame f=LiveRawFrame.acquire();
            assertEquals(8,f.rowStride);assertEquals(16,f.buffer.remaining());
            java.nio.ShortBuffer v=f.buffer.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer();
            for(int y=0;y<2;y++)for(int i=0;i<4;i++)assertEquals(px[y][i],v.get(y*4+i)&0xffff);
        } finally {LiveRawFrame.setEnabled(false);}
    }
    @Test public void raw12FramesAreUnpackedAndShortPackedPlanesRejected() {
        int[] px={4095,2048,17,3000};
        byte[] data=new byte[6];
        for(int i=0;i<2;i++){int a=px[2*i],b=px[2*i+1];data[3*i]=(byte)(a>>4);data[3*i+1]=(byte)(b>>4);data[3*i+2]=(byte)((a&15)|((b&15)<<4));}
        LiveRawFrame.setEnabled(true);
        try {
            byte[] two=new byte[12];System.arraycopy(data,0,two,0,6);System.arraycopy(data,0,two,6,6);
            LiveRawFrame.publish(ByteBuffer.wrap(two),4,2,6,0,4095,null,null,null,null,1,1,null,1,100,6400,true,0,0,android.graphics.ImageFormat.RAW12);
            java.nio.ShortBuffer v=LiveRawFrame.acquire().buffer.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer();
            for(int i=0;i<4;i++){assertEquals(px[i],v.get(i)&0xffff);assertEquals(px[i],v.get(4+i)&0xffff);}
            int version=LiveRawFrame.getVersion();
            LiveRawFrame.publish(ByteBuffer.wrap(new byte[10]),4,2,6,0,4095,null,null,null,null,1,1,null,1,100,6400,true,0,0,android.graphics.ImageFormat.RAW12);
            assertEquals(version,LiveRawFrame.getVersion());
        } finally {LiveRawFrame.setEnabled(false);}
    }
    private void publish(int value){LiveRawFrame.publish(ByteBuffer.wrap(new byte[]{(byte)value,0,0,0,0,0,0,0}),2,2,4,0,1023,new float[4],new float[]{1,1,1},new float[]{1,0,0,0,1,0,0,0,1});}
}

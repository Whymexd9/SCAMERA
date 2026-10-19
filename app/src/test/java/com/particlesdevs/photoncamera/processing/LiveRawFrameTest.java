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
    /**
     * P194: a plain Bayer frame of 30 MP or more (the X100 / X300 Ultra's hardware-remosaic FULLRES) is published as its sparse
     * Bayer bin: (w/2) x (h/2), block 1, the same CFA phase; a smaller Bayer frame is published as it is.
     */
    @Test public void aLargeBayerFrameIsPublishedAsItsSparseBayerBin() {
        int w = 6000, h = 5000;
        ByteBuffer plane = ByteBuffer.allocate(w * h * 2).order(java.nio.ByteOrder.nativeOrder());
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
            plane.putShort((y * w + x) * 2, (short) (100 * ((y % 2) * 2 + x % 2 + 1) + (y % 4 >= 2 || x % 4 >= 2 ? 7 : 0)));
        LiveRawFrame.setEnabled(true);
        try {
            LiveRawFrame.publish(plane,w,h,w*2,2,1023,null,null,null,null,1,1,null,1,100,6400,true,0,0,android.graphics.ImageFormat.RAW_SENSOR);
            LiveRawFrame.Frame f=LiveRawFrame.acquire();
            assertNotNull(f);
            assertEquals(w/2,f.width);assertEquals(h/2,f.height);assertEquals(w,f.rowStride);assertEquals(1,f.cfaBlock);assertEquals(2,f.cfaPattern);
            java.nio.ShortBuffer v=f.buffer.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer();
            for(int y:new int[]{0,1,2,3,h/2-2,h/2-1})for(int x:new int[]{0,1,2,3,w/2-2,w/2-1})
                assertEquals("CFA phase kept, only the kept quads", 100*((y%2)*2+x%2+1), v.get(y*(w/2)+x));
            assertEquals(0,plane.position());
        } finally {LiveRawFrame.setEnabled(false);}
    }
    private void publish(int value){LiveRawFrame.publish(ByteBuffer.wrap(new byte[]{(byte)value,0,0,0,0,0,0,0}),2,2,4,0,1023,new float[4],new float[]{1,1,1},new float[]{1,0,0,0,1,0,0,0,1});}

    /** P266: «Разрешение видоискателя RAW» - the plain Bayer frame halved (one 2 x 2 quad of each 4 x 4) until it fits. */
    @Test
    public void theViewfinderSizeHalvesAPlainBayerFrame() {
        assertEquals(0, LiveRawFrame.halvings(4096, 3072, 0));
        assertEquals(0, LiveRawFrame.halvings(1920, 1440, 1920));
        assertEquals(1, LiveRawFrame.halvings(4096, 3072, 2880));
        assertEquals(2, LiveRawFrame.halvings(4096, 3072, 1440));
        assertEquals(3, LiveRawFrame.halvings(4096, 3072, 640));
        final int w = 8, h = 8;
        java.nio.ByteBuffer in = java.nio.ByteBuffer.allocateDirect(w * h * 2).order(java.nio.ByteOrder.nativeOrder());
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) in.putShort((short) (y * 100 + x));
        in.flip();
        java.nio.ByteBuffer out = LiveRawFrame.halveBayer(in, w, h, w * 2,
                java.nio.ByteBuffer.allocateDirect(w * h).order(java.nio.ByteOrder.nativeOrder()));
        java.nio.ShortBuffer s = out.asShortBuffer();
        // rows 0, 1, 4, 5 and columns 0, 1, 4, 5: the CFA phase stays (an even row / column stays even)
        int[] keep = {0, 1, 4, 5};
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) assertEquals(keep[y] * 100 + keep[x], s.get(y * 4 + x));
    }

}

package com.particlesdevs.photoncamera.processing;
import java.nio.ByteBuffer;
import org.junit.Test;
import static org.junit.Assert.*;
public class LiveRawFrameTest {
    @Test public void captureCannotOverwriteFrameBeingUploaded(){
        LiveRawFrame.pendingNs=Long.MAX_VALUE; // no replacement by age, however slow the machine
        LiveRawFrame.setEnabled(true);
        try{
            publish(7);LiveRawFrame.Frame read=LiveRawFrame.acquire();
            publish(11);publish(13);publish(17);
            assertEquals(7,read.buffer.get(0));
            // P314 drop-when-busy: 13 and 17 came while 11 waited for the GL thread - not copied, 11 is the next frame
            assertEquals(11,LiveRawFrame.acquire().buffer.get(0));
            assertEquals("the frame taken stays the GL thread's: 7's buffer is not refilled while 11 is uploaded",7,read.buffer.get(0));
            publish(19);
            assertEquals(19,LiveRawFrame.acquire().buffer.get(0));
        }finally{LiveRawFrame.setEnabled(false);LiveRawFrame.pendingNs=LiveRawFrame.PENDING_NS;}
        assertNull(LiveRawFrame.acquire());
    }

    /**
     * P314 (owner, 2026-10-21: the reference viewfinder «обработку он никак не замедляет»): while the GL thread has not taken the
     * last frame the camera thread copies nothing (takesFrame false, counted) - its copies follow the viewfinder's draws.
     */
    @Test public void aFrameWaitingForTheViewfinderIsNotReplaced(){
        LiveRawFrame.pendingNs=Long.MAX_VALUE; // no replacement by age, however slow the machine
        LiveRawFrame.setEnabled(true);
        try{
            assertTrue("nothing waits: a frame is taken",LiveRawFrame.takesFrame());
            publish(21);int version=LiveRawFrame.getVersion();long dropped=LiveRawFrame.droppedFrames();
            assertFalse(LiveRawFrame.takesFrame());
            publish(23);publish(25);
            assertEquals("not copied",version,LiveRawFrame.getVersion());
            assertEquals("each skipped frame counted once",dropped+3,LiveRawFrame.droppedFrames());
            assertTrue("the waiting frame is the one acquire returns",LiveRawFrame.peekSession()>=0);
            LiveRawFrame.Frame f=LiveRawFrame.acquire();
            assertEquals(21,f.buffer.get(0));assertEquals(version,f.version);
            assertTrue("taken: the next frame is copied",LiveRawFrame.takesFrame());
            publish(27);assertEquals(version+1,LiveRawFrame.getVersion());
        }finally{LiveRawFrame.setEnabled(false);LiveRawFrame.pendingNs=LiveRawFrame.PENDING_NS;}
        assertTrue("disabled: nothing is taken",!LiveRawFrame.takesFrame());
    }

    /** P314: a waiting frame is replaced once it is PENDING_NS old (a viewfinder not drawing RAW keeps a fresh frame). */
    @Test public void aWaitingFrameIsReplacedOnceItIsOld() throws Exception {
        assertTrue(LiveRawFrame.pending(true,0));
        assertTrue(LiveRawFrame.pending(true,LiveRawFrame.PENDING_NS-1));
        assertFalse(LiveRawFrame.pending(true,LiveRawFrame.PENDING_NS));
        assertFalse("taken: never pending",LiveRawFrame.pending(false,0));
        assertFalse("a clock step back is no reason to hold a frame",LiveRawFrame.pending(true,-1));
        LiveRawFrame.setEnabled(true);
        try{
            publish(31);
            Thread.sleep(LiveRawFrame.PENDING_NS/1_000_000L+20);
            publish(33);
            assertEquals(33,LiveRawFrame.acquire().buffer.get(0));
        }finally{LiveRawFrame.setEnabled(false);}
    }

    /** P314 triple buffer: acquire copies nothing - without a new frame it hands the same buffer again. */
    @Test public void acquireHandsTheSameBufferUntilANewFrame(){
        LiveRawFrame.setEnabled(true);
        try{
            publish(41);
            LiveRawFrame.Frame a=LiveRawFrame.acquire(),b=LiveRawFrame.acquire();
            assertEquals(a.version,b.version);
            a.buffer.put(1,(byte)99);
            assertEquals("one buffer, not a copy",99,b.buffer.get(1));
            publish(43);
            LiveRawFrame.Frame c=LiveRawFrame.acquire();
            assertEquals(43,c.buffer.get(0));assertEquals(0,c.buffer.get(1));
            c.buffer.put(1,(byte)77);
            assertEquals("the new frame is another buffer",99,a.buffer.get(1));
            // three buffers turn round: the GL thread's never reaches the writer
            publish(45);LiveRawFrame.Frame d=LiveRawFrame.acquire();
            assertEquals(45,d.buffer.get(0));
            assertEquals("the frame taken before stays untouched until the next acquire",77,c.buffer.get(1));
        }finally{LiveRawFrame.setEnabled(false);}
    }

    /**
     * P314: a 2x ISZ Quad / 4x Tetra frame (below the full-resolution sizes) is published as its cell means - (w / block) x
     * (h / block), block 1, cellMean - unless the viewfinder size is «Full» (cap -1: as it came).
     */
    @Test public void aMosaicFrameIsPublishedAsItsCellMeans() throws Exception {
        for(int block:new int[]{2,4}){
            int w=8*block,h=4*block;
            ByteBuffer plane=ByteBuffer.allocate(w*h*2).order(java.nio.ByteOrder.nativeOrder());
            java.util.Random random=new java.util.Random(314+block);
            int[] v=new int[w*h];
            for(int i=0;i<w*h;i++){v[i]=64+random.nextInt(960);plane.putShort(i*2,(short)v[i]);}
            // each publish in a session of its own: a session change resets the bins' pacing (binIntervalNs after the last bin)
            LiveRawFrame.setEnabled(false);LiveRawFrame.setEnabled(true);
            try{
                LiveRawFrame.publish(plane,w,h,w*2,3,1023,new float[]{64,64,64,64},new float[]{2,1,1.5f},null,null,1,1,null,block,100,6400,true,0,0,android.graphics.ImageFormat.RAW_SENSOR);
                LiveRawFrame.Frame f=LiveRawFrame.acquire();
                assertNotNull("block "+block,f);
                assertEquals(w/block,f.width);assertEquals(h/block,f.height);assertEquals(1,f.cfaBlock);assertTrue(f.cellMean);assertEquals(3,f.cfaPattern);
                java.nio.ShortBuffer s=f.buffer.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer();
                for(int y=0;y<h/block;y++)for(int x=0;x<w/block;x++){
                    int sum=0;for(int dy=0;dy<block;dy++)for(int dx=0;dx<block;dx++)sum+=v[(y*block+dy)*w+x*block+dx];
                    assertEquals("block "+block+" cell "+x+","+y,(sum+block*block/2)/(block*block),s.get(y*(w/block)+x)&0xffff);
                }
                assertEquals(0,plane.position());
                LiveRawFrame.setMaxLongSide(-1);
                LiveRawFrame.publish(plane,w,h,w*2,3,1023,new float[]{64,64,64,64},new float[]{2,1,1.5f},null,null,1,1,null,block,100,6400,true,0,0,android.graphics.ImageFormat.RAW_SENSOR);
                f=LiveRawFrame.acquire();
                assertEquals("«Full»: as it came",w,f.width);assertEquals(block,f.cfaBlock);assertFalse(f.cellMean);
                assertEquals(v[w+3],f.buffer.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer().get(w+3)&0xffff);
                LiveRawFrame.setMaxLongSide(1920);
                LiveRawFrame.setEnabled(false);LiveRawFrame.setEnabled(true);
                LiveRawFrame.publish(plane,w,h,w*2,3,1023,new float[]{64,64,64,64},new float[]{2,1,1.5f},null,null,1,1,null,block,100,6400,true,0,0,android.graphics.ImageFormat.RAW_SENSOR);
                f=LiveRawFrame.acquire();
                assertEquals("a size cap bins the mosaic too (its means fit 1920)",w/block,f.width);assertTrue(f.cellMean);
            }finally{LiveRawFrame.setMaxLongSide(0);LiveRawFrame.setEnabled(false);}
        }
        assertTrue(LiveRawFrame.binsCellMeans(4096,3072,2,0));
        assertTrue(LiveRawFrame.binsCellMeans(4080,3072,4,0));
        assertFalse("«Full»",LiveRawFrame.binsCellMeans(4096,3072,2,-1));
        assertFalse("a plain Bayer frame",LiveRawFrame.binsCellMeans(4096,3072,1,0));
        assertFalse("50 MP Quad: the P181 sparse bin as before",LiveRawFrame.binsCellMeans(8192,6144,2,0));
        assertFalse("not whole cells",LiveRawFrame.binsCellMeans(4090,3072,4,0));
    }

    /**
     * P314: the bin is lossless for the viewfinder - rawdevelop_fs's getFastColor (one colour per 2 x 2 of macro pixels, each the
     * mean of a block x block cell) reads, on the binned frame with block 1, the same means it read on the mosaic with its block,
     * for every macro pixel, up to the 1/2 DN rounding of the mean.
     */
    @Test public void aBinnedMosaicGivesTheViewfinderTheSameColours() throws Exception {
        for(int block:new int[]{2,4}){
            int w=16*block,h=8*block;
            ByteBuffer plane=ByteBuffer.allocate(w*h*2).order(java.nio.ByteOrder.nativeOrder());
            java.util.Random random=new java.util.Random(31400+block);
            for(int i=0;i<w*h;i++)plane.putShort(i*2,(short)(64+random.nextInt(960)));
            LiveRawFrame.setEnabled(false);LiveRawFrame.setEnabled(true);
            try{
                LiveRawFrame.publish(plane,w,h,w*2,0,1023,null,null,null,null,1,1,null,block,100,6400,true,0,0,android.graphics.ImageFormat.RAW_SENSOR);
                LiveRawFrame.Frame binned=LiveRawFrame.acquire();
                assertTrue(binned.cellMean);
                java.nio.ShortBuffer full=plane.duplicate().order(java.nio.ByteOrder.nativeOrder()).asShortBuffer();
                java.nio.ShortBuffer bin=binned.buffer.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer();
                for(int my=0;my<h/block;my++)for(int mx=0;mx<w/block;mx++){
                    // getFastColor(mx, my): the quad at (mx & ~1, my & ~1) and its three neighbours, each getMacroPixel
                    for(int q=0;q<4;q++){
                        int qx=(mx&~1)+(q&1),qy=(my&~1)+(q>>1);
                        assertEquals("block "+block+" macro "+qx+","+qy,macro(full,w,block,qx,qy),macro(bin,w/block,1,qx,qy),0.5);
                    }
                }
            }finally{LiveRawFrame.setEnabled(false);}
        }
    }

    /** rawdevelop_fs getMacroPixel: the mean of the block x block sites of macro pixel (mx, my). */
    private static double macro(java.nio.ShortBuffer s,int rowPx,int block,int mx,int my){
        double sum=0;for(int y=0;y<block;y++)for(int x=0;x<block;x++)sum+=s.get((my*block+y)*rowPx+mx*block+x)&0xffff;
        return sum/(block*block);
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

    /** The site a halving keeps: one 2 x 2 quad of each 4 x 4. */
    private static int kept(int x) { return 4 * (x >> 1) + (x & 1); }

    /** P316 (settings audit): «Разрешение видоискателя RAW» halves a packed RAW10 frame too (it was unpacked at its full size). */
    @Test
    public void theViewfinderSizeHalvesAPackedFrame() {
        final int w = 64, h = 32, row = w * 5 / 4;
        byte[] data = new byte[row * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x += 4) {
            int o = y * row + x * 5 / 4, low = 0;
            for (int i = 0; i < 4; i++) { int v = (y * 7 + (x + i) * 3) % 1024; data[o + i] = (byte) (v >> 2); low |= (v & 3) << (2 * i); }
            data[o + 4] = (byte) low;
        }
        LiveRawFrame.setEnabled(true);
        try {
            LiveRawFrame.setMaxLongSide(16);
            LiveRawFrame.publish(ByteBuffer.wrap(data), w, h, row, 0, 1023, null, null, null, null, 1, 1, null, 1, 100, 6400, true, 0, 0, android.graphics.ImageFormat.RAW10);
            LiveRawFrame.Frame f = LiveRawFrame.acquire();
            assertNotNull(f);
            assertEquals(w / 4, f.width); assertEquals(h / 4, f.height); assertEquals(w / 4 * 2, f.rowStride);
            java.nio.ShortBuffer s = f.buffer.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer();
            for (int y = 0; y < h / 4; y++) for (int x = 0; x < w / 4; x++)
                assertEquals(x + "," + y, (kept(kept(y)) * 7 + kept(kept(x)) * 3) % 1024, s.get(y * (w / 4) + x) & 0xffff);
        } finally { LiveRawFrame.setMaxLongSide(0); LiveRawFrame.setEnabled(false); }
    }

    /** P316 (settings audit): ... and a Quad / Tetra frame's cell means (the steps did nothing there, only «Full»). */
    @Test
    public void theViewfinderSizeHalvesAMosaicsCellMeans() {
        for (int block : new int[]{2, 4}) {
            final int w = 32 * block, h = 16 * block;
            ByteBuffer plane = ByteBuffer.allocate(w * h * 2).order(java.nio.ByteOrder.nativeOrder());
            for (int i = 0; i < w * h; i++) plane.putShort(i * 2, (short) (64 + (i * 37) % 900));
            LiveRawFrame.setEnabled(false); LiveRawFrame.setEnabled(true);
            try {
                LiveRawFrame.publish(plane, w, h, w * 2, 3, 1023, new float[]{64, 64, 64, 64}, new float[]{2, 1, 1.5f}, null, null, 1, 1, null, block, 100, 6400, true, 0, 0, android.graphics.ImageFormat.RAW_SENSOR);
                LiveRawFrame.Frame means = LiveRawFrame.acquire();
                assertNotNull(means);
                assertEquals(32, means.width); assertTrue(means.cellMean);
                java.nio.ShortBuffer m = means.buffer.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer();
                int[] full = new int[32 * 16];
                for (int i = 0; i < full.length; i++) full[i] = m.get(i) & 0xffff;
                LiveRawFrame.setMaxLongSide(8);
                LiveRawFrame.setEnabled(false); LiveRawFrame.setEnabled(true);
                LiveRawFrame.publish(plane, w, h, w * 2, 3, 1023, new float[]{64, 64, 64, 64}, new float[]{2, 1, 1.5f}, null, null, 1, 1, null, block, 100, 6400, true, 0, 0, android.graphics.ImageFormat.RAW_SENSOR);
                LiveRawFrame.Frame f = LiveRawFrame.acquire();
                assertNotNull(f);
                assertEquals("block " + block, 8, f.width); assertEquals(4, f.height); assertEquals(1, f.cfaBlock);
                assertTrue(f.cellMean); assertEquals(3, f.cfaPattern);
                java.nio.ShortBuffer s = f.buffer.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer();
                for (int y = 0; y < 4; y++) for (int x = 0; x < 8; x++)
                    assertEquals("block " + block + " " + x + "," + y, full[kept(kept(y)) * 32 + kept(kept(x))], s.get(y * 8 + x) & 0xffff);
            } finally { LiveRawFrame.setMaxLongSide(0); LiveRawFrame.setEnabled(false); }
        }
    }

}

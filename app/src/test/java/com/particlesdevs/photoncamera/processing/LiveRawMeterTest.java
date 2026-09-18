package com.particlesdevs.photoncamera.processing;
import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.Assert.*;
public class LiveRawMeterTest {
    private LiveRawFrame.Frame frame(int sample,boolean automatic) {
        LiveRawFrame.Frame f=new LiveRawFrame.Frame();f.width=f.height=16;f.rowStride=32;
        f.buffer=ByteBuffer.allocateDirect(512).order(ByteOrder.nativeOrder());
        for(int i=0;i<256;i++)f.buffer.putShort((short)sample);f.buffer.flip();
        f.whiteLevel=1023;f.blackLevel=new float[]{64,64,64,64};f.crop=new float[]{0,0,1,1};f.autoExposure=automatic;return f;
    }
    @Test public void darkSceneBrightensAndManualExposureRemainsUnderUserControl() {
        LiveRawMeter m=new LiveRawMeter();m.update(frame(75,true));assertTrue(m.exposure>1);assertTrue(m.exposure<=5);
        m.update(frame(75,false));assertEquals(1,m.exposure,0);
    }
    @Test public void lensResetDoesNotKeepExposureFromTheDarkModule() {
        LiveRawMeter m=new LiveRawMeter();m.update(frame(70,true));float dark=m.exposure;
        m.reset();m.update(frame(800,true));assertTrue(m.exposure<dark);assertTrue(Float.isFinite(m.contrast));
    }
    @Test public void meterUsesAllCfaSitesAndSupportsBlackFrames() {
        LiveRawMeter m=new LiveRawMeter();LiveRawFrame.Frame f=frame(64,true);m.update(f);
        assertTrue(Float.isFinite(m.exposure));for(float v:m.multipliers)assertTrue(Float.isFinite(v));
        f=frame(500,true);f.cfaBlock=4;m.reset();m.update(f);assertTrue(m.exposure<1);
    }
}

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
        f.wbGains=new float[]{1,1,1};f.shading=new float[]{1,1,1};f.shadingWidth=f.shadingHeight=1;
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
    /** P212h (A6 = M11): an OPPO native frame (AE off, the own AE) with a deficit shows it; without one it stays at gain 1. */
    @Test public void anAutomaticFrameWithADeficitGivesExposureAboveOneHeldToItsRange() {
        LiveRawMeter m=new LiveRawMeter();
        LiveRawFrame.Frame night=frame(66,true);night.minDisplayGain=1f;night.maxDisplayGain=4f; // 2 EV short of the meter
        m.update(night);assertTrue(m.exposure>1);assertTrue(m.exposure<=4);
        for(int i=0;i<60;i++)m.update(night);
        assertEquals("the meter wants more than the deficit: held at 2^deficit",4f,m.exposure,0.1f);
        LiveRawMeter day=new LiveRawMeter();
        LiveRawFrame.Frame noDeficit=frame(66,true);noDeficit.minDisplayGain=1f;noDeficit.maxDisplayGain=1f;
        day.update(noDeficit);assertEquals("no deficit: today's viewfinder",1f,day.exposure,0f);
        LiveRawFrame.Frame bright=frame(900,true);bright.minDisplayGain=1f;bright.maxDisplayGain=4f;
        LiveRawMeter b=new LiveRawMeter();b.update(bright);assertEquals("never darker than gain 1",1f,b.exposure,0f);
        // the default range leaves every other route's gain as it was
        LiveRawMeter plain=new LiveRawMeter(),ref=new LiveRawMeter();
        plain.update(frame(75,true));
        LiveRawFrame.Frame unbounded=frame(75,true);unbounded.minDisplayGain=0f;unbounded.maxDisplayGain=Float.POSITIVE_INFINITY;
        ref.update(unbounded);assertEquals(ref.exposure,plain.exposure,0f);
        assertEquals(2f,LiveRawMeter.held(3f,1f,2f),0f);assertEquals(1f,LiveRawMeter.held(.5f,1f,2f),0f);assertEquals(.5f,LiveRawMeter.held(.5f,0f,Float.POSITIVE_INFINITY),0f);
    }
    /** P212h (A6): SceneLuma from the RAW viewfinder: the frame's own median before any gain, gamma 1/2.2 (ExposurePriority.linear inverts it). */
    @Test public void theSceneLumaIsTheFramesMedianBeforeTheGain() {
        LiveRawMeter m=new LiveRawMeter();assertTrue(Double.isNaN(m.sceneLuma()));
        LiveRawFrame.Frame f=frame(64+96,true);m.update(f); // (160 - 64) / (1023 - 64) = 0.1001
        assertEquals(Math.pow(96/959.0,1/2.2),m.sceneLuma(),2e-3);
        LiveRawFrame.Frame g=frame(64+192,false);g.minDisplayGain=1f;g.maxDisplayGain=4f;m.update(g); // twice the exposure: twice linear
        assertEquals(2.0,Math.pow(m.sceneLuma(),2.2)/(96/959.0),0.02);
        m.reset();assertTrue(Double.isNaN(m.sceneLuma()));
    }
    @Test public void meterUsesAllCfaSitesAndSupportsBlackFrames() {
        LiveRawMeter m=new LiveRawMeter();LiveRawFrame.Frame f=frame(64,true);m.update(f);
        assertTrue(Float.isFinite(m.exposure));for(float v:m.multipliers)assertTrue(Float.isFinite(v));
        f=frame(500,true);f.cfaBlock=4;m.reset();m.update(f);assertTrue(m.exposure<1);
    }
}

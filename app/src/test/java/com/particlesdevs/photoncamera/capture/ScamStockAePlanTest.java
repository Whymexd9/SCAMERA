package com.particlesdevs.photoncamera.capture;

import android.app.Application;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class ScamStockAePlanTest {
    private ScamStockAe.Plan plan() throws Exception {
        byte[] input=new byte[232], state=new byte[2352], data=new byte[100];
        ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).putInt(0xc4,9);
        ByteBuffer.wrap(state).order(ByteOrder.LITTLE_ENDIAN).putLong(0xb8,0xc80000);
        ByteBuffer p=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        float[] time={10000000,2000000,1000000,20000000}, gain={4,2,1,4};
        for(int i=0;i<4;i++)p.putFloat(i*16,time[i]).putFloat(i*16+4,gain[i]);
        p.putInt(72,65536).putInt(76,4).putInt(80,0);
        return new ScamStockAe.Plan(data,new ScamAeContext(input,state,data),1,100,100,1);
    }
    private TotalCaptureResult result(long timestamp,float gain,float shutter) {
        TotalCaptureResult result=mock(TotalCaptureResult.class);
        float[] aec=new float[35];aec[2]=gain;aec[14]=shutter;
        when(result.get(any())).thenAnswer(invocation -> {
            CaptureResult.Key<?> key=invocation.getArgument(0);
            if(key.equals(CaptureResult.SENSOR_TIMESTAMP))return timestamp;
            if(key.getName().equals("vivo.control.Vivo3rdAlgoAECFrameControl"))return aec;
            return null;
        });
        return result;
    }
    @Test public void acceptsMatchedPastNormal() throws Exception {
        plan().verifyZslNormal(result(99,4,10000000),100);
    }
    @Test public void acceptsEqualProductWithDifferentGainSplit() throws Exception {
        // ZSL N: the preview AE may split the same exposure product differently (0.00 EV).
        plan().verifyZslNormal(result(99,2,20000000),100);
    }
    @Test public void rejectsZslProductFarFromThePlan() throws Exception {
        ScamStockAe.Plan plan=plan();
        assertThrows(IllegalStateException.class,()->plan.verifyZslNormal(result(99,2,40000000),100));
    }
    @Test public void rejectsNormalAfterCutoff() throws Exception {
        ScamStockAe.Plan plan=plan();
        assertThrows(IllegalStateException.class,()->plan.verifyZslNormal(result(101,4,10000000),100));
    }
    @Test public void rejectsMissingVendorGain() throws Exception {
        ScamStockAe.Plan plan=plan();
        TotalCaptureResult result=mock(TotalCaptureResult.class);
        when(result.get(CaptureResult.SENSOR_TIMESTAMP)).thenReturn(99L);
        assertThrows(IllegalStateException.class,()->plan.verifyZslNormal(result,100));
    }
    @Test public void firstFutureRequestUsesLongSlot() throws Exception {
        CaptureRequest request=mock(CaptureRequest.class);
        when(request.getTag()).thenReturn(new ImageFrame.ScamCaptureTag(1,0,ImageFrame.CaptureRole.LONG));
        ScamStockAe.Plan plan=plan();
        plan.verify(request,result(101,4,20000000));
        assertThrows(IllegalStateException.class,()->plan.verify(request,result(101,4,10000000)));
    }
}

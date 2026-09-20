package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import com.particlesdevs.photoncamera.util.Log;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class VivoNiceCaptureLog {
    private static final Set<String> VENDOR = new HashSet<>(Arrays.asList(
            "vivo.parameter.VivoAlgoAECFrameControl", "vivo.parameter.VivoAlgoAECShortFrameControl",
            "vivo.parameter.VivoAlgoCaptureFrameControl", "vivo.parameter.rawHDRParams",
            "vivo.parameter.niceHdrExpEVMode", "vivo.parameter.rawHDRCaptureDrcGain"));
    private VivoNiceCaptureLog() {}
    public static void request(CaptureRequest request, int index) {
        Log.i("NICE_CAPTURE", "request="+index+" role="+request.getTag()
                +" exposureNs="+request.get(CaptureRequest.SENSOR_EXPOSURE_TIME)
                +" ISO="+request.get(CaptureRequest.SENSOR_SENSITIVITY)
                +" aeMode="+request.get(CaptureRequest.CONTROL_AE_MODE)
                +" aeLock="+request.get(CaptureRequest.CONTROL_AE_LOCK)
                +" enableZsl="+request.get(CaptureRequest.CONTROL_ENABLE_ZSL)
                +" frameDurationNs="+request.get(CaptureRequest.SENSOR_FRAME_DURATION));
    }
    public static void result(CaptureRequest request, TotalCaptureResult result, long cutoff) {
        Log.i("NICE_CAPTURE", "resultFrame="+result.getFrameNumber()+" role="+request.getTag()
                +" timestamp="+result.get(CaptureResult.SENSOR_TIMESTAMP)+" zslCutoff="+cutoff
                +" requestedExposureNs="+request.get(CaptureRequest.SENSOR_EXPOSURE_TIME)
                +" actualExposureNs="+result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                +" requestedISO="+request.get(CaptureRequest.SENSOR_SENSITIVITY)
                +" actualISO="+result.get(CaptureResult.SENSOR_SENSITIVITY)
                +" aeState="+result.get(CaptureResult.CONTROL_AE_STATE)
                +" frameDurationNs="+result.get(CaptureResult.SENSOR_FRAME_DURATION)
                +" rollingShutterSkewNs="+result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW));
        try {
            Set<String> found=new HashSet<>();
            for(CaptureResult.Key<?> key:result.getKeys()) if(VENDOR.contains(key.getName())) {
                Object value=result.get(key);found.add(key.getName());
                Log.i("NICE_CAPTURE", "vendor "+key.getName()+"="+valueText(value));
            }
            Set<String> absent=new HashSet<>(VENDOR);absent.removeAll(found);
            Log.i("NICE_CAPTURE", "vendorPlanFieldsAbsent="+absent);
        } catch(RuntimeException error) { Log.w("NICE_CAPTURE", "Vendor diagnostic read failed: "+error); }
    }
    private static String valueText(Object value) {
        if(value==null || !value.getClass().isArray())return String.valueOf(value);
        int count=Array.getLength(value);StringBuilder text=new StringBuilder("[");
        for(int i=0;i<Math.min(count,64);i++){if(i>0)text.append(',');text.append(Array.get(value,i));}
        return text.append("] length=").append(count).toString();
    }
}

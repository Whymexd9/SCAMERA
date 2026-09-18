package com.particlesdevs.photoncamera.circularbarlib.camera;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.RggbChannelVector;

/** Kelvin -> sensor neutral using the camera's own XYZ and calibration matrices.
 * CIE xy approximation: Kang et al. (2002), as documented by Colour Science:
 * https://colour.readthedocs.io/en/v0.3.9/_modules/colour/temperature/cct.html
 * Matrix directions and manual control contract: Android Camera2 characteristics/requests.
 */
public final class ManualWhiteBalance {
    private ManualWhiteBalance() {}
    public static boolean isSupported(CameraCharacteristics camera) {
        if(camera==null || camera.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)==null)return false;
        int[] modes=camera.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        int[] caps=camera.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        boolean off=false,manual=false;
        if(modes!=null)for(int m:modes)off|=m==CaptureRequest.CONTROL_AWB_MODE_OFF;
        if(caps!=null)for(int c:caps)manual|=c==CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING;
        return off && manual;
    }
    public static double[] xyz(int kelvin) {
        double t=Math.max(2000,Math.min(10000,kelvin)),t2=t*t,t3=t2*t;
        double x=t<=4000 ? -0.2661239e9/t3-0.2343589e6/t2+0.8776956e3/t+0.179910
                : -3.0258469e9/t3+2.1070379e6/t2+0.2226347e3/t+0.240390;
        double y=t<=2222 ? -1.1063814*x*x*x-1.34811020*x*x+2.18555832*x-0.20219683
                : t<=4000 ? -0.9549476*x*x*x-1.37418593*x*x+2.09137015*x-0.16748867
                : 3.0817580*x*x*x-5.8733867*x*x+3.75112997*x-0.37001483;
        return new double[]{x/y,1,(1-x-y)/y};
    }
    private static double temperature(Number illuminant) {
        if(illuminant==null)return 0;
        switch(illuminant.intValue()) {
            case 1:case 4:case 9:case 20:return 5500;
            case 2:case 14:return 4150;
            case 3:return 2856;
            case 10:case 21:return 6500;
            case 11:case 22:return 7500;
            case 12:return 6430;
            case 13:return 5000;
            case 15:return 3450;
            case 17:return 2856;
            case 18:return 4874;
            case 19:return 6774;
            case 23:return 5000;
            case 24:return 3200;
            default:return 0;
        }
    }
    private static double[] multiply(ColorSpaceTransform m,double[] v) {
        if(m==null)return v.clone();
        double[] out=new double[3];
        for(int row=0;row<3;row++)for(int col=0;col<3;col++)out[row]+=m.getElement(col,row).doubleValue()*v[col];
        return out;
    }
    public static RggbChannelVector gains(CameraCharacteristics camera,int kelvin) {
        double[] white=xyz(kelvin);
        ColorSpaceTransform c1=camera.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1);
        ColorSpaceTransform c2=camera.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2);
        double[] n1=multiply(camera.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1),multiply(c1,white));
        double t1=temperature(camera.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1));
        double t2=temperature(camera.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2));
        if(c2!=null && t1>0 && t2>0 && t1!=t2) {
            double weight=Math.max(0,Math.min(1,(1.0/kelvin-1.0/t1)/(1.0/t2-1.0/t1)));
            double[] n2=multiply(camera.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2),multiply(c2,white));
            for(int i=0;i<3;i++)n1[i]=n1[i]*(1-weight)+n2[i]*weight;
        }
        double maximum=Math.max(n1[0],Math.max(n1[1],n1[2]));
        float[] gains=new float[3];
        for(int i=0;i<3;i++) {
            if(!Double.isFinite(n1[i]) || n1[i]<=0)throw new IllegalArgumentException("Invalid sensor white balance calibration");
            gains[i]=(float)Math.max(1,Math.min(8,maximum/n1[i]));
        }
        return new RggbChannelVector(gains[0],gains[1],gains[1],gains[2]);
    }
}

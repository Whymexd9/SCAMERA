package com.particlesdevs.photoncamera.capture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Sparse sensor-domain histogram AE. SCAMERA policy, not Google's learned AE. */
public final class SceneDistributionMeter {
    private SceneDistributionMeter() {}
    public static double[] measure(ByteBuffer buffer,int width,int height,int stride,int block,double black,double white) {
        if(buffer==null || width<16 || height<16 || stride<width*2 || white<=black
                || (block!=1 && block!=2 && block!=4))return null;
        ByteBuffer raw=buffer.duplicate().slice().order(ByteOrder.LITTLE_ENDIAN);
        if((long)(height-1)*stride+width*2L>raw.remaining())return null;
        int[] mid=new int[1024],high=new int[1024];int count=0,period=2*block;
        int dx=Math.max(period,(width/48/period)*period),dy=Math.max(period,(height/36/period)*period);
        for(int y=0;y+period<height;y+=dy)for(int x=0;x+period<width;x+=dx){
            double mean=0,peak=0;
            for(int c=0;c<4;c++){
                int offset=(y+(c/2)*block)*stride+(x+(c%2)*block)*2;
                double v=Math.max(0,Math.min(1,((raw.getShort(offset)&65535)-black)/(white-black)));
                mean+=v*.25;peak=Math.max(peak,v);
            }
            mid[(int)(mean*1023)]++;high[(int)(peak*1023)]++;count++;
        }
        if(count==0)return null;
        return new double[]{percentile(mid,count,.5),percentile(high,count,.99)};
    }
    private static double percentile(int[] hist,int count,double fraction){
        int target=(int)Math.ceil(count*fraction),n=0;
        for(int i=0;i<hist.length;i++){n+=hist[i];if(n>=target)return i/1023.0;}
        return 1;
    }
    public static int nextSteps(double median,double highlight,int current,int baseline,double step,int low,int high){
        if(!Double.isFinite(median+highlight+step) || median<0 || highlight<0 || step<=0)return current;
        double midEv=Math.log(.12/Math.max(median,.001))/Math.log(2);
        double highEv=Math.log(.95/Math.max(highlight,.001))/Math.log(2);
        double error=Math.min(midEv,highEv);
        if(Math.abs(error)<Math.max(.15,step*.55))return current;
        int target=current+(error>0?1:-1); // one camera EV step per settled update
        int limit=Math.max(1,(int)Math.floor(2/step));
        return Math.max(Math.max(low,baseline-limit),Math.min(Math.min(high,baseline+limit),target));
    }
}

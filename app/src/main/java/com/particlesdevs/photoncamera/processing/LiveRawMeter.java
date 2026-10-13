package com.particlesdevs.photoncamera.processing;

import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;

/** Percentile AE and single-frame shadow/highlight adaptation recovered from libvf_demosaic.
 * No burst fusion or sensor-exposure changes. State belongs to one camera session. */
public final class LiveRawMeter {
    private final int[] luminance = new int[4096], highlights = new int[4096];
    private float p10=-1,p50,p98;
    public float exposure=1, contrast;
    public final float[] multipliers={1,1,1,1};
    private boolean initialized;
    public void reset() { p10=-1; exposure=1; contrast=0; initialized=false; Arrays.fill(multipliers,1); }
    private static float clamp(float v,float lo,float hi) { return Math.max(lo,Math.min(v,hi)); }
    private static float log2(float v) { return (float)(Math.log(Math.max(v,1e-6))/Math.log(2)); }
    private static float percentile(int[] hist,int total,float fraction) {
        int target=Math.max(1,(int)Math.ceil(total*fraction)),sum=0;
        for(int i=0;i<hist.length;i++) { sum+=hist[i]; if(sum>=target)return i/4095f; }
        return 1;
    }
    public void update(LiveRawFrame.Frame f) {
        Arrays.fill(luminance,0); Arrays.fill(highlights,0);
        ShortBuffer raw=f.buffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer();
        int block=Math.max(1,f.cfaBlock), period=2*block;
        int left=Math.max(0,(int)(f.crop[0]*f.width)/period*period);
        int top=Math.max(0,(int)(f.crop[1]*f.height)/period*period);
        int right=Math.min(f.width,(int)((f.crop[0]+f.crop[2])*f.width));
        int bottom=Math.min(f.height,(int)((f.crop[1]+f.crop[3])*f.height));
        int dx=Math.max(period,((right-left)/32/period)*period);
        int dy=Math.max(period,((bottom-top)/32/period)*period), count=0;
        int[][] orders={{0,1,2,3},{1,0,3,2},{2,3,0,1},{3,2,1,0}};
        int[] order=orders[f.cfaPattern]; float[] sites=new float[4];
        for(int y=top;y+period<=bottom;y+=dy) for(int x=left;x+period<=right;x+=dx) {
            for(int site=0;site<4;site++) {
                float sum=0;
                for(int yy=0;yy<block;yy++)for(int xx=0;xx<block;xx++) {
                    int i=(y+(site/2)*block+yy)*(f.rowStride/2)+x+(site%2)*block+xx;
                    sum+=raw.get(i)&65535;
                }
                sites[site]=Math.max(0,(sum/(block*block)-f.blackLevel[site])/Math.max(1,f.whiteLevel-f.blackLevel[site]));
            }
            float r=sites[order[0]],g=(sites[order[1]]+sites[order[2]])*.5f,b=sites[order[3]];
            // Meter in the sensor domain, as the library does; never meter a tone-mapped readback.
            float l=.2126f*r+.7152f*g+.0722f*b;
            luminance[(int)(clamp(l,0,1)*4095)]++;
            highlights[(int)(clamp(Math.max(r,Math.max(g,b)),0,1)*4095)]++; count++;
        }
        if(count==0)return;
        float a=percentile(luminance,count,.1f),b=Math.max(.0001f,percentile(luminance,count,.5f)),c=Math.max(.001f,percentile(highlights,count,.98f));
        if(p10<0) {p10=a;p50=b;p98=c;} else {p10+=(a-p10)*.15f;p50+=(b-p50)*.15f;p98+=(c-p98)*.15f;}
        float q=1;
        if(f.iso>100) {float t=clamp(log2(f.iso/100f)/Math.max(.001f,log2(Math.max(101,f.maxAnalogIso)/100f)),0,1);q=1-t*t;}
        float target=.07f*(.2857f+.7143f*(float)Math.pow(q,.6));
        float ae=clamp((float)Math.sqrt(target/Math.max(p50,.001f)),.1f,5);
        // Protect highlights by default; metadata has already been matched to this RAW.
        if(ae>1 && p98*ae>.8f) {float safe=.8f/p98;ae=safe+(ae-safe)*.33f;}
        if(!f.autoExposure) ae=1; // Do not cancel the user's manual shutter/ISO changes.
        if(!initialized || !f.autoExposure) exposure=ae;
        else if(Math.abs(ae-exposure)/Math.max(.01f,exposure)>.015f)exposure+=(ae-exposure)*.15f;
        float d=(float)(Math.sqrt(Math.max(Math.min(exposure,p98*exposure),0))-Math.sqrt(Math.max(p10*exposure,0)));
        float z=Math.max(0,d)/1.5f;
        float mid=clamp(((float)Math.sqrt(p50*exposure)-.2f)/.22f,0,1);
        float shadow=z*(1+.5f*z)/(1+1.5f*z)*(1-.35f*mid*mid)*q;
        float high=Math.min(1,Math.max(0,.5f*log2(p98*exposure)));
        float t=clamp(1-d/.75f,0,1), boost=.2f*t*t*(3-2*t);
        float[] next={(float)Math.pow(2,-3*high),(float)Math.pow(2,-1.5f*high),1,Math.min(16,(float)Math.pow(2,2*shadow))};
        for(int i=0;i<4;i++) multipliers[i]=initialized?multipliers[i]+.15f*(next[i]-multipliers[i]):next[i];
        contrast=initialized?contrast+.15f*(boost-contrast):boost;
        initialized=true;
    }
}

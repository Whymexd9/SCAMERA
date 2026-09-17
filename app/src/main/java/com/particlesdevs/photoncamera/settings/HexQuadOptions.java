package com.particlesdevs.photoncamera.settings;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Immutable per-shot controls. Pure Java so ISO policy and transport are host-testable. */
public final class HexQuadOptions {
    public static final int HEADER_BYTES=112;
    public final int modelScale;
    public final boolean fullResolution,autoIso;
    public final float noiseOverall,noisePhoton,noiseReadout,texture;
    public final float lumaPercent,chromaPercent;
    public HexQuadOptions(int iso,int model,boolean full,float overall,float photon,float readout,
                          float luma,float chroma,boolean auto,float lowLuma,float lowChroma,
                          float highLuma,float highChroma,float textures) {
        if(iso<50||iso>12800||(model!=1&&model!=2))throw new IllegalArgumentException("Invalid HexQuad ISO/model");
        modelScale=model;fullResolution=full&&model==2;autoIso=auto;
        noiseOverall=bounded(overall,.5f,2);noisePhoton=bounded(photon,.5f,2);noiseReadout=bounded(readout,.5f,2);
        texture=bounded(textures,0,100)/100f;
        lumaPercent=auto?interpolate(iso,lowLuma,highLuma):bounded(luma,0,100);
        chromaPercent=auto?interpolate(iso,lowChroma,highChroma):bounded(chroma,0,100);
    }
    private static float bounded(float value,float low,float high){
        if(!Float.isFinite(value)||value<low||value>high)throw new IllegalArgumentException("Invalid HexQuad control");
        return value;
    }
    public static float interpolate(int iso,float low,float high){
        bounded(low,0,100);bounded(high,0,100);
        double t=Math.max(0,Math.min(1,Math.log(Math.max(1,iso)/100.0)/Math.log(32.0)));
        return (float)(low+(high-low)*t);
    }
    public int outputScale(){return fullResolution?2:1;}
    public long outputBytes(int width,int height){return (long)width*height*outputScale()*outputScale()*2;}
    public String profileKey(int iso,int red){
        // Full profile checks must never carry over to another model or VST transform.
        return "v14:"+iso+":"+red+":"+modelScale+":"+Float.floatToIntBits(noiseOverall)+":"+
                Float.floatToIntBits(noisePhoton)+":"+Float.floatToIntBits(noiseReadout);
    }
    public ByteBuffer header(int width,int height,int iso,int red,float black,float white,boolean response,float[] neutral){
        if(neutral==null||neutral.length!=3)throw new IllegalArgumentException("Invalid neutral point");
        for(float v:neutral)bounded(v,.0001f,10000);
        ByteBuffer b=ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x32515848).putInt(3).putInt(width).putInt(height).putInt(iso).putInt(red)
                .putInt(0).putInt(0).putFloat(black).putFloat(white).putInt(response?1:0).putInt(6)
                .putFloat(lumaPercent/100f).putFloat(chromaPercent/100f)
                .putFloat(neutral[0]).putFloat(neutral[1]).putFloat(neutral[2]);
        b.position(80);
        b.putInt(modelScale).putFloat(noiseOverall).putFloat(noisePhoton).putFloat(noiseReadout)
                .putFloat(texture).putInt(fullResolution?1:0);
        b.position(0);return b;
    }
}

package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.content.Context;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.util.*;

/** Camera2 burst transport for the original IMX06C forward NICE model. */
public final class VivoNiceBurst {
    final int width,height,cfa;
    private final float white;
    private final float[] black;
    private final ImageFrame[] ordered=new ImageFrame[7];
    private final float[] exposure=new float[7];
    private VivoNiceBurst(List<ImageFrame> source,Parameters p) throws IOException {
        width=p.rawSize.x;height=p.rawSize.y;cfa=p.cfaPattern;white=p.whiteLevel;black=p.blackLevel.clone();
        if((p.physicalID!=3&&p.physicalID!=4)||p.quadCfa||cfa<0||cfa>3||PreferenceKeys.isRemosaicEnabled()
                ||com.particlesdevs.photoncamera.util.Allocator.binning)
            throw new IOException("NICE HDR: нужен обычный Bayer IMX06C (основа/широкоугольная), без ремозаика и биннинга");
        if(black.length!=4)throw new IOException("NICE HDR: нужны четыре уровня чёрного");
        if(width<64||height<64||(width&1)!=0||(height&1)!=0||(long)width*height>16000000)
            throw new IOException("NICE HDR: размер RAW до 16 МП");
        List<ImageFrame> normal=new ArrayList<>(),shorts=new ArrayList<>(),longs=new ArrayList<>();
        for(ImageFrame f:source){
            if(f.measuredIso<=0||f.measuredExposure<=0||f.pair==null||f.buffer==null||f.width!=width||f.height!=height||f.buffer.capacity()!=(long)width*height*2)
                throw new IOException("NICE HDR: отсутствует полный RAW или его экспозиция");
            if(f.pair.isHighlightFrame)shorts.add(f);else if(f.pair.isLongFrame)longs.add(f);else normal.add(f);
        }
        if(normal.isEmpty()||shorts.isEmpty())throw new IOException("NICE HDR: нужны обычные и короткие кадры");
        ordered[3]=normal.get(0); // HdrxProcessor selected the reference by quality.
        int next=1;for(int i=0;i<3;++i){ordered[i]=normal.get(Math.min(next,normal.size()-1));++next;}
        Comparator<ImageFrame> byExposure=Comparator.comparingDouble(VivoNiceBurst::product);
        shorts.sort(byExposure);longs.sort(byExposure);
        ordered[4]=longs.isEmpty()?ordered[3]:longs.get(longs.size()-1);
        ordered[5]=shorts.get(shorts.size()-1);ordered[6]=shorts.get(0);
        double ref=product(ordered[3]);
        if(!(product(ordered[6])<ref))throw new IOException("NICE HDR: короткий кадр не темнее опорного");
        for(int i=0;i<7;++i){
            exposure[i]=(float)(product(ordered[i])/ref);
            if(!Float.isFinite(exposure[i])||exposure[i]<1f/256||exposure[i]>256||ordered[i].pair.iso<50||ordered[i].pair.iso>12800)
                throw new IOException("NICE HDR: экспозиция/ISO вне диапазона");
            Log.i("NICE_HDR","slot="+i+" role="+new String[]{"N","N","N","N-ref","L","S","ES"}[i]
                    +" frame="+ordered[i].number+" timestamp="+ordered[i].timestamp+" exposure_ratio="+exposure[i]+" ISO="+ordered[i].pair.iso);
        }
        if(ordered[5]==ordered[6])Log.i("NICE_HDR","No distinct ES: using S for ES, as supported by donor routing");
        if(normal.size()<4)Log.i("NICE_HDR","Fewer than 4 N frames: repeating available normal input");
    }
    private static double product(ImageFrame f){return (double)f.pair.exposure*f.pair.iso;}
    void write(File file)throws IOException {
        ByteBuffer header=ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0x3143484e).putInt(1).putInt(width).putInt(height).putInt(cfa).putInt(7).putFloat(white);
        for(float v:black)header.putFloat(v);for(float v:exposure)header.putFloat(v);for(ImageFrame f:ordered)header.putInt(f.pair.iso);
        header.position(0);
        try(FileChannel out=new FileOutputStream(file).getChannel()){
            while(header.hasRemaining())out.write(header);
            for(ImageFrame f:ordered){ByteBuffer raw=f.buffer.duplicate();raw.clear();while(raw.hasRemaining())out.write(raw);}
        }
    }
    public static ByteBuffer process(Context context,List<ImageFrame> frames,Parameters p)throws Exception {
        return VivoNeuralClient.processNiceBurst(context,new VivoNiceBurst(frames,p));
    }
}

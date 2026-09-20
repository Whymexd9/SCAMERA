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

/** Camera2 burst transport for the original forward NICE model; calibration comes from Camera2. */
public final class VivoNiceBurst {
    final int width,height,cfa;
    private final float white;
    private float noiseSlope,noiseOffset,normalNoiseSlope,normalNoiseOffset;
    final boolean diagnostics;
    final float normCoefficient,noiseScale;
    final VivoNiceScene scene;
    private final boolean trainedSensor;
    private final float[] black;
    private final ImageFrame[] ordered=new ImageFrame[7];
    private final VivoNiceAe[] ae=new VivoNiceAe[7];
    private final float[] exposure=new float[7];
    private VivoNiceBurst(List<ImageFrame> source,Parameters p) throws IOException {
        width=p.rawSize.x;height=p.rawSize.y;cfa=p.cfaPattern;white=p.whiteLevel;black=p.blackLevel.clone();
        diagnostics=PreferenceKeys.isNiceDiagnosticsEnabled();
        normCoefficient=PreferenceKeys.gcamValue("pref_vivo_nice_norm",1.1f,.55f,2.2f);
        noiseScale=PreferenceKeys.gcamValue("pref_vivo_nice_noise_scale",1f,.25f,4f);
        trainedSensor = "vivo".equalsIgnoreCase(android.os.Build.MANUFACTURER)
                && "PD2454".equalsIgnoreCase(android.os.Build.DEVICE)
                && (p.physicalID == 3 || p.physicalID == 4);
        if(p.quadCfa||cfa<0||cfa>3||PreferenceKeys.isRemosaicEnabled()
                ||com.particlesdevs.photoncamera.util.Allocator.binning)
            throw new IOException("NICE HDR: нужен обычный Bayer RAW, без Quad/Tetra, ремозаика и программного биннинга");
        if(black.length!=4)throw new IOException("NICE HDR: нужны четыре уровня чёрного");
        if(width<64||height<64||(width&1)!=0||(height&1)!=0||(long)width*height>16000000)
            throw new IOException("NICE HDR: размер RAW до 16 МП");
        List<ImageFrame> normal=new ArrayList<>(),shorts=new ArrayList<>(),longs=new ArrayList<>();
        for(ImageFrame f:source){
            String detail="frame="+f.number+" timestamp="+f.timestamp+" ZSL="+f.fromZsl;
            if(f.measuredIso<=0||f.measuredExposure<=0)
                throw new IOException("NICE HDR: нет измеренной экспозиции: "+detail
                        +" exposureNs="+f.measuredExposure+" ISO="+f.measuredIso);
            if(f.pair==null)throw new IOException("NICE HDR: нет роли кадра: "+detail);
            if(f.buffer==null||f.width!=width||f.height!=height||f.buffer.capacity()!=(long)width*height*2)
                throw new IOException("NICE HDR: неполный RAW: "+detail+" size="+f.width+"x"+f.height
                        +" bytes="+(f.buffer==null?0:f.buffer.capacity())+" expected="+width+"x"+height+"/"+((long)width*height*2));
            if(f.pair.isHighlightFrame)shorts.add(f);else if(f.pair.isLongFrame)longs.add(f);else normal.add(f);
        }
        if(normal.isEmpty()||shorts.isEmpty())throw new IOException("NICE HDR: нужны обычные и короткие кадры");
        // CRE SelectFrame sorts the chosen reference to vector index zero
        // (0x3617e8). XML ref/refn select radiometric exposure levels,
        // not the position of the unwarped network reference.
        ordered[0]=normal.get(0);
        scene=VivoNiceScene.fromReference(ordered[0]);
        Log.i("NICE_HDR",scene.describe());
        for(int i=1;i<4;++i)ordered[i]=normal.get(Math.min(i,normal.size()-1));
        Comparator<ImageFrame> byExposure=Comparator.comparingDouble(VivoNiceBurst::product);
        shorts.sort(byExposure);longs.sort(byExposure);
        ordered[4]=longs.isEmpty()?ordered[0]:longs.get(longs.size()-1);
        ordered[5]=shorts.get(shorts.size()-1);ordered[6]=shorts.get(0);
        // Forward ref/refn=3 identify L in the ES/S/N/L radiometric table.
        float[] longNoise=noiseFor(ordered[4]), normalNoise=noiseFor(ordered[0]);
        noiseSlope=longNoise[0];noiseOffset=longNoise[1];
        normalNoiseSlope=normalNoise[0];normalNoiseOffset=normalNoise[1];
        Log.i("NICE_HDR","Calibration source="+(trainedSensor?"IMX06C forward HDR profile":"Camera2 experimental cross-sensor")+" sensor="+p.physicalID
                +" CFA="+cfa+" slope="+noiseSlope+" offset="+noiseOffset
                +"; original weights, experimental cross-sensor adaptation");
        double ref=product(ordered[0]);
        if(!(product(ordered[6])<ref))throw new IOException("NICE HDR: короткий кадр не темнее опорного");
        for(int i=0;i<7;++i){
            ae[i]=VivoNiceAe.fromFrame(ordered[i]);
            Log.i("NICE_HDR","slot="+i+" "+ae[i].describe());
            exposure[i]=(float)(product(ordered[i])/ref);
            if(!Float.isFinite(exposure[i])||exposure[i]<1f/256||exposure[i]>256||ordered[i].measuredIso<=0)
                throw new IOException("NICE HDR: экспозиция/ISO вне диапазона");
            Log.i("NICE_HDR","slot="+i+" role="+new String[]{"N-ref","N","N","N","L","S","ES"}[i]
                    +" frame="+ordered[i].number+" timestamp="+ordered[i].timestamp+" exposureNs="+ordered[i].measuredExposure
                    +" exposure_ratio="+exposure[i]+" ISO="+ordered[i].measuredIso);
        }
        if(ordered[5]==ordered[6])Log.i("NICE_HDR","No distinct ES: using S for ES, as supported by donor routing");
        Set<Long> unique=new HashSet<>();int zslSlots=0;
        for(int i=0;i<7;i++) {
            ImageFrame frame=ordered[i];boolean duplicate=!unique.add(frame.timestamp);
            if(frame.fromZsl)zslSlots++;
            Log.i("NICE_PIPELINE","slot="+i+" timestamp="+frame.timestamp+" source="+(frame.fromZsl?"ZSL":"PSL")
                    +" duplicateTimestamp="+duplicate+" deltaToReferenceNs="+(frame.timestamp-ordered[0].timestamp)
                    +" measuredExposureNs="+frame.measuredExposure+" measuredISO="+frame.measuredIso);
        }
        Log.i("NICE_PIPELINE","capture=Camera2_manual_hybrid stockVcfRequests=false graph=fixed_4N_1L_1S_1ES"
                +" sourceCounts=N:"+normal.size()+",L:"+longs.size()+",S:"+shorts.size()
                +" uniqueSelected="+unique.size()+" zslSlots="+zslSlots+" pslSlots="+(7-zslSlots)
                +" separateES="+(ordered[5].timestamp!=ordered[6].timestamp)+" referencePolicy=first_normal"
                +" TCE=not_connected normCoefficient="+normCoefficient+" noiseVarianceScale="+noiseScale);
        if(normal.size()<4)Log.i("NICE_HDR","Fewer than 4 N frames: repeating available normal input");
    }
    private float[] noiseFor(ImageFrame frame) throws IOException {
        float slope=frame.noiseSlope,offset=frame.noiseOffset;
        if(trainedSensor) {
            int iso=frame.measuredIso;
            if(iso<50||iso>12800)throw new IOException("NICE HDR: ISO вне проверенного профиля IMX06C");
            slope=Math.fma(0.0001242085f,iso,-0.0014234833f)/255f;
            offset=Math.max(0.0272538637f+Math.fma(0.0000000158f*iso,iso,0.0000376323f*iso),0.000001f)/65025f;
        }
        if(!Float.isFinite(slope)||slope<=0||!Float.isFinite(offset)||offset<0)
            throw new IOException("NICE HDR: некорректный Camera2 SENSOR_NOISE_PROFILE для RAW frame="+frame.number);
        return new float[]{slope,offset};
    }
    private static double product(ImageFrame f){return (double)f.measuredExposure*f.measuredIso;}
    void write(File file)throws IOException {
        ByteBuffer header=ByteBuffer.allocate(160+7*VivoNiceAe.TRANSPORT_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0x3143484e).putInt(8).putInt(width).putInt(height).putInt(cfa).putInt(7).putFloat(white);
        for(float v:black)header.putFloat(v);for(float v:exposure)header.putFloat(v);for(ImageFrame f:ordered)header.putInt(f.measuredIso);
        header.putFloat(noiseSlope).putFloat(noiseOffset).putInt(diagnostics?1:0);
        header.putFloat(normalNoiseSlope).putFloat(normalNoiseOffset);
        header.putFloat(normCoefficient).putFloat(noiseScale);
        header.position(128);
        scene.writeTransport(header);
        for(VivoNiceAe value:ae)value.writeTransport(header);
        header.position(0);
        try(FileChannel out=new FileOutputStream(file).getChannel()){
            while(header.hasRemaining())out.write(header);
            for(ImageFrame f:ordered){ByteBuffer raw=f.buffer.duplicate();raw.clear();while(raw.hasRemaining())out.write(raw);}
        }
    }
    public static ByteBuffer process(Context context,List<ImageFrame> frames,Parameters p)throws Exception {
        VivoNiceBurst burst=new VivoNiceBurst(frames,p);
        if(burst.diagnostics)NiceDiagnostics.begin(context,p,burst.ordered[0],burst.scene);
        return VivoNeuralClient.processNiceBurst(context,burst);
    }
}

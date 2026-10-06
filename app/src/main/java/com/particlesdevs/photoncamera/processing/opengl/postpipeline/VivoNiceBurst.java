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
public final class VivoNiceBurst implements NiceTransport {
    @Override public int width() { return width; }
    @Override public int height() { return height; }
    @Override public int cfa() { return cfa; }
    @Override public boolean mergedDng() { return mergedDng; }
    @Override public boolean diagnostics() { return diagnostics; }
    final int width,height,cfa;
    private final float white;
    private float noiseSlope,noiseOffset,normalNoiseSlope,normalNoiseOffset;
    final boolean diagnostics;
    final float normCoefficient,noiseScale,photonScale,readoutScale;
    final VivoNiceScene scene;
    private final boolean trainedSensor;
    private final float[] black;
    private final ImageFrame[] ordered=new ImageFrame[7];
    // Extra ZSL N frames (same exposure as the N reference), older than the four slots.
    private final List<ImageFrame> extraNormals=new ArrayList<>();
    private final VivoNiceAe[] ae=new VivoNiceAe[7];
    private final float[] exposure=new float[7];
    private final Map<ImageFrame,VivoNiceAe> measuredAe=new IdentityHashMap<>();
    private boolean vendorExposureDomain;
    /** 2 / 4 when the frames' own Quad / Tetra samples follow the plain frames (Sabre merge over the mosaic sites), else 0. */
    private int mosaicBlock;
    /** L ratio when L is built from the N frames by the worker, else 0. */
    private float syntheticLong;
    private String noiseSource="?";
    private boolean stockDevice;
    private int physicalId;
    private VivoNiceBurst(List<ImageFrame> source,Parameters p) throws IOException {
        width=p.rawSize.x;height=p.rawSize.y;cfa=p.cfaPattern;white=p.whiteLevel;black=p.blackLevel.clone();
        diagnostics=PreferenceKeys.isNiceDiagnosticsEnabled();
        normCoefficient=PreferenceKeys.niceInternalValue("norm",1.1f);
        noiseScale=PreferenceKeys.niceInternalValue("noise_scale",1f);
        photonScale=PreferenceKeys.niceInternalValue("noise_photon",1f);
        readoutScale=PreferenceKeys.niceInternalValue("noise_readout",1f);
        stockDevice = "vivo".equalsIgnoreCase(android.os.Build.MANUFACTURER)
                && "PD2454".equalsIgnoreCase(android.os.Build.DEVICE);
        physicalId = p.physicalID;
        trainedSensor = stockDevice && (p.physicalID == 3 || p.physicalID == 4);
        if(p.quadCfa||cfa<0||cfa>3||com.particlesdevs.photoncamera.util.Allocator.binning)
            throw new IOException("SCAM HDR: нужен обычный Bayer RAW, без Quad/Tetra, ремозаика и программного биннинга");
        if(black.length!=4)throw new IOException("SCAM HDR: нужны четыре уровня чёрного");
        if(width<64||height<64||(width&1)!=0||(height&1)!=0||(long)width*height>16000000)
            throw new IOException("SCAM HDR: размер RAW до 16 МП");
        if(source.size()<6||source.size()>53)throw new IOException("SCAM HDR: нужны 4–50 N и кадры S/ES (и L)");
        Set<Long> sourceTimestamps=new HashSet<>();
        List<ImageFrame> normal=new ArrayList<>(),shorts=new ArrayList<>(),longs=new ArrayList<>();
        for(ImageFrame f:source){
            String detail="frame="+f.number+" timestamp="+f.timestamp+" ZSL="+f.fromZsl;
            if(f.timestamp<=0 || !sourceTimestamps.add(f.timestamp))
                throw new IOException("SCAM HDR 4+3: повторный или отсутствующий timestamp: "+detail);
            if(f.measuredIso<=0||f.measuredExposure<=0)
                throw new IOException("SCAM HDR: нет измеренной экспозиции: "+detail
                        +" exposureNs="+f.measuredExposure+" ISO="+f.measuredIso);
            ImageFrame.CaptureRole role=f.getCaptureRole();
            if(role==null)throw new IOException("SCAM HDR: нет роли из совпавших метаданных RAW: "+detail);
            if(f.buffer==null||f.width!=width||f.height!=height||f.buffer.capacity()!=(long)width*height*2)
                throw new IOException("SCAM HDR: неполный RAW: "+detail+" size="+f.width+"x"+f.height
                        +" bytes="+(f.buffer==null?0:f.buffer.capacity())+" expected="+width+"x"+height+"/"+((long)width*height*2));
            if(f.rawPayloadError!=null)
                throw new IOException("SCAM HDR: RAW-кадр не в 16-битном формате, снимок не сохранён: "+detail+" ("+f.rawPayloadError+")");
            switch(role) {
                case SHORT: case EXTRA_SHORT: shorts.add(f); break;
                case LONG: longs.add(f); break;
                case NORMAL: normal.add(f); break;
                default: throw new IOException("SCAM HDR: неизвестная роль RAW: "+detail);
            }
        }
        int vendorFrames=0;
        for(ImageFrame frame:source) {
            VivoNiceAe snapshot=VivoNiceAe.fromFrame(frame);
            measuredAe.put(frame,snapshot);
            if(snapshot.hasMeasuredExposure())vendorFrames++;
        }
        // Keep one radiometric domain for the entire burst; never mix ISO and linear gain (P27: such a burst is merged by the
        // Hybrid, HdrxProcessor).
        if(vendorFrames!=0 && vendorFrames!=source.size())
            throw new IOException("SCAM HDR: неполные измеренные vendor AE в серии");
        vendorExposureDomain=vendorFrames==source.size();
        Log.i("NICE_PIPELINE","exposureDomain="+(vendorExposureDomain
                ?"matched_vendor_exposure_times_gain":"Camera2_exposure_times_ISO_fallback"));
        normal.sort(Comparator.comparingLong(f->f.timestamp));
        // The four newest N stay the model's slots (as with four frames); older
        // ones are extras merged into those slots by the worker.
        while(normal.size()>4)extraNormals.add(normal.remove(0));
        // No captured L: the worker builds it from all N frames (ratio from the plan).
        syntheticLong=longs.isEmpty()&&!normal.isEmpty()?normal.get(0).syntheticLongRatio:0;
        if(syntheticLong>0)longs.add(normal.get(0));
        if(normal.size()!=4 || shorts.size()!=2 || longs.size()!=1)
            throw new IOException("SCAM HDR 4+3: нужны 4 N, 1 L, 1 S и 1 ES; получено N="
                    +normal.size()+", L="+longs.size()+", S/ES="+shorts.size());
        // CRE SelectFrame sorts the chosen reference to vector index zero
        // (0x3617e8). XML ref/refn select radiometric exposure levels,
        // not the position of the unwarped network reference.
        ordered[0]=normal.get(0);
        scene=VivoNiceScene.fromReference(ordered[0]);
        Log.i("NICE_HDR",scene.describe());
        for(int i=1;i<4;++i)ordered[i]=normal.get(i);
        Comparator<ImageFrame> byExposure=Comparator.comparingDouble(this::product);
        shorts.sort(byExposure);longs.sort(byExposure);
        ordered[4]=longs.get(0);
        // S and ES by their measured exposure (P27: the request tags may be swapped by the HAL's rounding; the ES < S check
        // below still holds the graph to two distinct short frames).
        ordered[5]=shorts.get(shorts.size()-1);ordered[6]=shorts.get(0);
        // Forward ref/refn=3 identify L in the ES/S/N/L radiometric table.
        float[] longNoise=noiseFor(ordered[4]), normalNoise=noiseFor(ordered[0]);
        noiseSlope=longNoise[0];noiseOffset=longNoise[1];
        normalNoiseSlope=normalNoise[0];normalNoiseOffset=normalNoise[1];
        Log.i("NICE_HDR","Calibration source="+(trainedSensor?"IMX06C forward HDR profile":"Camera2 experimental cross-sensor")+" sensor="+p.physicalID
                +" CFA="+cfa+" noiseSource="+noiseSource+" slope="+noiseSlope+" offset="+noiseOffset
                +"; original weights, experimental cross-sensor adaptation");
        noiseSlope*=photonScale;
        noiseOffset*=readoutScale;
        normalNoiseSlope*=photonScale;
        normalNoiseOffset*=readoutScale;
        Log.i("NICE_HDR", "Input noise tuning: photon="+photonScale+" readout="+readoutScale
                +" overall="+noiseScale+"; applied before model VST/IVST, no RGB denoise");
        double ref=product(ordered[0]);
        for(Iterator<ImageFrame> it=extraNormals.iterator();it.hasNext();) {
            ImageFrame f=it.next();
            if(Math.abs(product(f)/ref-1)>0.02) {
                Log.w("NICE_HDR","extra N frame="+f.number+" dropped: exposure differs from N reference");
                it.remove();
            }
        }
        Log.i("NICE_HDR","extra ZSL N frames merged into the 4 N slots: "+extraNormals.size());
        if(PreferenceKeys.isNiceMosaicSabre()){
            boolean all=true;
            for(int i=0;i<4;++i)all&=ordered[i].mosaic!=null&&ordered[i].mosaic.capacity()==(long)width*height*2;
            for(ImageFrame f:extraNormals)all&=f.mosaic!=null&&f.mosaic.capacity()==(long)width*height*2;
            mosaicBlock=all?PreferenceKeys.niceMosaicBlock():0;
            Log.i("NICE_HDR","Sabre over mosaic sites: "+(all?"block "+mosaicBlock+", "+(4+extraNormals.size())+" frames":"off (no mosaic copies)"));
        }
        if(!(product(ordered[6])<product(ordered[5]) && product(ordered[5])<ref
                && ref<=product(ordered[4])))
            throw new IOException("SCAM HDR 4+3: измеренные экспозиции должны удовлетворять ES < S < N <= L");
        for(int i=0;i<7;++i){
            ae[i]=measuredAe.get(ordered[i]);
            Log.i("NICE_HDR","slot="+i+" "+ae[i].describe());
            exposure[i]=i==4&&syntheticLong>0?syntheticLong:(float)(product(ordered[i])/ref);
            // The worker takes x1/256..x256 (vivo-nice-capture.h); a burst outside it is merged by the Hybrid (P27) instead of
            // failing in the worker.
            if(!Float.isFinite(exposure[i])||exposure[i]<1f/256||exposure[i]>256||ordered[i].measuredIso<=0)
                throw new IOException("SCAM HDR: экспозиция/ISO вне диапазона");
            Log.i("NICE_HDR","slot="+i+" role="+new String[]{"N-ref","N","N","N","L","S","ES"}[i]
                    +" frame="+ordered[i].number+" timestamp="+ordered[i].timestamp+" exposureNs="+ordered[i].measuredExposure
                    +" exposure_ratio="+exposure[i]+" ISO="+ordered[i].measuredIso);
        }
        Set<Long> unique=new HashSet<>();int zslSlots=0;
        for(int i=0;i<7;i++) {
            ImageFrame frame=ordered[i];boolean duplicate=!unique.add(frame.timestamp);
            if(frame.fromZsl)zslSlots++;
            Log.i("NICE_PIPELINE","slot="+i+" timestamp="+frame.timestamp+" source="+(frame.fromZsl?"ZSL":"PSL")
                    +" duplicateTimestamp="+duplicate+" deltaToReferenceNs="+(frame.timestamp-ordered[0].timestamp)
                    +" measuredExposureNs="+frame.measuredExposure+" measuredISO="+frame.measuredIso);
        }
        Log.i("NICE_PIPELINE","capture=Camera2_RAW stockVcfRequests=false graph=fixed_4N_1L_1S_1ES"
                +" sourceCounts=N:"+normal.size()+",L:"+longs.size()+",S:"+shorts.size()
                +" uniqueSelected="+unique.size()+" zslSlots="+zslSlots+" pslSlots="+(7-zslSlots)
                +" separateES="+(ordered[5].timestamp!=ordered[6].timestamp)+" referencePolicy=first_normal"
                +" TCE=not_connected normCoefficient="+normCoefficient+" noiseVarianceScale="+noiseScale);
    }
    private float[] noiseFor(ImageFrame frame) throws IOException {
        float slope=frame.noiseSlope,offset=frame.noiseOffset;
        // NICE noise source: auto (IMX06C built-in on cameras 3/4, Camera2 elsewhere),
        // imx06c (built-in for any camera), camera2, or settings (the noise model
        // chosen/imported under "Модель шума сенсора", for other sensors/devices).
        String source=PreferenceKeys.getNiceNoiseSource();
        com.particlesdevs.photoncamera.processing.render.NoiseModelProfile profile="settings".equals(source)
                ?com.particlesdevs.photoncamera.processing.render.NoiseModelProfile.byId(PreferenceKeys.getNoiseModelProfileId()):null;
        if("settings".equals(source)&&profile==null)
            Log.w("NICE_HDR","NICE noise source=settings but no profile is selected; using auto");
        // Stock NICE CRE HDR noise (NiceCREConfigHdrForward.xml NoiseInfoHDR):
        // IMX06C on MainCamera and UltraWideCamera (cameras 3/4), HP9 on TeleCamera (5).
        boolean auto=!"camera2".equals(source)&&!"imx06c".equals(source)&&!"hp9".equals(source)&&profile==null;
        boolean imx06c="imx06c".equals(source)||(auto&&trainedSensor);
        boolean hp9="hp9".equals(source)||(auto&&stockDevice&&physicalId==5);
        if(profile!=null) {
            Integer maxAnalog=com.particlesdevs.photoncamera.capture.CaptureController.mCameraCharacteristics==null?null
                    :com.particlesdevs.photoncamera.capture.CaptureController.mCameraCharacteristics.get(
                            android.hardware.camera2.CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY);
            android.util.Pair<Double,Double>[] model=profile.evaluate(frame.measuredIso,maxAnalog==null?frame.measuredIso:maxAnalog);
            double s=0,o=0;for(android.util.Pair<Double,Double> c:model){s+=c.first;o+=c.second;}
            slope=(float)(s/model.length);offset=(float)(o/model.length);
            noiseSource="settings profile "+profile.id;
        } else if(imx06c) {
            noiseSource="IMX06C built-in";
            int iso=frame.measuredIso;
            if(iso<50||iso>12800)throw new IOException("SCAM HDR: ISO вне проверенного профиля IMX06C");
            slope=Math.fma(0.0001242085f,iso,-0.0014234833f)/255f;
            offset=Math.max(0.0272538637f+Math.fma(0.0000000158f*iso,iso,0.0000376323f*iso),0.000001f)/65025f;
        } else if(hp9) {
            noiseSource="HP9 stock SCAM HDR";
            int iso=frame.measuredIso;
            slope=Math.fma(0.0000684080f,iso,0.0000302159f)/255f;
            offset=Math.max(0.0410039589f+Math.fma(0.0000000224f*iso,iso,0.0000105126f*iso),0.000001f)/65025f;
        } else noiseSource="Camera2 SENSOR_NOISE_PROFILE";
        if(!Float.isFinite(slope)||slope<=0||!Float.isFinite(offset)||offset<0)
            throw new IOException("SCAM HDR: некорректный профиль шума ("+noiseSource+") для RAW frame="+frame.number);
        return new float[]{slope,offset};
    }
    private double product(ImageFrame f) {
        return vendorExposureDomain ? measuredAe.get(f).measuredExposureProduct()
                : (double)f.measuredExposure*f.measuredIso;
    }
    private ByteBuffer header() {
        ByteBuffer header=ByteBuffer.allocate(160+7*VivoNiceAe.TRANSPORT_BYTES+32).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0x3143484e).putInt(9).putInt(width).putInt(height).putInt(cfa).putInt(7+extraNormals.size()).putFloat(white);
        for(float v:black)header.putFloat(v);for(float v:exposure)header.putFloat(v);for(ImageFrame f:ordered)header.putInt(f.measuredIso);
        header.putFloat(noiseSlope).putFloat(noiseOffset).putInt(diagnostics?1:0);
        header.putFloat(normalNoiseSlope).putFloat(normalNoiseOffset);
        header.putFloat(normCoefficient).putFloat(noiseScale);
        header.position(128);
        scene.writeTransport(header);
        for(VivoNiceAe value:ae)value.writeTransport(header);
        // NCH v9: luma/chroma strength inside NICE (ISO level from the normal reference).
        int referenceIso=ordered[0].measuredIso;
        float luma=PreferenceKeys.getNiceLuma(referenceIso),chroma=PreferenceKeys.getNiceChroma(referenceIso);
        header.putFloat(luma).putFloat(chroma)
                .putFloat(PreferenceKeys.niceInternalValue("luma_radius",2f))
                .putFloat(PreferenceKeys.niceInternalValue("chroma_radius",4f))
                .putFloat(1f-PreferenceKeys.getNiceMerge()).putFloat(mergedDng?1:0).putFloat(syntheticLong).putFloat(mosaicBlock);
        Log.i("NICE_HDR","luma/chroma inside NICE: ISO="+referenceIso+" level="+PreferenceKeys.niceIsoLevel(referenceIso)
                +" luma="+luma+" chroma="+chroma+" merge="+PreferenceKeys.getNiceMerge());
        header.position(0);
        return header;
    }
    @Override public void write(File file)throws IOException {
        try(FileChannel out=new FileOutputStream(file).getChannel()){write(out);}
    }
    @Override public void write(FileChannel out)throws IOException {
        ByteBuffer header=header();
        long position=0;
        while(header.hasRemaining())position+=out.write(header,position);
        // Frames at fixed offsets, written by four threads (positional writes):
        // one thread copied ~0.6 GB at ~1.8 GB/s.
        java.util.List<ImageFrame> frames=new java.util.ArrayList<>(java.util.Arrays.asList(ordered));
        frames.addAll(extraNormals);
        // The plain frames first, then (Sabre over mosaic sites) the N slots' and extras' own mosaic samples.
        final java.util.List<ByteBuffer> buffers=new java.util.ArrayList<>();
        for(ImageFrame f:frames)buffers.add(f.buffer);
        if(mosaicBlock>0){
            for(int i=0;i<4;++i)buffers.add(ordered[i].mosaic);
            for(ImageFrame f:extraNormals)buffers.add(f.mosaic);
        }
        final long[] offsets=new long[buffers.size()];
        for(int i=0;i<buffers.size();i++){offsets[i]=position;position+=buffers.get(i).capacity();}
        final java.util.concurrent.atomic.AtomicInteger next=new java.util.concurrent.atomic.AtomicInteger();
        final IOException[] failure={null};
        Thread[] workers=new Thread[Math.min(4,buffers.size())];
        for(int t=0;t<workers.length;t++){
            workers[t]=new Thread(()->{
                try{
                    for(int i=next.getAndIncrement();i<buffers.size();i=next.getAndIncrement()){
                        ByteBuffer raw=buffers.get(i).duplicate();raw.clear();long at=offsets[i];
                        while(raw.hasRemaining())at+=out.write(raw,at);
                    }
                }catch(IOException e){synchronized(failure){failure[0]=e;}}
            },"nice-write-"+t);
            workers[t].start();
        }
        for(Thread w:workers)try{w.join();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
        if(failure[0]!=null)throw failure[0];
    }
    /** Whether the worker should also return the whole-burst merged RAW for the DNG. */
    boolean mergedDng;
    /** Merged Bayer RAW of the last NICE shot (uint16, sensor layout, white 16383), or null. */
    public static volatile ByteBuffer lastMergedDng;
    /** Effective merged frames per pixel of the last SCAM HDR result (uint8, 1/8 frame), or null. */
    public static volatile ByteBuffer lastEffectiveFrames;
    public static ByteBuffer process(Context context,List<ImageFrame> frames,Parameters p,boolean mergedDng)throws Exception {
        VivoNiceBurst burst=new VivoNiceBurst(frames,p);
        burst.mergedDng=mergedDng;
        if(burst.diagnostics)NiceDiagnostics.begin(context,p,burst.ordered[0],burst.scene);
        return VivoNeuralClient.processNiceBurst(context,burst);
    }
}

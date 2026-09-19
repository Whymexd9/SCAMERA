package com.particlesdevs.photoncamera.remosaic;

import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.util.Allocator;
import com.particlesdevs.photoncamera.util.Log;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;

/** Original APK JNI ABI. Buffers use SCAMERA's allocator, never Java-owned memory. */
public final class MobileRemosaicProcessor {
    private static native boolean nativeProcess(ByteBuffer[] inputs, int width, int height, int block,
            String cfa, int frameCount, ByteBuffer corrections, float isoScale,
            float redCaScale, float blueCaScale, ByteBuffer output);
    static void load() {
        try { System.loadLibrary("mobileRemosaic"); }
        catch (LinkageError e) { throw new IllegalStateException("Не удалось загрузить Multi-frame Remosaic", e); }
    }
    private MobileRemosaicProcessor() {}
    static ByteBuffer[] validate(List<ImageFrame> frames, Parameters p, int block, int minimum) {
        int width=p.rawSize.x, height=p.rawSize.y;
        int allowed=BurstPolicy.frameCount(frames.size(),width,height);
        if (frames.size()<minimum || frames.size()>allowed) throw new IllegalArgumentException("MFSR: размер серии превышает лимит памяти");
        if (Allocator.binning || PhotonCamera.getSettings().aspect169)
            throw new IllegalArgumentException("MFSR: выберите 4:3 и отключите программный биннинг");
        if (width%(block*2)!=0 || height%(block*2)!=0)
            throw new IllegalArgumentException("MFSR: размеры не кратны периоду мозаики");
        ByteBuffer[] inputs=new ByteBuffer[frames.size()];
        HashSet<Long> timestamps=new HashSet<>();
        ImageFrame ref=frames.get(0);
        for(int i=0;i<frames.size();i++) {
            ImageFrame f=frames.get(i);
            if(f.width!=width || f.height!=height || f.buffer==null || !f.buffer.isDirect()
                    || f.buffer.capacity()!=(long)width*height*2 || f.buffer.position()!=0)
                throw new IllegalArgumentException("MFSR: неполный RAW или неизвестный шаг строки");
            if(!timestamps.add(f.timestamp) || f.pair==null || ref.pair==null
                    || f.pair.exposure!=ref.pair.exposure || f.pair.iso!=ref.pair.iso
                    || f.pair.isHighlightFrame!=ref.pair.isHighlightFrame || f.pair.isLongFrame!=ref.pair.isLongFrame)
                throw new IllegalArgumentException("MFSR: нужны разные кадры одинаковой экспозиции");
            inputs[i]=f.buffer;
        }
        return inputs;
    }
    public static ByteBuffer process(List<ImageFrame> frames, Parameters p) {
        int block=PreferenceKeys.getMultiFrameBlock();
        String cfa=BurstPolicy.cfa(PreferenceKeys.getMultiFrameCfa(),p.baseCfaPattern);
        return processGroup(frames,p,block,cfa,3);
    }
    private static ByteBuffer processGroup(List<ImageFrame> frames,Parameters p,int block,String cfa,int minimum) {
        ByteBuffer[] inputs=validate(frames,p,block,minimum);
        load();
        String key=RemosaicCalibrationStore.key(p.cameraID,block,cfa,p.rawSize.x,p.rawSize.y);
        RemosaicCalibrationStore.Profile cal=PreferenceKeys.isMultiFrameFpnEnabled()
                ? RemosaicCalibrationStore.load(key,frames.get(0).pair.iso,frames.get(0).pair.exposure,inputs[0].capacity()) : null;
        ByteBuffer out=Allocator.allocate(inputs[0].capacity());
        if(out==null) throw new IllegalStateException("MFSR: не удалось выделить выходной RAW");
        boolean ok=false;long start=System.nanoTime();
        try {
            ok=nativeProcess(inputs,p.rawSize.x,p.rawSize.y,block,cfa,inputs.length,
                    cal==null?null:cal.map,cal==null?0:cal.scale,
                    PreferenceKeys.getMultiFrameRedCa(),PreferenceKeys.getMultiFrameBlueCa(),out);
            if(!ok) throw new IllegalStateException("Multi-frame Remosaic: ошибка nativeProcess");
            p.cfaPattern=(byte)java.util.Arrays.asList("RGGB","GRBG","GBRG","BGGR").indexOf(cfa);
            p.baseCfaPattern=p.cfaPattern;p.quadCfa=false;p.remosaicDone=true;
            // Keep original RAW levels: native reconstruction does not normalize them.
            p.hotPixels=new android.graphics.Point[0];
            out.position(0);
            Log.i("RAW_MFSR","backend=mobileRemosaic block="+block+" cfa="+cfa+" frames="+inputs.length
                    +" dimensions="+p.rawSize+" FPN="+(cal!=null)+" elapsedMs="+(System.nanoTime()-start)/1_000_000);
            return out;
        } finally { if(!ok) Allocator.free(out); }
    }
    /** Fuse each equal-exposure group independently; never feed a bracket to nativeProcess. */
    public static java.util.ArrayList<ImageFrame> prepareBracket(List<ImageFrame> frames,Parameters p) {
        int block=PreferenceKeys.getMultiFrameBlock();
        String cfa=BurstPolicy.cfa(PreferenceKeys.getMultiFrameCfa(),p.baseCfaPattern);
        java.util.ArrayList<ExposureGroups.Sample> samples=new java.util.ArrayList<>();
        for(ImageFrame f:frames) samples.add(new ExposureGroups.Sample(f.timestamp,f.pair.exposure,f.pair.iso,
                f.pair.isHighlightFrame ? 1 : f.pair.isLongFrame ? 2 : 0));
        java.util.ArrayList<ImageFrame> result=new java.util.ArrayList<>();
        for(java.util.List<Integer> indices:ExposureGroups.split(samples)) {
            java.util.ArrayList<ImageFrame> group=new java.util.ArrayList<>();
            for(int i:indices)group.add(frames.get(i));
            boolean normal=!group.get(0).pair.isHighlightFrame && !group.get(0).pair.isLongFrame;
            ByteBuffer output=processGroup(group,p,block,cfa,normal?3:1);
            // The binary aligns to index floor(N/2); retain that frame's timestamp and gyro.
            ImageFrame ref=group.get(group.size()/2);
            for(ImageFrame f:group)f.close();
            ref.buffer=output;ref.computeSharpness();result.add(ref);
            if(normal) {p.iso=ref.pair.iso;p.exposureTime=ref.pair.exposure/1e9;p.multiFrameCount=group.size();}
        }
        Log.i("RAW_MFSR","HDR donors="+(result.size()-1)+" normalFrames="+p.multiFrameCount);
        return result;
    }
    public static void calibrate(List<ImageFrame> frames, Parameters p) throws Exception {
        int block=PreferenceKeys.getMultiFrameBlock();
        String cfa=BurstPolicy.cfa(PreferenceKeys.getMultiFrameCfa(),p.baseCfaPattern);
        ByteBuffer[] inputs=validate(frames,p,block,3);load();
        RemosaicCalibrationStore.save(RemosaicCalibrationStore.key(p.cameraID,block,cfa,p.rawSize.x,p.rawSize.y),
                inputs,p.rawSize.x,p.rawSize.y,p.iso,(long)(p.exposureTime*1e9));
        PreferenceKeys.finishMultiFrameCalibration();
    }
}

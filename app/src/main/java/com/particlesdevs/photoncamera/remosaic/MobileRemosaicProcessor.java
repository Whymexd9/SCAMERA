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
import com.particlesdevs.photoncamera.util.Lang;

/** Original APK JNI ABI. Buffers use SCAMERA's allocator, never Java-owned memory. */
public final class MobileRemosaicProcessor {
    private static native boolean nativeProcess(ByteBuffer[] inputs, int width, int height, int block,
            String cfa, int frameCount, ByteBuffer corrections, float isoScale,
            float redCaScale, float blueCaScale, ByteBuffer output);
    static void load() {
        try { System.loadLibrary("mobileRemosaic"); }
        catch (LinkageError e) { throw new IllegalStateException(Lang.t("Не удалось загрузить Multi-frame Remosaic", "Could not load Multi-frame Remosaic"), e); }
    }
    private MobileRemosaicProcessor() {}
    static ByteBuffer[] validate(List<ImageFrame> frames, Parameters p, int block, int minimum) {
        int width=p.rawSize.x, height=p.rawSize.y;
        int allowed=BurstPolicy.frameCount(frames.size(),width,height);
        if (frames.size()<minimum || frames.size()>allowed) throw new IllegalArgumentException(Lang.t("MFSR: размер серии превышает лимит памяти", "MFSR: the burst size exceeds the memory limit"));
        if (Allocator.binning || PhotonCamera.getSettings().aspect169)
            throw new IllegalArgumentException(Lang.t("MFSR: выберите 4:3 и отключите программный биннинг", "MFSR: choose 4:3 and turn off software binning"));
        if (width%(block*2)!=0 || height%(block*2)!=0)
            throw new IllegalArgumentException(Lang.t("MFSR: размеры не кратны периоду мозаики", "MFSR: the size is not a multiple of the mosaic period"));
        ByteBuffer[] inputs=new ByteBuffer[frames.size()];
        HashSet<Long> timestamps=new HashSet<>();
        ImageFrame ref=frames.get(0);
        for(int i=0;i<frames.size();i++) {
            ImageFrame f=frames.get(i);
            if(f.width!=width || f.height!=height || f.buffer==null || !f.buffer.isDirect()
                    || f.buffer.capacity()!=(long)width*height*2 || f.buffer.position()!=0)
                throw new IllegalArgumentException(Lang.t("MFSR: неполный RAW или неизвестный шаг строки", "MFSR: incomplete RAW or unknown row stride"));
            if(!timestamps.add(f.timestamp) || f.pair==null || ref.pair==null
                    || f.pair.exposure!=ref.pair.exposure || f.pair.iso!=ref.pair.iso
                    || f.pair.isHighlightFrame!=ref.pair.isHighlightFrame || f.pair.isLongFrame!=ref.pair.isLongFrame)
                throw new IllegalArgumentException(Lang.t("MFSR: нужны разные кадры одинаковой экспозиции", "MFSR: needs distinct frames of the same exposure"));
            inputs[i]=f.buffer;
        }
        return inputs;
    }
    private static ByteBuffer processGroup(List<ImageFrame> frames,Parameters p,int block,String cfa,int minimum) {
        ByteBuffer[] inputs=validate(frames,p,block,minimum);
        load();
        String key=RemosaicCalibrationStore.key(p.cameraID,block,cfa,p.rawSize.x,p.rawSize.y);
        // FPN profiles recorded by the former CAL capture still apply when present.
        RemosaicCalibrationStore.Profile cal=RemosaicCalibrationStore.load(key,frames.get(0).pair.iso,frames.get(0).pair.exposure,inputs[0].capacity());
        ByteBuffer out=Allocator.allocate(inputs[0].capacity());
        if(out==null) throw new IllegalStateException(Lang.t("MFSR: не удалось выделить выходной RAW", "MFSR: could not allocate the output RAW"));
        boolean ok=false;long start=System.nanoTime();
        try {
            ok=nativeProcess(inputs,p.rawSize.x,p.rawSize.y,block,cfa,inputs.length,
                    cal==null?null:cal.map,cal==null?0:cal.scale,
                    1f,1f,out);
            if(!ok) throw new IllegalStateException(Lang.t("Multi-frame Remosaic: ошибка nativeProcess", "Multi-frame Remosaic: nativeProcess error"));
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
    /**
     * SCAM HDR on a mosaic stream: one plain-bayer frame (the frames' own black/white levels) merged from the equal-exposure
     * N frames. Updates the bayer layout in the parameters like the ordinary path.
     */
    public static ByteBuffer mergeForScam(List<ImageFrame> frames, Parameters p, int block, String cfa) {
        return processGroup(frames,p,block,cfa,3);
    }
}

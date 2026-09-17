package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.content.Context;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.util.*;

/** Six real sensor frames, before any remosaic/merge. Native worker owns registration. */
public final class HexQuadBurst {
    final int width,height,red,iso;
    final float black,white;
    final boolean response;
    final boolean zsl;
    final double exposureSeconds;
    final float lumaPercent,chromaPercent;
    final boolean postDenoise;
    final com.particlesdevs.photoncamera.settings.HexQuadOptions options;
    final float[] neutral;
    private final List<ImageFrame> frames;
    private HexQuadBurst(List<ImageFrame> frames,Parameters p) throws IOException {
        this.frames=new ArrayList<>(frames);
        width=p.rawSize.x;height=p.rawSize.y;
        if(com.particlesdevs.photoncamera.util.Allocator.binning)
            throw new IOException("HexQuad требует исходный Tetra RAW: отключите программный биннинг");
        if(com.particlesdevs.photoncamera.app.PhotonCamera.getSettings().aspect169)
            throw new IOException("Для теста HexQuad выберите 4:3: обрезка 16:9 меняет фазу Tetra");
        int[] phase=PreferenceKeys.getRemosaicPhase();
        if(PreferenceKeys.getRemosaicBlockSize()!=4 || Math.floorMod(phase[0],8)!=0 || Math.floorMod(phase[1],8)!=0)
            throw new IOException("Нужны Tetra 4×4 и фаза 0,0");
        if(frames.size()!=6 || width<288 || height<288 || width%8!=0 || height%8!=0 || (long)width*height>16000000)
            throw new IOException("Нужны 6 RAW Tetra, до 16 МП. Используйте режим Фото и 4× ISZ телевика");
        red=RemosaicCore.emittedCfaPattern(p.cfaPattern);
        black=(p.blackLevel[0]+p.blackLevel[1]+p.blackLevel[2]+p.blackLevel[3])*.25f;
        white=p.whiteLevel;response=PreferenceKeys.isTetraResponseCorrection();

        postDenoise=PreferenceKeys.isHexQuadPostDenoiseEnabled();
        if(p.whitePoint==null||p.whitePoint.length!=3)throw new IOException("Нет точки белого для HexQuad");
        neutral=p.whitePoint.clone();
        for(float v:neutral)if(!Float.isFinite(v)||v<.0001f||v>10000f)throw new IOException("Неверная точка белого HexQuad");
        ImageFrame ref=frames.get(0);
        zsl=ref.fromZsl;exposureSeconds=p.exposureTime;
        if(ref.pair==null)throw new IOException("Нет параметров экспозиции RAW");
        iso=p.iso; // Measured sensor ISO from CaptureResult, not normalized UI ISO.
        if(iso<50||iso>12800||!Float.isFinite(black)||!Float.isFinite(white)||white<=black+1)
            throw new IOException("Неподдерживаемые ISO/уровни RAW");
        options=PreferenceKeys.getHexQuadOptions(iso);
        lumaPercent=options.lumaPercent;chromaPercent=options.chromaPercent;
        Set<Long> timestamps=new HashSet<>();
        for(ImageFrame frame:frames){
            if(frame.buffer==null || frame.width!=width || frame.height!=height || frame.buffer.capacity()!=(long)width*height*2)
                throw new IOException("Неполный RAW или неизвестный шаг строки");
            if(!timestamps.add(frame.timestamp)||frame.pair==null||frame.pair.isHighlightFrame||frame.pair.isLongFrame||
                    frame.pair.iso!=ref.pair.iso||frame.pair.exposure!=ref.pair.exposure)
                throw new IOException("HexQuad требует шесть разных кадров с одинаковыми ISO и выдержкой");
        }
    }
    void write(File file) throws IOException {
        try(FileChannel channel=new FileOutputStream(file).getChannel()){
            ByteBuffer header=options.header(width,height,iso,red,black,white,response,neutral);
            header.position(0);while(header.hasRemaining())channel.write(header);
            for(ImageFrame frame:frames){ByteBuffer data=frame.buffer.duplicate();data.clear();while(data.hasRemaining())channel.write(data);}
        }
    }
    public static ByteBuffer process(Context context,List<ImageFrame> frames,Parameters p) throws Exception {
        HexQuadBurst burst=new HexQuadBurst(frames,p);
        ByteBuffer result=VivoNeuralClient.processBurst(context,burst);
        // Native output preserves reconstruction precision in normalized Bayer16.
        // Sensor white balance and lens shading are applied once, downstream.
        if(burst.options.fullResolution){
            p.rawSize=new android.graphics.Point(burst.width*2,burst.height*2);
            if(p.sensorPix!=null)p.sensorPix=new android.graphics.Rect(p.sensorPix.left*2,p.sensorPix.top*2,p.sensorPix.right*2,p.sensorPix.bottom*2);
            p.XPerMm*=2;p.YPerMm*=2;
            p.alignmentSize=new android.graphics.Point(p.rawSize.x/p.tile+1,p.rawSize.y/p.tile+1);
            p.tilesX=p.rawSize.x/800+1;
            // Sensor defect coordinates no longer address the reconstructed lattice.
            p.hotPixels=new android.graphics.Point[0];
        }
        p.cfaPattern=(byte)burst.red;p.quadCfa=false;p.remosaicDone=true;
        p.hexQuadProcessed=true;p.hexQuadPostDenoise=burst.postDenoise;
        p.whiteLevel=65535;
        Arrays.fill(p.blackLevel,0f);
        p.iso=burst.iso; // Keep measured exposureTime from CaptureResult.
        return result;
    }
}

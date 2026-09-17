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
    private final List<ImageFrame> frames;
    private HexQuadBurst(List<ImageFrame> frames,Parameters p) throws IOException {
        this.frames=new ArrayList<>(frames);
        width=p.rawSize.x;height=p.rawSize.y;
        int[] phase=PreferenceKeys.getRemosaicPhase();
        if(PreferenceKeys.getRemosaicBlockSize()!=4 || Math.floorMod(phase[0],8)!=0 || Math.floorMod(phase[1],8)!=0)
            throw new IOException("Нужны Tetra 4×4 и фаза 0,0");
        if(frames.size()!=6 || width<288 || height<288 || width%8!=0 || height%8!=0 || (long)width*height>16000000)
            throw new IOException("Нужны 6 RAW Tetra, до 16 МП. Используйте режим Фото и 4× ISZ телевика");
        red=RemosaicCore.emittedCfaPattern(p.cfaPattern);
        black=(p.blackLevel[0]+p.blackLevel[1]+p.blackLevel[2]+p.blackLevel[3])*.25f;
        white=p.whiteLevel;response=PreferenceKeys.isTetraResponseCorrection();
        ImageFrame ref=frames.get(0);
        if(ref.pair==null)throw new IOException("Нет параметров экспозиции RAW");
        iso=p.iso; // Measured sensor ISO from CaptureResult, not normalized UI ISO.
        if(iso<50||iso>12800||!Float.isFinite(black)||!Float.isFinite(white)||white<=black+1)
            throw new IOException("Неподдерживаемые ISO/уровни RAW");
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
            ByteBuffer header=ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(0x32515848).putInt(1).putInt(width).putInt(height).putInt(iso).putInt(red)
                    .putInt(0).putInt(0).putFloat(black).putFloat(white).putInt(response?1:0).putInt(6);
            header.position(0);while(header.hasRemaining())channel.write(header);
            for(ImageFrame frame:frames){ByteBuffer data=frame.buffer.duplicate();data.clear();while(data.hasRemaining())channel.write(data);}
        }
    }
    public static ByteBuffer process(Context context,List<ImageFrame> frames,Parameters p) throws Exception {
        HexQuadBurst burst=new HexQuadBurst(frames,p);
        ByteBuffer result=VivoNeuralClient.processBurst(context,burst);
        // Output uses the existing backend's Bayer orientation and average BL.
        // Sensor white balance and lens shading are applied once, downstream.
        p.cfaPattern=(byte)burst.red;p.quadCfa=false;p.remosaicDone=true;
        Arrays.fill(p.blackLevel,burst.black);
        p.iso=burst.iso; // Keep measured exposureTime from CaptureResult.
        return result;
    }
}

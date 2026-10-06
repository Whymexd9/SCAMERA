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
    final com.particlesdevs.photoncamera.settings.HexQuadOptions options;
    final float[] neutral;
    /** Main camera 2x2 Quad (2x ISZ, vendor IMX06C model): four frames, x1 output. */
    final boolean quad;
    /** 0 main IMX06C quad model, 1 tele HP9 ROI quad model (200 MP sensor). */
    final int quadModel;
    private final List<ImageFrame> frames;
    /** quad: the model of the SCAM HDR mosaic (true Quad 2x2, false Tetra 4x4). */
    private HexQuadBurst(List<ImageFrame> frames,Parameters p,boolean quad) throws IOException {
        this.frames=new ArrayList<>(frames);
        width=p.rawSize.x;height=p.rawSize.y;
        this.quad=quad;
        if(com.particlesdevs.photoncamera.util.Allocator.binning)
            throw new IOException(quad?"Quad 2×2 требует исходный Quad RAW: отключите программный биннинг":"HexQuad требует исходный Tetra RAW: отключите программный биннинг");
        if(com.particlesdevs.photoncamera.app.PhotonCamera.getSettings().aspect169)
            throw new IOException("Выберите 4:3: обрезка 16:9 меняет фазу мозаики");
        int[] phase=PreferenceKeys.getRemosaicPhase();
        if(quad){
            if(Math.floorMod(phase[0],4)!=0 || Math.floorMod(phase[1],4)!=0)
                throw new IOException("Quad 2×2: нужна фаза 0,0");
            if(frames.size()<4 || frames.size()>50 || width<544 || height<544 || width%8!=0 || height%8!=0 || (long)width*height>16000000)
                throw new IOException("Нужны 4–50 RAW Quad 2×2, до 16 МП. Используйте режим Фото и 2× ISZ");
        } else {
        if(PreferenceKeys.getRemosaicBlockSize()!=4 || Math.floorMod(phase[0],8)!=0 || Math.floorMod(phase[1],8)!=0)
            throw new IOException("Нужны Tetra 4×4 и фаза 0,0");
        if(frames.size()<6 || frames.size()>50 || width<288 || height<288 || width%8!=0 || height%8!=0 || (long)width*height>16000000)
            throw new IOException("Нужны 6–50 RAW Tetra, до 16 МП. Используйте режим Фото и 4× ISZ телевика");
        }
        quadModel=quad&&isHp9(p.cameraID)?1:0;
        red=RemosaicCore.emittedCfaPattern(p.cfaPattern);
        black=(p.blackLevel[0]+p.blackLevel[1]+p.blackLevel[2]+p.blackLevel[3])*.25f;
        white=p.whiteLevel;response=PreferenceKeys.isTetraResponseCorrection();

        if(p.whitePoint==null||p.whitePoint.length!=3)throw new IOException("Нет точки белого для HexQuad");
        neutral=p.whitePoint.clone();
        for(float v:neutral)if(!Float.isFinite(v)||v<.0001f||v>10000f)throw new IOException("Неверная точка белого HexQuad");
        ImageFrame ref=frames.get(0);
        zsl=ref.fromZsl;exposureSeconds=p.exposureTime;
        if(ref.pair==null)throw new IOException("Нет параметров экспозиции RAW");
        iso=p.iso; // Measured sensor ISO from CaptureResult, not normalized UI ISO.
        if(iso<50||iso>12800||!Float.isFinite(black)||!Float.isFinite(white)||white<=black+1)
            throw new IOException("Неподдерживаемые ISO/уровни RAW");
        // Inside SCAM HDR the network output stays at the input size and exposure (the mosaic feeds the N slots).
        options=(quad?PreferenceKeys.getQuadOptions(iso):PreferenceKeys.getHexQuadOptions(iso)).sameSizeIf(true);
        lumaPercent=options.lumaPercent;chromaPercent=options.chromaPercent;
        Set<Long> timestamps=new HashSet<>();
        for(ImageFrame frame:frames){
            if(frame.buffer==null || frame.width!=width || frame.height!=height || frame.buffer.capacity()!=(long)width*height*2)
                throw new IOException("Неполный RAW или неизвестный шаг строки");
            if(!timestamps.add(frame.timestamp)||frame.pair==null||frame.pair.isHighlightFrame||frame.pair.isLongFrame||
                    frame.pair.iso!=ref.pair.iso||frame.pair.exposure!=ref.pair.exposure)
                throw new IOException("Нужны "+frames.size()+" разных кадра с одинаковыми ISO и выдержкой");
        }
    }
    void write(File file) throws IOException {
        try(FileChannel channel=new FileOutputStream(file).getChannel()){
            ByteBuffer header=options.header(width,height,iso,red,black,white,response,neutral,frames.size(),quadModel);
            header.position(0);while(header.hasRemaining())channel.write(header);
            for(ImageFrame frame:frames){ByteBuffer data=frame.buffer.duplicate();data.clear();while(data.hasRemaining())channel.write(data);}
        }
    }
    /** HP9 is the 200 MP tele: maximum-resolution pixel array of 16320 px. */
    private static boolean isHp9(String cameraId){
        try{
            android.hardware.camera2.CameraManager manager=(android.hardware.camera2.CameraManager)
                    com.particlesdevs.photoncamera.app.PhotonCamera.getAppContext().getSystemService(Context.CAMERA_SERVICE);
            android.util.Size max=manager.getCameraCharacteristics(cameraId).get(
                    android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION);
            return max!=null&&max.getWidth()>=16000;
        }catch(Exception e){return false;}
    }
    /**
     * SCAM HDR on a mosaic stream: the equal-exposure N frames through the NPU model, returned as sensor-domain plain
     * bayer (uint16, the frames' black and white levels, output size = input size). The parameters are not touched.
     */
    public static ByteBuffer processForNice(Context context,List<ImageFrame> frames,Parameters p,boolean quad) throws Exception {
        HexQuadBurst burst=new HexQuadBurst(frames,p,quad);
        ByteBuffer result=VivoNeuralClient.processBurst(context,burst);
        // Normalized linear bayer16, black 0 / white 65535: back to the frames' own levels.
        ShortBuffer s=result.order(ByteOrder.nativeOrder()).asShortBuffer();
        float black=burst.black,span=burst.white-burst.black,gain=PreferenceKeys.niceInternalValue("mosaic_gain",1f);
        double sumIn=0,sumOut=0;int n=s.limit();
        for(int i=0;i<n;i++){
            int v=s.get(i)&0xffff;
            float raw=Math.max(0f,Math.min(burst.white,v*(1f/65535f)*span*gain+black));
            if((i&63)==0){sumIn+=v;sumOut+=raw;}
            s.put(i,(short)Math.round(raw));
        }
        com.particlesdevs.photoncamera.util.Log.i("NICE_MOSAIC","neural output "+(quad?"Quad 2x2":"HexQuad")+": mean "
                +String.format(java.util.Locale.US,"%.1f -> %.1f",sumIn/Math.max(1,(n+63)/64),sumOut/Math.max(1,(n+63)/64))
                +" (black "+black+", white "+burst.white+")");
        result.position(0);
        return result;
    }
}

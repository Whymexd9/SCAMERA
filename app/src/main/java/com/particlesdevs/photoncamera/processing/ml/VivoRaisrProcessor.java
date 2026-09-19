package com.particlesdevs.photoncamera.processing.ml;

import android.content.Context;
import android.graphics.Bitmap;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Experimental Vivo RAISR and SoftPQE, isolated from ART. Original firmware is hash-checked before loading. */
public final class VivoRaisrProcessor {
    private VivoRaisrProcessor() {}
    // System EGL/graphicsenv must resolve against system libbase/libutils, not
    // same-soname vendor copies. Vendor entry libraries are opened by absolute path.
    static final String LIBRARY_PATH = "/system/lib64:/system_ext/lib64:/vendor/lib64:/vendor/lib64/hw";
    static final String SOFT_LIBRARY_PATH = "/system/lib64:/system_ext/lib64:/vendor/npu/lib:/vendor/lib64";
    private static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
    private static void asset(Context c, String name, File target) throws IOException {
        try (InputStream in=c.getAssets().open("vivo-upscale/"+name);
             OutputStream out=new FileOutputStream(target)) {
            byte[] buffer=new byte[65536];int n;
            while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
        }
    }
    public static synchronized Bitmap process(Context context, Bitmap source, String cameraId,
                                               int iso, int scaleTenths) throws Exception {
        return process(context, source, cameraId, iso, scaleTenths, "raisr");
    }
    public static synchronized Bitmap process(Context context, Bitmap source, String cameraId,
                                               int iso, int scaleTenths, String backend) throws Exception {
        final boolean soft="softpqe".equals(backend);
        if(!soft && !"raisr".equals(backend))throw new IOException("Unknown Vivo backend");
        final String name=soft?"SOFTPQE":"RAISR";
        final String libraryPath=soft?SOFT_LIBRARY_PATH:LIBRARY_PATH;
        final String dspPath=(soft?"/vendor/npu/lib;":"/vendor/lib64/hw;")+
                "/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/vendor/dsp;/system/lib/rfsa/adsp";
        final int strength=percent(PreferenceKeys.getRaisrStrength());
        final int texture=percent(PreferenceKeys.getRaisrAliasingSuppression());
        final int halo=percent(PreferenceKeys.getRaisrHaloProtection());
        if(soft && !"3".equals(cameraId))throw new IOException("SoftPQE: доступен только профиль основной камеры");
        // Roles are Vivo SAT roles, never Camera2 IDs. Recovered jump table: 2 master, 8 tele-3x.
        if(!"3".equals(cameraId) && !"5".equals(cameraId))
            throw new IOException("Vivo "+name+": нет проверенного профиля для модуля "+cameraId);
        int w=source.getWidth(),h=source.getHeight();
        if(w%2!=0 || h%2!=0 || w<32 || h<32)throw new IOException("Vivo "+name+": неподдерживаемый размер");
        int scale=soft?20:Math.max(10,Math.min(40,scaleTenths));
        // Keep an exact isotropic ratio and even chroma dimensions, including 1.1x etc.
        int divisor=gcd(w,h), step=divisor/2;
        int multiplier=(int)Math.round(step*(scale/10.0));
        int ow=w/divisor*2*multiplier,oh=h/divisor*2*multiplier;
        if((long)ow*oh>96000000 || ow>19968 || oh>14000 || ow<w || oh<h)
            throw new IOException("Vivo "+name+": результат превышает допустимый размер");
        File dir=new File(context.getCacheDir(),"vivo-upscale-"+UUID.randomUUID());
        if(!dir.mkdir())throw new IOException("Не удалось создать папку апскейла");
        Process process=null;Thread reader=null;
        StringBuilder report=new StringBuilder("Vivo "+name+" experimental; camera="+cameraId+" ISO="+iso+" size="+w+"x"+h+" -> "+ow+"x"+oh+"\n");
        AtomicBoolean complete=new AtomicBoolean(false);
        try {
            File worker=new File(dir,"worker"), checks=new File(dir,"checks.sha256");
            File input=new File(dir,"input.yuv"),output=new File(dir,"output.yuv");
            asset(context,"arm64-v8a/vivo-upscale-worker",worker);
            asset(context,soft?"softpqe-master.sha256":"raisr-"+("5".equals(cameraId)?"tele_3x":"master")+".sha256",checks);
            if(!worker.setExecutable(true,true) || !output.createNewFile())throw new IOException("Не удалось подготовить апскейл");
            writeNv21(source,input);
            String command="/system/bin/sha256sum -c "+quote(checks.getAbsolutePath())+
                    " && exec /system/bin/env LD_LIBRARY_PATH="+quote(libraryPath)+" "+
                    "ADSP_LIBRARY_PATH="+quote(dspPath)+" "+
                    quote(worker.getAbsolutePath())+(soft?
                    " --softpqe /vendor/lib64/libvivo_softpqe.so /vendor/camera3rd/nti/softpqe/config/ui_normal_shot/aigc_24M ":
                    " --raisr /vendor/lib64/libvivo_raisr.so /vendor/camera3rd/nti/raisr ")+
                    quote(input.getAbsolutePath())+" "+quote(output.getAbsolutePath())+" "+w+" "+h+" "+ow+" "+oh+
                    " 17 "+Math.max(1,Math.min(1000000,iso))+" "+("5".equals(cameraId)?8:2)+
                    (soft?"":" "+strength+" "+texture+" "+halo);
            String launch="START "+name+" camera="+cameraId+" "+w+"x"+h+" -> "+ow+"x"+oh+
                    " ISO="+iso+" LD_LIBRARY_PATH="+libraryPath;
            report.append(launch).append('\n');Log.d("VivoUpscale",launch);
            process=new ProcessBuilder("su","-c",command).redirectErrorStream(true).start();
            process.getOutputStream().close();final Process child=process;
            reader=new Thread(()->{
                try(BufferedReader lines=new BufferedReader(new InputStreamReader(child.getInputStream()))) {
                    String line;while((line=lines.readLine())!=null) {
                        if(line.equals(name+" EXPERIMENT COMPLETE"))complete.set(true);
                        synchronized(report){if(report.length()<64000)report.append(line).append('\n');}
                        Log.d("VivoUpscale",line);
                    }
                } catch(IOException e){Log.e("VivoUpscale","Worker output error",e);}
            },"vivo-upscale-log");reader.setDaemon(true);reader.start();
            if(!process.waitFor(200,TimeUnit.SECONDS))throw new IOException("Vivo "+name+": тайм-аут");
            reader.join(3000);
            if(reader.isAlive() || process.exitValue()!=0 || !complete.get())throw new IOException(
                    "Vivo "+name+" не завершён (exit="+process.exitValue()+", marker="+complete.get()+
                    ", logPending="+reader.isAlive()+"); исходный снимок сохранён");
            if(output.length()!=(long)ow*oh*3/2)throw new IOException("Неверный размер результата Vivo "+name);
            return readNv21(output,ow,oh);
        } catch(Exception e) {
            synchronized(report){report.append("CLIENT STOP: ").append(e).append('\n');}throw e;
        } finally {
            if(process!=null){process.destroyForcibly();try{process.getInputStream().close();}catch(IOException ignored){}}
            synchronized(report){context.getSharedPreferences("vivo_upscale_report",Context.MODE_PRIVATE).edit().putString("report",report.toString()).apply();}
            File[] files=dir.listFiles();if(files!=null)for(File file:files)file.delete();dir.delete();
        }
    }
    private static int gcd(int a,int b){while(b!=0){int c=a%b;a=b;b=c;}return a;}
    private static int percent(int n){return Math.max(0,Math.min(100,n));}
    private static int clamp(int n){return Math.max(0,Math.min(255,n));}
    // BT.601 limited-range NV21. Conversion uses two rows, not a full RGB int[] copy.
    static void writeNv21(Bitmap bitmap, File file) throws IOException {
        int w=bitmap.getWidth(),h=bitmap.getHeight();int[] rgb=new int[w*2];
        byte[] y=new byte[w*2],vu=new byte[w];
        try(RandomAccessFile out=new RandomAccessFile(file,"rw")) {
            out.setLength((long)w*h*3/2);
            for(int row=0;row<h;row+=2) {
                bitmap.getPixels(rgb,0,w,0,row,w,2);
                for(int i=0;i<rgb.length;i++) {
                    int c=rgb[i],r=(c>>16)&255,g=(c>>8)&255,b=c&255;
                    y[i]=(byte)clamp(((66*r+129*g+25*b+128)>>8)+16);
                }
                for(int col=0;col<w;col+=2) {
                    int r=0,g=0,b=0;
                    for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++) {
                        int c=rgb[dy*w+col+dx];r+=(c>>16)&255;g+=(c>>8)&255;b+=c&255;
                    }
                    r=(r+2)/4;g=(g+2)/4;b=(b+2)/4;
                    vu[col]=(byte)clamp(((112*r-94*g-18*b+128)>>8)+128);
                    vu[col+1]=(byte)clamp(((-38*r-74*g+112*b+128)>>8)+128);
                }
                out.seek((long)row*w);out.write(y);
                out.seek((long)w*h+(long)row/2*w);out.write(vu);
            }
        }
    }
    static Bitmap readNv21(File file,int w,int h) throws IOException {
        Bitmap result=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        int[] rgb=new int[w*2];byte[] y=new byte[w*2],vu=new byte[w];
        try(RandomAccessFile in=new RandomAccessFile(file,"r")) {
            for(int row=0;row<h;row+=2) {
                in.seek((long)row*w);in.readFully(y);in.seek((long)w*h+(long)row/2*w);in.readFully(vu);
                for(int dy=0;dy<2;dy++)for(int col=0;col<w;col++) {
                    int l=Math.max(0,(y[dy*w+col]&255)-16),v=(vu[col&~1]&255)-128,u=(vu[(col&~1)+1]&255)-128;
                    rgb[dy*w+col]=0xff000000 | clamp((298*l+409*v+128)>>8)<<16 |
                            clamp((298*l-100*u-208*v+128)>>8)<<8 | clamp((298*l+516*u+128)>>8);
                }
                result.setPixels(rgb,0,w,0,row,w,2);
            }
            return result;
        } catch(IOException|RuntimeException e){result.recycle();throw e;}
    }
}

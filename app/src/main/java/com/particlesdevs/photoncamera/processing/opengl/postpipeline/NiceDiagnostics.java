package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.opengl.*;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.util.Log;
import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.*;
import static android.opengl.GLES30.*;

/** Per-capture diagnostics: sampled previews plus the exact bounded native input. */
public final class NiceDiagnostics {
    private static final ThreadLocal<Job> active=new ThreadLocal<>();
    private static final java.util.concurrent.ThreadPoolExecutor exports =
            new java.util.concurrent.ThreadPoolExecutor(1, 1, 30, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(2), r -> {
                        Thread t=new Thread(r,"NICE-diagnostic-export");t.setDaemon(true);return t;
                    });
    static { exports.allowCoreThreadTimeOut(true); }
    private static final class Job {
        final Context context;final File dir;final String name;
        Job(Context c) throws IOException {
            context=c;name="NICE-"+new SimpleDateFormat("yyyyMMdd-HHmmss-SSS",Locale.ROOT).format(new Date())+"-"+UUID.randomUUID().toString().substring(0,8);
            dir=new File(c.getCacheDir(),name);if(!dir.mkdir())throw new IOException("diagnostic directory");
        }
    }
    public static void begin(Context context,Parameters p,ImageFrame ref,VivoNiceScene scene) {
        try {
            Job j=new Job(context);active.set(j);
            String info="NICE diagnostic capture\nCamera="+p.physicalID+" CFA="+p.cfaPattern+" size="+p.rawSize
                    +"\nISO="+ref.measuredIso+" exposureNs="+ref.measuredExposure+" timestamp="+ref.timestamp
                    +"\nCamera2 noise slope="+ref.noiseSlope+" offset="+ref.noiseOffset
                    +"\n"+scene.describe()
                    +"\nwhite="+p.whiteLevel+" black="+Arrays.toString(p.blackLevel)+" neutral="+Arrays.toString(p.whitePoint)
                    +"\nsensorToProPhoto="+Arrays.toString(p.sensorToProPhoto)
                    +"\nPFM: little-endian float32 RGB; rows bottom first. Thumbnails are nearest sampled, sensor orientation."
                    +"\nPNG: clipped preview only; linear stages use gamma 1/2.2. PFM retains negative/HDR values."
                    +"\n00: Bayer-cell preview, black-subtracted, NO WB. 01: NPU tile before IVST."
                    +"\n02: reconstructed native RGB after IVST. Other files: named GPU stages."
                    +"\ninput.nch: exact native burst, NCH v8: 128-byte base header, 32-byte scene, seven AE records, then seven RAW16 planes."
                    +"\nIt retains every model slot and its exposure/ISO, black level and noise calibration for replay."
                    +"\nSensor portability is experimental; original model weights are unchanged.\n";
            Files.write(new File(j.dir,"README.txt").toPath(),info.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            int step=Math.max(1,(Math.max(ref.width,ref.height)/2+1023)/1024),w=ref.width/(2*step),h=ref.height/(2*step);
            ByteBuffer rgb=ByteBuffer.allocate(w*h*12).order(ByteOrder.nativeOrder());
            ByteBuffer raw=ref.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
                float[] c=new float[3];
                for(int phase=0;phase<4;phase++) {
                    int ch=phase==p.cfaPattern?0:phase==(p.cfaPattern^3)?2:1;
                    int offset=((y*2*step+(phase>>1))*ref.width+x*2*step+(phase&1))*2;
                    float value=Math.max(0,((raw.getShort(offset)&65535)-p.blackLevel[phase])/(p.whiteLevel-p.blackLevel[phase]));
                    c[ch]+=ch==1?value*.5f:value;
                }
                for(float v:c)rgb.putFloat(v);
            }
            rgb.flip();buffer("00-reference-bayer-no-wb",rgb,w,h,3,true);
        } catch(Exception e){Log.e("NICE_DIAG","Begin failed",e);}
    }
    public static void buffer(String stage,ByteBuffer bytes,int width,int height,int channels,boolean linear) {
        Job j=active.get();if(j==null)return;
        try {
            int step=Math.max(1,(Math.max(width,height)+1023)/1024),w=width/step,h=height/step;
            ByteBuffer in=bytes.duplicate().order(ByteOrder.nativeOrder());
            ByteBuffer out=ByteBuffer.allocate(w*h*12).order(ByteOrder.LITTLE_ENDIAN);
            int[] pixels=new int[w*h];long invalid=0;float min=Float.POSITIVE_INFINITY,max=Float.NEGATIVE_INFINITY;
            for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
                int pixel=0xff000000;
                for(int c=0;c<3;c++) {
                    float v=in.getFloat(((y*step)*width+x*step)*channels*4+c*4);
                    out.putFloat(((h-1-y)*w+x)*12+c*4,v);
                    if(!Float.isFinite(v)){invalid++;v=0;}else{min=Math.min(min,v);max=Math.max(max,v);}
                    float display=Math.max(0,Math.min(1,v));if(linear)display=(float)Math.pow(display,1/2.2);
                    pixel|=Math.round(display*255)<<(16-8*c);
                }
                pixels[y*w+x]=pixel;
            }
            try(OutputStream f=new FileOutputStream(new File(j.dir,stage+".pfm"))){
                f.write(("PF\n"+w+" "+h+"\n-1.0\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));f.write(out.array());
            }
            Bitmap bitmap=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
            try(OutputStream f=new FileOutputStream(new File(j.dir,stage+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,f);}finally{bitmap.recycle();}
            String line=stage+": "+w+"x"+h+" min="+min+" max="+max+" nonfinite="+invalid+"\n";
            try(FileWriter f=new FileWriter(new File(j.dir,"stages.txt"),true)){f.write(line);}
            Log.i("NICE_DIAG",line.trim());
        } catch(Exception e){Log.e("NICE_DIAG","Save stage failed: "+stage,e);}
    }
    public static void nativeFiles(File source,String report) {
        Job j=active.get();if(j==null)return;
        // Take ownership before the client's finally block removes its job.
        // Both directories are in this app's cache: rename avoids another full
        // seven-frame copy and lets the existing export queue do compression.
        File burst=new File(source,"input.f32");
        if(burst.isFile()&&burst.length()>=128&&burst.length()<=160+7*VivoNiceAe.TRANSPORT_BYTES+16000000L*14) {
            if(!burst.renameTo(new File(j.dir,"input.nch")))
                Log.w("NICE_DIAG","Could not retain native burst for replay");
        }
        try {
            File[] files=source.listFiles((d,n)->n.startsWith("nice-diag-")&&n.endsWith(".pfm"));
            if(files!=null)for(File f:files)Files.copy(f.toPath(),new File(j.dir,"01-"+f.getName()).toPath());
            Files.write(new File(j.dir,"native-report.txt").toPath(),report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }catch(Exception e){Log.e("NICE_DIAG","Native diagnostic copy failed",e);}
    }
    public static void gpu(String name,GLTexture source) {
        if(active.get()==null||source==null||!Arrays.asList("VivoNiceRgb","VivoHdrDenoise","LinearExposure","HeadroomRender","FalseColorSuppression","Sharpening").contains(name))return;
        // Read sampled rows through a temporary READ framebuffer. No diagnostic
        // shader/texture bindings can disturb the processing program's state.
        int[] oldRead=new int[1],framebuffer=new int[1];
        glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING,oldRead,0);
        try {
            glGenFramebuffers(1,framebuffer,0);glBindFramebuffer(GL_READ_FRAMEBUFFER,framebuffer[0]);
            glFramebufferTexture2D(GL_READ_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,source.mTextureID,0);
            if(glCheckFramebufferStatus(GL_READ_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)
                throw new IOException("Diagnostic framebuffer incomplete");
            int step=Math.max(1,(Math.max(source.mSize.x,source.mSize.y)+1023)/1024);
            int w=source.mSize.x/step,h=source.mSize.y/step;
            ByteBuffer row=ByteBuffer.allocateDirect(source.mSize.x*16).order(ByteOrder.nativeOrder());
            ByteBuffer sampled=ByteBuffer.allocate(w*h*12).order(ByteOrder.nativeOrder());
            for(int y=0;y<h;y++) {
                row.clear();glReadPixels(0,y*step,source.mSize.x,1,GL_RGBA,GL_FLOAT,row);
                if(glGetError()!=GL_NO_ERROR)throw new IOException("Diagnostic float readback unavailable");
                for(int x=0;x<w;x++)for(int c=0;c<3;c++)sampled.putFloat(row.getFloat((x*step*4+c)*4));
            }
            sampled.flip();
            buffer("03-"+name,sampled,w,h,3,
                    !Arrays.asList("HeadroomRender","FalseColorSuppression","Sharpening").contains(name));
        }catch(Exception e){Log.e("NICE_DIAG","GPU stage failed: "+name,e);}
        finally{glBindFramebuffer(GL_READ_FRAMEBUFFER,oldRead[0]);if(framebuffer[0]!=0)glDeleteFramebuffers(1,framebuffer,0);}
    }

    public static void finish() {
        Job j=active.get();active.remove();if(j==null)return;
        try { exports.execute(() -> export(j)); }
        catch(java.util.concurrent.RejectedExecutionException e) {
            Log.w("NICE_DIAG","Export queue full; diagnostic cache retained: " + j.dir);
        }
    }
    private static void export(Job j) {
        Uri uri=null;
        try {
            OutputStream stream;
            if(Build.VERSION.SDK_INT>=29) {
                ContentValues v=new ContentValues();v.put(MediaStore.Downloads.DISPLAY_NAME,j.name+".zip");
                v.put(MediaStore.Downloads.MIME_TYPE,"application/zip");v.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/SCAMERA");v.put(MediaStore.Downloads.IS_PENDING,1);
                uri=j.context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);
                if(uri==null)throw new IOException("Cannot create diagnostic ZIP");stream=j.context.getContentResolver().openOutputStream(uri);
            } else {
                File dir=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"SCAMERA");dir.mkdirs();stream=new FileOutputStream(new File(dir,j.name+".zip"));
            }
            if(stream==null)throw new IOException("Cannot open diagnostic ZIP");
            try(ZipOutputStream zip=new ZipOutputStream(stream)) {
                File[] files=j.dir.listFiles();if(files!=null)for(File f:files){zip.putNextEntry(new ZipEntry(f.getName()));Files.copy(f.toPath(),zip);zip.closeEntry();}
            }
            if(uri!=null){ContentValues v=new ContentValues();v.put(MediaStore.Downloads.IS_PENDING,0);j.context.getContentResolver().update(uri,v,null,null);}
            Log.i("NICE_DIAG","Saved Download/SCAMERA/"+j.name+".zip");
        }catch(Exception e){if(uri!=null)try{j.context.getContentResolver().delete(uri,null,null);}catch(Exception cleanup){Log.e("NICE_DIAG","Partial ZIP cleanup failed",cleanup);}Log.e("NICE_DIAG","Export failed; cache retained: "+j.dir,e);return;}
        File[] files=j.dir.listFiles();if(files!=null)for(File f:files)f.delete();j.dir.delete();
    }
}

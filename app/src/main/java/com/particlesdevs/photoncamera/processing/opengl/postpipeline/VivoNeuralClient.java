package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.content.Context;
import android.content.SharedPreferences;
import com.particlesdevs.photoncamera.util.Log;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/** Bounded one-job root process; vendor failures cannot crash the camera PID. */
public final class VivoNeuralClient {
    private VivoNeuralClient() {}
    // Process-local only: reinstall/restart clears it, and every job verifies
    // the bundled model/runtime hashes. Never reuse between ISO/CFA pairs.
    private static final java.util.Set<String> validatedHexProfiles = new java.util.HashSet<>();
    private static String quote(String s) { return "'"+s.replace("'","'\\''")+"'"; }
    public static synchronized void selfTest(Context context,Consumer<String> log) throws Exception {
        job(context,null,0,0,0,true,false,null,null,log);
    }
    public static synchronized ByteBuffer process(Context context,ByteBuffer raw,int w,int h,int redQuad) throws Exception {
        return job(context,raw,w,h,redQuad,false,false,null,null,line->Log.d("VivoNeural",line));
    }
    static synchronized ByteBuffer processBurst(Context context,HexQuadBurst burst) throws Exception {
        return job(context,null,burst.width,burst.height,burst.red,true,false,burst,null,line->Log.d("VivoNeural",line));
    }
    public static synchronized void selfTestNice(Context context,Consumer<String> log) throws Exception {
        job(context,null,0,0,0,true,true,null,null,log);
    }
    static synchronized ByteBuffer processNiceBurst(Context context,VivoNiceBurst burst) throws Exception {
        return job(context,null,burst.width,burst.height,burst.cfa,true,true,null,burst,line->Log.i("NICE_HDR",line));
    }
    private static ByteBuffer job(Context context,ByteBuffer raw,int w,int h,int redQuad,boolean hex,boolean nice,HexQuadBurst burst,VivoNiceBurst niceBurst,Consumer<String> observer) throws Exception {
        if(raw!=null && (w<8||h<8||w%8!=0||h%8!=0||(long)w*h>16000000||raw.remaining()!=(long)w*h*4))
            throw new IOException("Неподдерживаемый размер RAW");
        File dir=new File(context.getCacheDir(),"vivo-neural-job-"+UUID.randomUUID());
        if(!dir.mkdir())throw new IOException("Не удалось создать папку задания");
        Process process=null;
        final String profileKey=burst==null?"":burst.options.profileKey(burst.iso,burst.red);
        final boolean cachedProfile=burst!=null&&validatedHexProfiles.contains(profileKey);
        final long startMs=android.os.SystemClock.elapsedRealtime();
        // A self-test must never overwrite the failed photograph's report.
        SharedPreferences prefs=context.getSharedPreferences(
                niceBurst!=null?"vivo_nice_capture_report":nice?"vivo_nice_root_report":raw!=null||burst!=null?"vivo_neural_capture_report":"vivo_neural_report",Context.MODE_PRIVATE);
        StringBuilder report=new StringBuilder("SCAMERA: root neural inference job\n");
        prefs.edit().putString("report",report.toString()).putBoolean("complete",false).commit();
        final long[] lastReportWriteMs={startMs};
        Consumer<String> log=line->{
            synchronized(report){
                if(report.length()<128000)report.append(line).append('\n');
                long now=android.os.SystemClock.elapsedRealtime();
                // Keep every line in memory; batch disk commits so the reader
                // cannot stall the native stdout pipe on frequent progress logs.
                if(now-lastReportWriteMs[0]>=500||line.startsWith("STOP:")||line.startsWith("CLIENT STOP:")){
                    prefs.edit().putString("report",report.toString()).commit();lastReportWriteMs[0]=now;
                }
            }
            observer.accept(line);
        };
        try {
            if(burst!=null)log.accept("HEX SOURCE: "+(burst.zsl?"ZSL":"PSL")+" ISO="+burst.iso+
                    " exposure_s="+burst.exposureSeconds+" black="+burst.black+" white="+burst.white+
                    " display_exposure_ev="+burst.exposureEv+" neutral="+java.util.Arrays.toString(burst.neutral)+
                    " profile_cache="+cachedProfile+" luma="+burst.lumaPercent+" chroma="+burst.chromaPercent+
                    " additional_NR="+burst.postDenoise+" model=x"+burst.options.modelScale+
                    " full_resolution="+burst.options.fullResolution+" auto_ISO="+burst.options.autoIso+
                    " noise_variance_factors="+burst.options.noiseOverall+","+burst.options.noisePhoton+","+burst.options.noiseReadout+
                    " texture="+(burst.options.texture*100)+" compute="+(burst.options.gpu?"HYBRID CPU + GPU + NPU":"CPU + NPU"));
            // Extract only the assets in this APK. Missing bundles fail before
            // requesting root; no fallback to Vivo firmware model files.
            try(ZipFile apk=new ZipFile(context.getApplicationInfo().sourceDir)){
                java.util.ArrayList<String> names=new java.util.ArrayList<>();
                names.add("vivo-neural-worker");
                if(nice)for(String[] item:VivoNeuralWorker.NICE_FILES)names.add(item[0]);
                for(String[] item:hex?VivoNeuralWorker.HEX_FILES:VivoNeuralWorker.FILES)
                    if(!nice || item[0].endsWith(".so"))names.add(item[0]);
                for(String name:names){
                    String prefix=nice&&name.equals("nice-main-forward-v79.bin")?"assets/vivo-nice/arm64-v8a/":hex&&!name.equals("vivo-neural-worker")?"assets/vivo-hexquad/arm64-v8a/":"assets/vivo-neural/arm64-v8a/";
                    java.util.zip.ZipEntry entry=apk.getEntry(prefix+name);
                    if(entry==null)throw new IOException("Неполный APK: отсутствует "+name+". Установите сборку Bundled.");
                    File file=new File(dir,name);
                    try(InputStream in=apk.getInputStream(entry);FileOutputStream out=new FileOutputStream(file)){
                        if(!file.setReadOnly())throw new IOException("Не удалось защитить "+name);
                        byte[] buf=new byte[65536];int n;long total=0;
                        while((n=in.read(buf))!=-1){total+=n;if(total>128L*1024*1024)throw new IOException("Слишком большой ресурс");out.write(buf,0,n);}
                    }
                    if(name.equals("vivo-neural-worker")&&!file.setExecutable(true,true))throw new IOException("Не удалось разрешить запуск нейромодуля");
                }
            }
            final long assetsDone=android.os.SystemClock.elapsedRealtime();
            File input=new File(dir,"input.f32"),output=new File(dir,"output.f32");
            if(burst!=null)burst.write(input);
            if(niceBurst!=null)niceBurst.write(input);
            if(raw!=null || burst!=null || niceBurst!=null){
                if(raw!=null)try(FileChannel channel=new FileOutputStream(input).getChannel()){ByteBuffer data=raw.duplicate();while(data.hasRemaining())channel.write(data);}
                // Create as app UID before root truncates/writes it: no chmod,
                // chown, shared-storage input, or globally readable temp files.
                if(!output.createNewFile())throw new IOException("Не удалось создать файл результата");
            }
            final long inputDone=android.os.SystemClock.elapsedRealtime();
            log.accept("HEX CLIENT PREP ms: assets="+(assetsDone-startMs)+" raw_write="+(inputDone-assetsDone));
            String command="export CLASSPATH="+quote(context.getApplicationInfo().sourceDir)+
                    "; export LD_LIBRARY_PATH="+quote("/system/lib64:/system_ext/lib64:"+dir.getAbsolutePath()+":/vendor/lib64")+
                    "; export ADSP_LIBRARY_PATH="+quote(dir.getAbsolutePath()+";/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/vendor/dsp;/system/lib/rfsa/adsp")+
                    "; exec /system/bin/app_process64 /system/bin "+VivoNeuralWorker.class.getName()+" "+quote(dir.getAbsolutePath());
            if(raw!=null)command+=" "+quote(input.getAbsolutePath())+" "+quote(output.getAbsolutePath())+" "+w+" "+h+" "+redQuad;
            if(burst!=null)command+=(cachedProfile?" --hexquad-capture-cached ":" --hexquad-capture ")+quote(input.getAbsolutePath())+" "+quote(output.getAbsolutePath());
            else if(niceBurst!=null)command+=" --nice-capture "+quote(input.getAbsolutePath())+" "+quote(output.getAbsolutePath());
            else if(nice)command+=" --nice";
            else if(hex)command+=" --hexquad";
            log.accept("ROOT: запуск отдельного процесса; разрешите запрос root");
            process=new ProcessBuilder("su","-c",command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            final Process child=process;
            final boolean[] completed={false};
            Thread reader=new Thread(()->{
                try(BufferedReader lines=new BufferedReader(new InputStreamReader(child.getInputStream()))){String line;while((line=lines.readLine())!=null){if(niceBurst!=null?line.equals("NICE CAPTURE OK"):nice?line.equals("NICE RUNTIME CHECK COMPLETE"):burst!=null?line.equals("HEXQUAD CAPTURE OK"):hex?line.startsWith("HEXQUAD CHECK COMPLETE:"):line.equals("NEURAL JOB OK"))completed[0]=true;log.accept(line);}}
                catch(IOException e){log.accept("READ: "+e);}
            },"vivo-neural-output");
            reader.setDaemon(true);reader.start();
            if(!process.waitFor(burst!=null||niceBurst!=null?900:200,TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException("Тайм-аут нейромодуля; снимок не обработан");}
            reader.join(5000);
            if(reader.isAlive()||process.exitValue()!=0||!completed[0])throw new IOException(
                    (nice?"Проверка NICE не завершена. Скопируйте этот отчёт. ":"Нейроремозаик не завершён. Откройте Vivo Neural — проверка → ")+
                    (raw!=null||burst!=null?"Отчёт последней съёмки":"Скопировать отчёт")+".");
            if(raw==null&&burst==null&&niceBurst==null)return null;
            long expected=niceBurst!=null?(long)w*h*12:burst!=null?burst.options.outputBytes(w,h):(long)w*h*4;
            if(expected<=0||expected>Integer.MAX_VALUE)throw new IOException("Слишком большой нейрорезультат");
            if(output.length()!=expected)throw new IOException("Неверный размер нейрорезультата");
            final long readStart=android.os.SystemClock.elapsedRealtime();
            ByteBuffer result=(burst!=null||niceBurst!=null?com.particlesdevs.photoncamera.util.Allocator.allocate((int)expected):ByteBuffer.allocateDirect((int)expected));
            if(result==null)throw new IOException("Недостаточно памяти для результата");
            result.order(ByteOrder.nativeOrder());
            try(FileChannel channel=new FileInputStream(output).getChannel()){while(result.hasRemaining())if(channel.read(result)<0)throw new EOFException("Неполный результат");}
            catch(Exception e){if(burst!=null||niceBurst!=null)com.particlesdevs.photoncamera.util.Allocator.free(result);throw e;}
            result.flip();
            if(burst!=null){if(validatedHexProfiles.size()>=32)validatedHexProfiles.clear();validatedHexProfiles.add(profileKey);}
            log.accept("HEX CLIENT OUTPUT ms="+(android.os.SystemClock.elapsedRealtime()-readStart));
            log.accept("HEX CLIENT TOTAL ms="+(android.os.SystemClock.elapsedRealtime()-startMs));
            return result;
        } catch(Exception e){validatedHexProfiles.remove(profileKey);log.accept("CLIENT STOP: "+e.getMessage());throw e;}
        finally {
            if(process!=null && process.isAlive())process.destroyForcibly();
            synchronized(report){
                prefs.edit().putString("report",report.toString()).putBoolean("complete",true).commit();
            }
            File[] files=dir.listFiles();if(files!=null)for(File f:files)f.delete();dir.delete();
        }
    }
}

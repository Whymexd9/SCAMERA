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
    private static String quote(String s) { return "'"+s.replace("'","'\\''")+"'"; }
    public static synchronized void selfTest(Context context,Consumer<String> log) throws Exception {
        job(context,null,0,0,0,log);
    }
    public static synchronized ByteBuffer process(Context context,ByteBuffer raw,int w,int h,int redQuad) throws Exception {
        return job(context,raw,w,h,redQuad,line->Log.d("VivoNeural",line));
    }
    private static ByteBuffer job(Context context,ByteBuffer raw,int w,int h,int redQuad,Consumer<String> observer) throws Exception {
        if(raw!=null && (w<8||h<8||w%8!=0||h%8!=0||(long)w*h>16000000||raw.remaining()!=(long)w*h*4))
            throw new IOException("Неподдерживаемый размер RAW");
        File dir=new File(context.getCacheDir(),"vivo-neural-job-"+UUID.randomUUID());
        if(!dir.mkdir())throw new IOException("Не удалось создать папку задания");
        Process process=null;
        SharedPreferences prefs=context.getSharedPreferences("vivo_neural_report",Context.MODE_PRIVATE);
        StringBuilder report=new StringBuilder("SCAMERA: root neural inference job\n");
        prefs.edit().putString("report",report.toString()).putBoolean("complete",false).commit();
        Consumer<String> log=line->{
            synchronized(report){if(report.length()<128000)report.append(line).append('\n');prefs.edit().putString("report",report.toString()).commit();}
            observer.accept(line);
        };
        try {
            // Works with either extracted or APK-resident native libraries.
            // Copy only our own compiled helper, never vendor model binaries.
            File library=new File(dir,"libvivoNeuralWorker.so");
            try(ZipFile apk=new ZipFile(context.getApplicationInfo().sourceDir)){
                java.util.zip.ZipEntry entry=apk.getEntry("lib/arm64-v8a/libvivoNeuralWorker.so");
                if(entry==null)throw new IOException("В APK отсутствует нейромодуль ARM64");
                try(InputStream in=apk.getInputStream(entry);FileOutputStream out=new FileOutputStream(library)){
                    if(!library.setReadOnly())throw new IOException("Не удалось защитить нейромодуль");
                    byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
                }
            }
            File input=new File(dir,"input.f32"),output=new File(dir,"output.f32");
            if(raw!=null){
                try(FileChannel channel=new FileOutputStream(input).getChannel()){ByteBuffer data=raw.duplicate();while(data.hasRemaining())channel.write(data);}
                // Create as app UID before root truncates/writes it: no chmod,
                // chown, shared-storage input, or globally readable temp files.
                if(!output.createNewFile())throw new IOException("Не удалось создать файл результата");
            }
            String command="export CLASSPATH="+quote(context.getApplicationInfo().sourceDir)+
                    "; export LD_LIBRARY_PATH="+quote("/system/lib64:/system_ext/lib64:/vendor/lib64/hw:/vendor/lib64")+
                    "; export ADSP_LIBRARY_PATH="+quote("/vendor/lib64/hw;/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/vendor/dsp;/system/lib/rfsa/adsp")+
                    "; exec /system/bin/app_process64 /system/bin "+VivoNeuralWorker.class.getName()+" "+quote(dir.getAbsolutePath());
            if(raw!=null)command+=" "+quote(input.getAbsolutePath())+" "+quote(output.getAbsolutePath())+" "+w+" "+h+" "+redQuad;
            log.accept("ROOT: запуск отдельного процесса; разрешите запрос root");
            process=new ProcessBuilder("su","-c",command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            final Process child=process;
            final boolean[] completed={false};
            Thread reader=new Thread(()->{
                try(BufferedReader lines=new BufferedReader(new InputStreamReader(child.getInputStream()))){String line;while((line=lines.readLine())!=null){if(line.equals("NEURAL JOB OK"))completed[0]=true;log.accept(line);}}
                catch(IOException e){log.accept("READ: "+e);}
            },"vivo-neural-output");
            reader.setDaemon(true);reader.start();
            if(!process.waitFor(200,TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException("Тайм-аут нейромодуля; снимок не обработан");}
            reader.join(5000);
            if(reader.isAlive()||process.exitValue()!=0||!completed[0])throw new IOException("Нейроремозаик не прошёл проверку. Откройте Vivo Neural — проверка и скопируйте отчёт.");
            if(raw==null)return null;
            long expected=(long)w*h*4;
            if(output.length()!=expected)throw new IOException("Неверный размер нейрорезультата");
            ByteBuffer result=ByteBuffer.allocateDirect((int)expected).order(ByteOrder.nativeOrder());
            try(FileChannel channel=new FileInputStream(output).getChannel()){while(result.hasRemaining())if(channel.read(result)<0)throw new EOFException("Неполный результат");}
            result.flip();return result;
        } catch(Exception e){log.accept("CLIENT STOP: "+e.getMessage());throw e;}
        finally {
            if(process!=null && process.isAlive())process.destroyForcibly();
            prefs.edit().putBoolean("complete",true).commit();
            File[] files=dir.listFiles();if(files!=null)for(File f:files)f.delete();dir.delete();
        }
    }
}

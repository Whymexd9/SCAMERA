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

/** Bounded one-job worker process (app sandbox, or su when enabled); vendor failures cannot crash the camera PID. */
public final class VivoNeuralClient {
    private VivoNeuralClient() {}
    // Profiles that passed the full chart suite. Persisted per app build (the gate
    // is deterministic for one model/runtime/profile), so a restart does not redo
    // the 54-execution suite (~4 s); a validated profile still runs the four
    // smoke charts on every shot. Never reused between ISO/CFA pairs.
    private static final java.util.Set<String> validatedHexProfiles = new java.util.HashSet<>();
    private static boolean hexProfilesLoaded;
    private static SharedPreferences hexProfileStore(Context context) {
        return context.getSharedPreferences("vivo_hex_validated_profiles", Context.MODE_PRIVATE);
    }
    // Version code plus install time: any reinstall (new model/runtime/worker) revalidates.
    private static String hexProfileBuild(Context context) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.getLongVersionCode() + ":" + info.lastUpdateTime;
        } catch (Exception e) { return ""; }
    }
    private static void loadHexProfiles(Context context) {
        if (hexProfilesLoaded) return;
        hexProfilesLoaded = true;
        SharedPreferences store = hexProfileStore(context);
        String build = hexProfileBuild(context);
        if (!build.isEmpty() && build.equals(store.getString("build", "")))
            validatedHexProfiles.addAll(store.getStringSet("profiles", java.util.Collections.emptySet()));
    }
    private static void saveHexProfiles(Context context) {
        hexProfileStore(context).edit().putString("build", hexProfileBuild(context))
                .putStringSet("profiles", new java.util.HashSet<>(validatedHexProfiles)).apply();
    }
    private static String quote(String s) { return "'"+s.replace("'","'\\''")+"'"; }
    public static synchronized void selfTest(Context context,Consumer<String> log) throws Exception {
        job(context,null,0,0,0,true,false,false,null,null,log);
    }
    public static synchronized ByteBuffer process(Context context,ByteBuffer raw,int w,int h,int redQuad) throws Exception {
        return job(context,raw,w,h,redQuad,false,false,false,null,null,line->Log.d("VivoNeural",line));
    }
    static synchronized ByteBuffer processBurst(Context context,HexQuadBurst burst) throws Exception {
        return job(context,null,burst.width,burst.height,burst.red,true,false,false,burst,null,line->Log.d("VivoNeural",line));
    }
    public static synchronized void selfTestNice(Context context,Consumer<String> log) throws Exception {
        job(context,null,0,0,0,true,true,false,null,null,log);
    }
    static synchronized ByteBuffer processNiceBurst(Context context,NiceTransport burst) throws Exception {
        return job(context,null,burst.width(),burst.height(),burst.cfa(),true,true,false,null,burst,line->Log.i("NICE_HDR",line));
    }
    public static synchronized void selfTestNiceTone(Context context,Consumer<String> log) throws Exception {
        job(context,null,0,0,0,true,true,true,null,null,log);
    }
    /** memfd shared with the root worker through /proc/<pid>/fd/<n>. */
    private static final class SharedMemory implements java.io.Closeable {
        final android.os.ParcelFileDescriptor fd;
        private SharedMemory(android.os.ParcelFileDescriptor fd) { this.fd = fd; }
        static SharedMemory create(String name) {
            if (android.os.Build.VERSION.SDK_INT < 30) return null;
            try {
                java.io.FileDescriptor raw = android.system.Os.memfd_create(name, android.system.OsConstants.MFD_CLOEXEC);
                try { return new SharedMemory(android.os.ParcelFileDescriptor.dup(raw)); }
                finally { android.system.Os.close(raw); }
            } catch (Exception e) {
                Log.w("NICE_HDR", "memfd unavailable, using files: " + e);
                return null;
            }
        }
        String path() { return "/proc/" + android.os.Process.myPid() + "/fd/" + fd.getFd(); }
        long size() throws IOException {
            try { return android.system.Os.fstat(fd.getFileDescriptor()).st_size; }
            catch (android.system.ErrnoException e) { throw new IOException(e); }
        }
        FileChannel writeChannel() { return new FileOutputStream(fd.getFileDescriptor()).getChannel(); }
        FileChannel readChannel() { return new FileInputStream(fd.getFileDescriptor()).getChannel(); }
        @Override public void close() { try { fd.close(); } catch (IOException ignored) {} }
    }
    private static File assetCacheDir(Context context) throws IOException {
        long stamp;
        try{stamp=context.getPackageManager().getPackageInfo(context.getPackageName(),0).lastUpdateTime;}
        catch(android.content.pm.PackageManager.NameNotFoundException e){throw new IOException(e);}
        File root=new File(context.getFilesDir(),"neural-assets");
        File dir=new File(root,Long.toString(stamp));
        if(!dir.isDirectory()){
            File[] old=root.listFiles();
            if(old!=null)for(File d:old)deleteTree(d);
            if(!dir.mkdirs()&&!dir.isDirectory())throw new IOException("Не удалось создать кэш ресурсов");
        }
        return dir;
    }
    private static void deleteTree(File file) {
        File[] children=file.isDirectory()?file.listFiles():null;
        if(children!=null)for(File child:children)deleteTree(child);
        file.delete();
    }
    private static File cachedAsset(Context context,ZipFile apk,java.util.zip.ZipEntry entry,String prefix,String name) throws IOException {
        File dir=new File(assetCacheDir(context),prefix.replace('/','_'));
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Не удалось создать кэш ресурсов");
        File file=new File(dir,name);
        if(file.isFile()&&file.length()==entry.getSize())return file;
        File tmp=new File(dir,name+".tmp");
        tmp.delete();
        try(InputStream in=apk.getInputStream(entry);FileOutputStream out=new FileOutputStream(tmp)){
            byte[] buf=new byte[65536];int n;long total=0;
            while((n=in.read(buf))!=-1){total+=n;if(total>128L*1024*1024)throw new IOException("Слишком большой ресурс");out.write(buf,0,n);}
        }
        if(!tmp.setReadOnly())throw new IOException("Не удалось защитить "+name);
        if(name.equals("vivo-neural-worker")&&!tmp.setExecutable(true,true))throw new IOException("Не удалось разрешить запуск нейромодуля");
        file.delete();
        if(!tmp.renameTo(file))throw new IOException("Не удалось сохранить "+name);
        return file;
    }
    private static ByteBuffer job(Context context,ByteBuffer raw,int w,int h,int redQuad,boolean hex,boolean nice,boolean niceTone,HexQuadBurst burst,NiceTransport niceBurst,Consumer<String> observer) throws Exception {
        if(raw!=null && (w<8||h<8||w%8!=0||h%8!=0||(long)w*h>16000000||raw.remaining()!=(long)w*h*4))
            throw new IOException("Неподдерживаемый размер RAW");
        File dir=new File(context.getCacheDir(),"vivo-neural-job-"+UUID.randomUUID());
        if(!dir.mkdir())throw new IOException("Не удалось создать папку задания");
        com.particlesdevs.photoncamera.util.WorkerSpawn.Child process=null;
        SharedMemory niceIn=null,niceOut=null;
        // Without root: the worker and its QNN/CRE runtime are installed as native
        // libraries and run as the app's own child process (sandboxed, same NPU/GPU).
        // su remains the optional fallback for builds without them.
        final boolean direct=(hex||nice) && com.particlesdevs.photoncamera.util.WorkerSpawn.available(context);
        final String profileKey=burst==null?"":burst.options.profileKey(burst.iso,burst.red);
        if(burst!=null)loadHexProfiles(context);
        final boolean cachedProfile=burst!=null&&validatedHexProfiles.contains(profileKey);
        final long startMs=android.os.SystemClock.elapsedRealtime();
        // A self-test must never overwrite the failed photograph's report.
        SharedPreferences prefs=context.getSharedPreferences(
                niceBurst!=null?"vivo_nice_capture_report":niceTone?"vivo_nice_tone_report":nice?"vivo_nice_root_report":raw!=null||burst!=null?"vivo_neural_capture_report":"vivo_neural_report",Context.MODE_PRIVATE);
        StringBuilder report=new StringBuilder("SCAMERA: neural inference job\n");
        // P27: a retry after a failed merge keeps the first attempt in its report.
        final String carried=carryReport;
        if(niceBurst!=null&&carried!=null)report.append("PREVIOUS ATTEMPT:\n").append(carried).append("\nRETRY:\n");
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
                    " neutral="+java.util.Arrays.toString(burst.neutral)+
                    " profile_cache="+cachedProfile+" luma="+burst.lumaPercent+" chroma="+burst.chromaPercent+
                    " model=x"+burst.options.modelScale+" auto_ISO="+burst.options.autoIso+
                    (burst.quad?" quad_model="+(burst.quadModel==1?"HP9 ROI (tele)":"IMX06C (main)"):"")+
                    " noise_variance_factors="+burst.options.noiseOverall+","+burst.options.noisePhoton+","+burst.options.noiseReadout+
                    " texture="+(burst.options.texture*100)+" compute="+(burst.options.gpu?"HYBRID CPU + GPU + NPU":"CPU + NPU"));
            // Extract only the assets in this APK. Missing bundles fail before
            // requesting root; no fallback to Vivo firmware model files.
            if(!direct && !com.particlesdevs.photoncamera.settings.PreferenceKeys.isRootEnabled())
                throw new IOException(hex||nice?"Обработчик не установлен в этой сборке; включите «Root-доступ» в настройках Обработки Vivo"
                        :"Этот нейроремозаик работает только с root: включите «Root-доступ» в настройках Обработки Vivo");
            try(ZipFile apk=new ZipFile(context.getApplicationInfo().sourceDir)){
                java.util.ArrayList<String> names=new java.util.ArrayList<>();
                java.util.HashSet<String> niceAssets=new java.util.HashSet<>();
                names.add("vivo-neural-worker");
                if(nice)for(String[] item:niceTone?VivoNeuralWorker.NICE_TONE_FILES:VivoNeuralWorker.NICE_FILES){names.add(item[0]);niceAssets.add(item[0]);}
                final boolean quad=burst!=null&&burst.quad;
                for(String[] item:hex?VivoNeuralWorker.HEX_FILES:VivoNeuralWorker.FILES)
                    if(!nice && !quad || item[0].endsWith(".so"))names.add(item[0]);
                if(quad)for(String[] item:VivoNeuralWorker.QUAD_FILES)names.add(item[0]);
                for(String name:names){
                    if(direct && name.equals("vivo-neural-worker"))continue;
                    String prefix=niceAssets.contains(name)?"assets/vivo-nice/arm64-v8a/":hex&&!name.equals("vivo-neural-worker")?"assets/vivo-hexquad/arm64-v8a/":"assets/vivo-neural/arm64-v8a/";
                    // The QNN/CRE runtime of the NICE and HexQuad sets ships once, as native
                    // libraries: the sandbox can map executable code only from nativeLibraryDir,
                    // and the su launcher reads the same files. (The tele576 set has its own
                    // QNN build and stays an asset.)
                    final boolean shared=name.endsWith(".so") && !prefix.equals("assets/vivo-neural/arm64-v8a/");
                    File installed=shared?com.particlesdevs.photoncamera.util.WorkerSpawn.library(context,name):null;
                    if(direct&&shared&&installed==null)throw new IOException("Неполная установка: нет "+name);
                    if(installed!=null){
                        try{android.system.Os.symlink(installed.getAbsolutePath(),new File(dir,name).getAbsolutePath());}
                        catch(android.system.ErrnoException e){throw new IOException("Не удалось подготовить "+name+": "+e.getMessage());}
                        continue;
                    }
                    java.util.zip.ZipEntry entry=apk.getEntry(prefix+name);
                    if(entry==null)throw new IOException("Неполный APK: отсутствует "+name+". Установите сборку Bundled.");
                    // Extracted once per installed APK (was every shot, ~0.2 s); the job
                    // directory only links to it. The root worker still verifies hashes.
                    File cached=cachedAsset(context,apk,entry,prefix,name);
                    try{android.system.Os.symlink(cached.getAbsolutePath(),new File(dir,name).getAbsolutePath());}
                    catch(android.system.ErrnoException e){throw new IOException("Не удалось подготовить "+name+": "+e.getMessage());}
                }
            }
            // "Bundled" CRE source: the worker skips /vendor and loads the APK copy with
            // compat stubs, the path non-vivo devices take automatically.
            // "Vendor" never falls back to the APK copy.
            if(niceBurst!=null){
                String creSource=com.particlesdevs.photoncamera.settings.PreferenceKeys.getNiceCreSource();
                String marker="bundled".equals(creSource)?"cre-force-bundled":"vendor".equals(creSource)?"cre-vendor-only":null;
                if(marker!=null && !new File(dir,marker).createNewFile())throw new IOException("Не удалось создать маркер CRE");
            }
            // Only the hybrid burst asks for the hybrid merge: a VivoNiceBurst reaches the worker when the engine is
            // the network or the burst is a Quad/Tetra mosaic (HdrxProcessor), and the hybrid would read the mosaic
            // as plain Bayer (vivo tele in ISZ: blue image, 3 October). Without the model the worker falls back itself.
            final boolean hybridMerge=niceBurst instanceof LmcHybridBurst;
            if(hybridMerge){
                // LMC hybrid merge (vivo-nice-hybrid.h): no neural model, any GPU. The worker reads the marker
                // and the tuning lines written from the SCAM HDR settings.
                if(!new File(dir,"hybrid-merge").createNewFile())throw new IOException("Не удалось создать маркер склейки Hybrid");
                // P30: the worker keeps its compiled GPU programs in the app's cache (−0.6 s per shot after the first).
                File glCache=new File(context.getCacheDir(),"hybrid-gl");
                if(glCache.isDirectory()||glCache.mkdirs())
                    try(java.io.FileWriter cw=new java.io.FileWriter(new File(dir,"gl-cache"))){cw.write(glCache.getAbsolutePath());}
                String tuning=com.particlesdevs.photoncamera.settings.PreferenceKeys.hybridTuningText()+((LmcHybridBurst)niceBurst).tuningOverride();
                if(!tuning.isEmpty())try(java.io.FileWriter tw=new java.io.FileWriter(new File(dir,"hybrid_tuning.txt"))){tw.write(tuning);}
                log.accept("CLIENT: LMC hybrid merge requested"+(tuning.isEmpty()?"":" tuning="+tuning.replace('\n',' ')));
            }
            if(niceBurst!=null){
                // Developer switch (created with adb): dump the network's input/output tiles.
                File external=context.getExternalFilesDir(null);
                if(external!=null && new File(external,"dump-forward").exists()){
                    try(java.io.FileWriter marker=new java.io.FileWriter(new File(dir,"dump-forward"))){marker.write(external.getAbsolutePath());}
                }
            }
            final long assetsDone=android.os.SystemClock.elapsedRealtime();
            File input=new File(dir,"input.f32"),output=new File(dir,"output.f32");
            if(burst!=null)burst.write(input);
            // NICE: burst and result through shared memory (memfd) instead of ~0.6 GB
            // written to and read back from flash per shot; the root worker opens
            // them as /proc/<pid>/fd/<n>. Files remain the fallback.
            // P30: with the app-process worker and shared memory the worker starts first and the burst is written while it
            // starts (spawn, CRE and GPU driver), then a byte on a pipe (job file "wait-go") lets it map the burst.
            boolean deferredWrite=false;
            if(niceBurst!=null){
                niceIn=SharedMemory.create("scamera-nice-in");
                niceOut=niceIn==null?null:SharedMemory.create("scamera-nice-out");
                if(niceOut!=null){
                    deferredWrite=direct;
                    if(!deferredWrite)try(FileChannel channel=niceIn.writeChannel()){niceBurst.write(channel);}
                } else {
                    if(niceIn!=null){niceIn.close();niceIn=null;}
                    niceBurst.write(input);
                }
            }
            android.os.ParcelFileDescriptor[] goPipe=null;
            if(deferredWrite){
                goPipe=android.os.ParcelFileDescriptor.createPipe();
                try(java.io.FileWriter marker=new java.io.FileWriter(new File(dir,"wait-go"))){marker.write("5");}
            }
            if(raw!=null || burst!=null || (niceBurst!=null && niceOut==null)){
                if(raw!=null)try(FileChannel channel=new FileOutputStream(input).getChannel()){ByteBuffer data=raw.duplicate();while(data.hasRemaining())channel.write(data);}
                // Create as app UID before root truncates/writes it: no chmod,
                // chown, shared-storage input, or globally readable temp files.
                if(!output.createNewFile())throw new IOException("Не удалось создать файл результата");
            }
            final long inputDone=android.os.SystemClock.elapsedRealtime();
            log.accept("HEX CLIENT PREP ms: assets="+(assetsDone-startMs)+" raw_write="+(inputDone-assetsDone));
            if(direct){
                String dirPath=dir.getAbsolutePath();
                java.util.ArrayList<String> args=new java.util.ArrayList<>();
                android.os.ParcelFileDescriptor[] fds=new android.os.ParcelFileDescriptor[0];
                if(burst!=null)java.util.Collections.addAll(args,burst.quad?"--quad-capture":cachedProfile?"--hexquad-capture-cached":"--hexquad-capture",
                        dirPath,input.getAbsolutePath(),output.getAbsolutePath());
                else if(niceBurst!=null){
                    // memfd burst/result inherited as fd 3/4 (the sandbox cannot open /proc/<pid>/fd).
                    if(niceOut!=null)fds=goPipe!=null?new android.os.ParcelFileDescriptor[]{niceIn.fd,niceOut.fd,goPipe[0]}
                            :new android.os.ParcelFileDescriptor[]{niceIn.fd,niceOut.fd};
                    java.util.Collections.addAll(args,"--nice-capture",dirPath,niceOut!=null?"fd:3":input.getAbsolutePath(),
                            niceOut!=null?"fd:4":output.getAbsolutePath());
                }
                else if(niceTone)java.util.Collections.addAll(args,"--nice-tone-check",dirPath);
                else if(nice)java.util.Collections.addAll(args,"--nice-check",dirPath);
                else java.util.Collections.addAll(args,"--hexquad-check",dirPath);
                log.accept("WORKER: отдельный процесс приложения (без root)");
                process=com.particlesdevs.photoncamera.util.WorkerSpawn.start(context,args,
                        com.particlesdevs.photoncamera.util.WorkerSpawn.environment(context,dir),fds);
                if(goPipe!=null){
                    goPipe[0].close();
                    final long writeStarted=android.os.SystemClock.elapsedRealtime();
                    try(java.io.OutputStream go=new android.os.ParcelFileDescriptor.AutoCloseOutputStream(goPipe[1])){
                        try(FileChannel channel=niceIn.writeChannel()){niceBurst.write(channel);}
                        go.write(1); // closing the pipe without this byte stops the worker
                    }
                    log.accept("HEX CLIENT PREP ms: burst written while the worker started in "+(android.os.SystemClock.elapsedRealtime()-writeStarted));
                }
            } else {
            String command="export CLASSPATH="+quote(context.getApplicationInfo().sourceDir)+
                    "; export LD_LIBRARY_PATH="+quote("/system/lib64:/system_ext/lib64:"+dir.getAbsolutePath()+":/vendor/lib64")+
                    "; export ADSP_LIBRARY_PATH="+quote(dir.getAbsolutePath()+";/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/vendor/dsp;/system/lib/rfsa/adsp")+
                    "; exec /system/bin/app_process64 /system/bin "+VivoNeuralWorker.class.getName()+" "+quote(dir.getAbsolutePath());
            if(raw!=null)command+=" "+quote(input.getAbsolutePath())+" "+quote(output.getAbsolutePath())+" "+w+" "+h+" "+redQuad;
            if(burst!=null)command+=(burst.quad?" --quad-capture ":cachedProfile?" --hexquad-capture-cached ":" --hexquad-capture ")+quote(input.getAbsolutePath())+" "+quote(output.getAbsolutePath());
            else if(niceBurst!=null)command+=" --nice-capture "+quote(niceIn!=null?niceIn.path():input.getAbsolutePath())
                    +" "+quote(niceOut!=null?niceOut.path():output.getAbsolutePath());
            else if(niceTone)command+=" --nice-tone-check";
            else if(nice)command+=" --nice";
            else if(hex)command+=" --hexquad";
            log.accept("ROOT: запуск отдельного процесса; разрешите запрос root");
            process=com.particlesdevs.photoncamera.util.WorkerSpawn.Child.of(new ProcessBuilder("su","-c",command).redirectErrorStream(true).start());
            }
            final com.particlesdevs.photoncamera.util.WorkerSpawn.Child child=process;
            final boolean[] completed={false};
            Thread reader=new Thread(()->{
                try(BufferedReader lines=new BufferedReader(new InputStreamReader(child.output))){String line;while((line=lines.readLine())!=null){if(niceBurst!=null?line.equals("NICE CAPTURE OK"):niceTone?line.startsWith("NICE TONE RUNTIME CHECK COMPLETE:"):nice?line.equals("NICE RUNTIME CHECK COMPLETE"):burst!=null?line.equals("HEXQUAD CAPTURE OK"):hex?line.startsWith("HEXQUAD CHECK COMPLETE:"):line.equals("NEURAL JOB OK"))completed[0]=true;log.accept(line);}}
                catch(IOException e){log.accept("READ: "+e);}
            },"vivo-neural-output");
            reader.setDaemon(true);reader.start();
            if(!process.waitFor(TimeUnit.SECONDS.toMillis(burst!=null||niceBurst!=null?900:niceTone?420:200))){process.destroy();throw new IOException("Тайм-аут нейромодуля; снимок не обработан");}
            reader.join(5000);
            if(process.exitValue()!=0||!completed[0]){
                // P26: the X200 Pro (Mali) worker vanished mid-merge with nothing in the log; say how it ended.
                final int code=process.exitValue();
                final String[] signals={"","SIGHUP","SIGINT","SIGQUIT","SIGILL","SIGTRAP","SIGABRT","SIGBUS","SIGFPE","SIGKILL (low memory or killed)","SIGUSR1","SIGSEGV"};
                log.accept("WORKER EXIT: "+(code>128&&code-128<signals.length?"signal "+(code-128)+" "+signals[code-128]
                        :code>128?"signal "+(code-128):"code "+code)+(completed[0]?"":", no completion line"));
            }
            if(reader.isAlive()||process.exitValue()!=0||!completed[0])throw new IOException(
                    niceBurst instanceof LmcHybridBurst?"Hybrid: склейка не завершена. Отчёт: SCAM HDR — проверка запуска → Отчёт последней съёмки SCAM HDR.":
                    niceBurst!=null?"SCAM HDR не завершён. Откройте SCAM HDR — проверка запуска → Отчёт последней съёмки SCAM HDR.":
                    (nice?"Проверка SCAM HDR не завершена. Скопируйте этот отчёт. ":"Нейроремозаик не завершён. Откройте Vivo Neural — проверка → ")+
                    (raw!=null||burst!=null?"Отчёт последней съёмки":"Скопировать отчёт")+".");
            if(raw==null&&burst==null&&niceBurst==null)return null;
            final int ow=niceBurst!=null?niceBurst.outputWidth():w,oh=niceBurst!=null?niceBurst.outputHeight():h;
            long expected=niceBurst!=null?(long)ow*oh*12:burst!=null?burst.options.outputBytes(w,h):(long)w*h*4;
            if(expected<=0||expected>Integer.MAX_VALUE)throw new IOException("Слишком большой нейрорезультат");
            final long outputBytes=niceOut!=null?niceOut.size():output.length();
            // Trailers after the RGB, each optional, in this order: merged Bayer RAW (sensor grid, w*h*2, if requested), the
            // effective-frame map (OUTPUT grid, ow*oh), the clip flags (OUTPUT grid, ow*oh; LMC hybrid, only if requested and only
            // after the map). The fullest layout that matches the file size wins.
            long dngBytes=0,effBytes=0,clipBytes=0;boolean sized=niceBurst==null&&outputBytes==expected;
            if(niceBurst!=null){
                final long rawTrailer=(long)w*h*2,outTrailer=(long)ow*oh;
                search:
                for(long d:niceBurst.mergedDng()?new long[]{rawTrailer,0}:new long[]{0})
                    for(long e:new long[]{outTrailer,0})
                        for(long c:niceBurst.clipFlags()&&e>0?new long[]{outTrailer,0}:new long[]{0})
                            if(outputBytes==expected+d+e+c){dngBytes=d;effBytes=e;clipBytes=c;sized=true;break search;}
            }
            if(!sized)throw new IOException("Неверный размер нейрорезультата");
            if(niceBurst!=null){VivoNiceBurst.lastMergedDng=null;VivoNiceBurst.lastEffectiveFrames=null;VivoNiceRgb.lastClipFlags=null;
                LmcHybridBurst.lastBentoApplied=report.indexOf("HYBRID BENTO: applied")>=0;
                LmcHybridBurst.lastBentoFactor=reportNumber(report,"HYBRID BENTO: applied","factor=",1f);
                LmcHybridBurst.lastBentoUsClipped=reportNumber(report,"HYBRID BENTO: applied","usClippedRatio=",0f);}
            final long readStart=android.os.SystemClock.elapsedRealtime();
            ByteBuffer result=(burst!=null||niceBurst!=null?com.particlesdevs.photoncamera.util.Allocator.allocate((int)expected):ByteBuffer.allocateDirect((int)expected));
            if(result==null)throw new IOException("Недостаточно памяти для результата");
            result.order(ByteOrder.nativeOrder());
            try(FileChannel channel=niceOut!=null?niceOut.readChannel():new FileInputStream(output).getChannel()){
                long position=0;
                while(result.hasRemaining()){int n=channel.read(result,position);if(n<0)throw new EOFException("Неполный результат");position+=n;}
            }
            catch(Exception e){if(burst!=null||niceBurst!=null)com.particlesdevs.photoncamera.util.Allocator.free(result);throw e;}
            result.flip();
            if(dngBytes>0){
                ByteBuffer dng=com.particlesdevs.photoncamera.util.Allocator.allocate((int)dngBytes);
                if(dng!=null){
                    dng.order(ByteOrder.nativeOrder());
                    try(FileChannel channel=niceOut!=null?niceOut.readChannel():new FileInputStream(output).getChannel()){
                        long position=expected;
                        while(dng.hasRemaining()){int n=channel.read(dng,position);if(n<0)throw new EOFException("Неполный RAW");position+=n;}
                        dng.flip();VivoNiceBurst.lastMergedDng=dng;
                    }catch(Exception e){com.particlesdevs.photoncamera.util.Allocator.free(dng);log.accept("CLIENT: merged DNG not read: "+e);}
                }
            }
            if(effBytes>0){
                ByteBuffer eff=ByteBuffer.allocateDirect((int)effBytes);
                try(FileChannel channel=niceOut!=null?niceOut.readChannel():new FileInputStream(output).getChannel()){
                    long position=expected+dngBytes;
                    while(eff.hasRemaining()){int n=channel.read(eff,position);if(n<0)throw new EOFException("Неполная карта кадров");position+=n;}
                    eff.flip();VivoNiceBurst.lastEffectiveFrames=eff;
                }catch(Exception e){log.accept("CLIENT: effective-frame map not read: "+e);}
            }
            if(clipBytes>0){
                ByteBuffer clip=ByteBuffer.allocateDirect((int)clipBytes);
                try(FileChannel channel=niceOut!=null?niceOut.readChannel():new FileInputStream(output).getChannel()){
                    long position=expected+dngBytes+effBytes;
                    while(clip.hasRemaining()){int n=channel.read(clip,position);if(n<0)throw new EOFException("Неполные флаги клипа");position+=n;}
                    clip.flip();VivoNiceRgb.lastClipFlags=clip;
                }catch(Exception e){log.accept("CLIENT: clip flags not read: "+e);}
            }else if(niceBurst!=null&&niceBurst.clipFlags())log.accept("CLIENT: clip flags asked for but not returned");
            if(niceBurst!=null)NiceDiagnostics.buffer("02-after-ivst",result,ow,oh,3,true);
            if(burst!=null){if(validatedHexProfiles.size()>=32)validatedHexProfiles.clear();validatedHexProfiles.add(profileKey);saveHexProfiles(context);}
            log.accept("HEX CLIENT OUTPUT ms="+(android.os.SystemClock.elapsedRealtime()-readStart));
            log.accept("HEX CLIENT TOTAL ms="+(android.os.SystemClock.elapsedRealtime()-startMs));
            return result;
        } catch(Exception e){if(validatedHexProfiles.remove(profileKey))saveHexProfiles(context);log.accept("CLIENT STOP: "+e.getMessage());throw e;}
        finally {
            if(process!=null && process.isAlive())process.destroy();
            if(niceIn!=null)niceIn.close();
            if(niceOut!=null)niceOut.close();
            synchronized(report){
                prefs.edit().putString("report",report.toString()).putBoolean("complete",true).commit();
                if(niceBurst!=null)lastJobReport=report.toString();
            }
            if(niceBurst!=null)NiceDiagnostics.nativeFiles(dir,report.toString());
            deleteTree(dir);
        }
    }

    /** P27: the report of the last merge job (LmcHybridBurst reads how the worker ended to pick its fallback tier). */
    public static volatile String lastJobReport;
    /** P27: text put at the top of the next merge job's report (the failed first attempt of a retry). */
    public static volatile String carryReport;

    /** Number after `key=` on the first report line containing `line` (worker report), or `fallback`. */
    static float reportNumber(CharSequence rep,String line,String key,float fallback){
        String report=rep.toString();
        int at=report.indexOf(line); if(at<0)return fallback;
        int end=report.indexOf('\n',at); String l=end<0?report.substring(at):report.substring(at,end);
        int k=l.indexOf(key); if(k<0)return fallback;
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("-?[0-9]+(\\.[0-9]+)?").matcher(l.substring(k+key.length()));
        try{ return m.find()?Float.parseFloat(m.group()):fallback; }catch(NumberFormatException e){ return fallback; }
    }
}

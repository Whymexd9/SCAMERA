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
import com.particlesdevs.photoncamera.util.Lang;

/** Bounded one-job worker process (app sandbox, or su when enabled); vendor failures cannot crash the camera PID. */
public final class ScamNeuralClient {
    private ScamNeuralClient() {}
    /** The worker did not finish in time. ScamHybridBurst skips its conservative retry on this type (the text is localized). */
    public static final class WorkerTimeoutException extends IOException {
        WorkerTimeoutException(String message) { super(message); }
    }
    private static String quote(String s) { return "'"+s.replace("'","'\\''")+"'"; }
    /** The SHDR merge job (P175: the only job; the neural remosaic's captures and checks are gone). */
    static synchronized ByteBuffer processScamBurst(Context context,ScamTransport burst) throws Exception {
        return job(context,burst,line->Log.i("SCAM_HDR",line));
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
                Log.w("SCAM_HDR", "memfd unavailable, using files: " + e);
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
        /** P48: dst.remaining() bytes from {@code position} into dst (its position ends at its limit), pread in parallel parts. */
        void readFully(ByteBuffer dst, long position) throws IOException {
            final java.io.FileDescriptor raw = fd.getFileDescriptor();
            readParallel((d, at) -> {
                try { return android.system.Os.pread(raw, d, at); }
                catch (android.system.ErrnoException e) { throw new IOException(e); }
            }, dst, position, READ_PART);
        }
        /** P179: the memfd cut to {@code size} bytes (the pages behind it go back). */
        void truncate(long size) throws IOException {
            try { android.system.Os.ftruncate(fd.getFileDescriptor(), size); }
            catch (android.system.ErrnoException e) { throw new IOException(e); }
        }
        @Override public void close() { try { fd.close(); } catch (IOException ignored) {} }
        static SharedMemory wrap(android.os.ParcelFileDescriptor fd) { return new SharedMemory(fd); }
    }
    /** P179: the slice of a shrinking read (a large result read from its end, the memfd cut behind each slice). */
    static final int SHRINK_SLICE = 256 << 20;

    /**
     * The trailers after the RGB ({@code expected} bytes), each optional, in this order: the merged Bayer RAW (sensor grid,
     * {@code rawTrailer} bytes, if requested), the effective-frame map (OUTPUT grid, {@code outTrailer}), the clip flags (OUTPUT
     * grid; SCAM Hybrid, only if requested and only after the map - P187: or with no map asked for, header flag 16). The fullest
     * layout that matches the output's size wins: {dng, eff, clip} bytes, or null when none does.
     */
    static long[] trailers(long outputBytes,long expected,long rawTrailer,long outTrailer,boolean dng,boolean map,boolean clip){
        for(long d:dng?new long[]{rawTrailer,0}:new long[]{0})
            for(long e:map?new long[]{outTrailer,0}:new long[]{0})
                for(long c:clip&&(e>0||!map)?new long[]{outTrailer,0}:new long[]{0})
                    if(outputBytes==expected+d+e+c)return new long[]{d,e,c};
        return null;
    }
    interface Truncatable { void truncate(long size) throws IOException; }
    /**
     * P179: dst.remaining() bytes from {@code position} into dst read from the end in slices of {@code slice} bytes, the source cut
     * to each slice's start once it is read (so the source and dst are never both whole); dst's position ends at its limit. The
     * same bytes as {@link #readParallel}.
     */
    static void readShrinking(PositionalReader reader, Truncatable source, ByteBuffer dst, long position, int slice) throws IOException {
        final int start = dst.position(), total = dst.remaining();
        for (int end = total; end > 0; ) {
            final int a = Math.max(0, end - slice);
            final ByteBuffer d = dst.duplicate();
            d.limit(start + end);
            d.position(start + a);
            readParallel(reader, d, position + a, READ_PART);
            source.truncate(position + a);
            end = a;
        }
        dst.position(start + total);
    }
    private static void readShrinking(SharedMemory m, ByteBuffer dst, long position, int slice) throws IOException {
        final java.io.FileDescriptor raw = m.fd.getFileDescriptor();
        readShrinking((d, at) -> {
            try { return android.system.Os.pread(raw, d, at); }
            catch (android.system.ErrnoException e) { throw new IOException(e); }
        }, m::truncate, dst, position, slice);
    }
    /** A positional read (pread): bytes from {@code position} into dst from its position, the count read (0 / -1 = end). */
    interface PositionalReader { int read(ByteBuffer dst, long position) throws IOException; }
    static final int READ_PART = 16 << 20;
    /**
     * P48 (research/speed/PLAIN_SHOT_SPEED.md): dst.remaining() bytes from {@code position} into dst in parts of {@code part}
     * bytes on {@link com.particlesdevs.photoncamera.util.ParallelWork}, each part by positional reads of its own; dst's position
     * ends at its limit. The same bytes as one sequential read (the worker's 600 MB result of the 2x grid: 422 ms on one core on
     * the vivo X200 Ultra). A short file is an EOFException, as before.
     */
    static void readParallel(PositionalReader reader, ByteBuffer dst, long position, int part) throws IOException {
        final int start = dst.position(), total = dst.remaining();
        final int parts = (int) ((total + (long) part - 1) / part);
        try {
            com.particlesdevs.photoncamera.util.ParallelWork.forEach(parts, k -> {
                final ByteBuffer d = dst.duplicate();
                final int a = start + k * part, e = (int) Math.min((long) start + total, (long) a + part);
                d.limit(e);
                d.position(a);
                long at = position + (a - start);
                try {
                    while (d.hasRemaining()) {
                        final int n = reader.read(d, at);
                        if (n <= 0) throw new EOFException(Lang.t("Неполный результат", "Incomplete result"));
                        at += n;
                    }
                } catch (IOException io) {
                    throw new UncheckedIOException(io);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        dst.position(start + total);
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
            if(!dir.mkdirs()&&!dir.isDirectory())throw new IOException(Lang.t("Не удалось создать кэш ресурсов","Could not create the asset cache"));
        }
        return dir;
    }
    private static void deleteTree(File file) {
        File[] children=file.isDirectory()?file.listFiles():null;
        if(children!=null)for(File child:children)deleteTree(child);
        file.delete();
    }
    /** W1.11: the finished job's folder (unique per job) is deleted off the shot's path. */
    private static final java.util.concurrent.ExecutorService CLEANUP=java.util.concurrent.Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"SCAMERA-job-cleanup");t.setDaemon(true);return t;
    });
    private static void deleteTreeLater(File dir){
        try{CLEANUP.execute(()->deleteTree(dir));}catch(RuntimeException e){deleteTree(dir);}
    }
    /**
     * W1.5: the APK opened once per process (its central directory was read again for every shot); a new install starts a new
     * process. Only the job thread uses it (job runs inside the class's synchronized entry points).
     */
    private static ZipFile apkFile;
    private static String apkPath;
    private static synchronized ZipFile apk(Context context) throws IOException {
        final String path=context.getApplicationInfo().sourceDir;
        if(apkFile==null||!path.equals(apkPath)){
            if(apkFile!=null)try{apkFile.close();}catch(IOException ignored){}
            apkFile=new ZipFile(path);apkPath=path;
        }
        return apkFile;
    }
    // P35 / P33: GPU programs of a module's merge route built in the worker's program cache when the module opens (one worker
    // run per colour block and tuning per process; off the shutter path, on a low-priority thread).
    private static final java.util.Set<String> prewarmed=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.ExecutorService PREWARM=java.util.concurrent.Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"hybrid-gpu-prewarm");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;});
    /** P35: build the Hybrid merge programs of colour block {@code block} (1 = plain Bayer, 2 Quad, 4 Tetra) ahead of the shot. */
    public static void prewarmHybridGpu(Context context,int block){
        if(context==null)return;
        final int b=block==2||block==4?block:1;
        final Context app=context.getApplicationContext();
        // Called from the camera session setup: everything else (worker check, tuning text, scam_dev.txt) runs on the prewarm
        // thread, and nothing it does can fail the session.
        try{PREWARM.execute(()->prewarmRun(app,b));}catch(RuntimeException e){Log.w("SCAM_HDR","HYBRID PREWARM: "+e);}
    }
    private static void prewarmRun(Context app,int b){
        final String tuning;
        try{
            if(!com.particlesdevs.photoncamera.util.WorkerSpawn.available(app))return;
            tuning=com.particlesdevs.photoncamera.settings.PreferenceKeys.hybridTuningText();
        }catch(RuntimeException e){Log.w("SCAM_HDR","HYBRID PREWARM: "+e);return;}
        if(!prewarmed.add(b+"|"+tuning.hashCode()))return;
        final long started=android.os.SystemClock.elapsedRealtime();
        File dir=new File(app.getCacheDir(),"scam-neural-prewarm-"+UUID.randomUUID());
        com.particlesdevs.photoncamera.util.WorkerSpawn.Child child=null;
        try{
            if(!dir.mkdir())throw new IOException("job folder");
            File glCache=new File(app.getCacheDir(),"hybrid-gl");
            if(!glCache.isDirectory()&&!glCache.mkdirs())throw new IOException("program cache folder");
            try(java.io.FileWriter cw=new java.io.FileWriter(new File(dir,"gl-cache"))){cw.write(glCache.getAbsolutePath());}
            if(!tuning.isEmpty())try(java.io.FileWriter tw=new java.io.FileWriter(new File(dir,"hybrid_tuning.txt"))){tw.write(tuning);}
            java.util.ArrayList<String> args=new java.util.ArrayList<>();
            java.util.Collections.addAll(args,"--gpu-prewarm",dir.getAbsolutePath(),String.valueOf(b));
            child=com.particlesdevs.photoncamera.util.WorkerSpawn.start(app,args,com.particlesdevs.photoncamera.util.WorkerSpawn.environment(app,dir));
            String result="no report";
            try(BufferedReader lines=new BufferedReader(new InputStreamReader(child.output))){
                String line;while((line=lines.readLine())!=null)if(line.startsWith("HYBRID PREWARM"))result=line;
            }
            if(!child.waitFor(60000)){child.destroy();result="timed out";}
            Log.i("SCAM_HDR",result+" (worker "+(android.os.SystemClock.elapsedRealtime()-started)+" ms)");
        }catch(Exception e){
            if(child!=null)child.destroy();
            prewarmed.remove(b+"|"+tuning.hashCode());
            Log.w("SCAM_HDR","HYBRID PREWARM: "+e);
        }finally{deleteTree(dir);}
    }
    /** W1.0: the Java preparation of a hybrid shot in one line, from the marks of its ShotTimeline (ms; -1 = not measured). */
    private static String javaPrepLine(){
        final long sharp=com.particlesdevs.photoncamera.processing.ShotTimeline.between("sharp_start","sharp_done");
        final long mosaic=com.particlesdevs.photoncamera.processing.ShotTimeline.between("mosaic_start","mosaic_done");
        final long beforeCall=com.particlesdevs.photoncamera.processing.ShotTimeline.between("proc","hybrid_call");
        final long ctorAll=com.particlesdevs.photoncamera.processing.ShotTimeline.between("hybrid_call","ctor_done");
        final long header=com.particlesdevs.photoncamera.processing.ShotTimeline.between("ctor_done","spawn");
        final long total=com.particlesdevs.photoncamera.processing.ShotTimeline.between("proc","spawn");
        return "HYBRID JAVA PREP ms: sharp="+sharp+" meta="+(beforeCall>=0&&sharp>=0?beforeCall-sharp:-1)+" mosaic="+mosaic
                +" ctor="+(ctorAll>=0&&mosaic>=0?ctorAll-mosaic:-1)+" header="+header+" total="+total;
    }
    private static File cachedAsset(Context context,ZipFile apk,java.util.zip.ZipEntry entry,String prefix,String name) throws IOException {
        File dir=new File(assetCacheDir(context),prefix.replace('/','_'));
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException(Lang.t("Не удалось создать кэш ресурсов","Could not create the asset cache"));
        File file=new File(dir,name);
        if(file.isFile()&&file.length()==entry.getSize())return file;
        File tmp=new File(dir,name+".tmp");
        tmp.delete();
        try(InputStream in=apk.getInputStream(entry);FileOutputStream out=new FileOutputStream(tmp)){
            byte[] buf=new byte[65536];int n;long total=0;
            while((n=in.read(buf))!=-1){total+=n;if(total>128L*1024*1024)throw new IOException(Lang.t("Слишком большой ресурс","Asset too large"));out.write(buf,0,n);}
        }
        if(!tmp.setReadOnly())throw new IOException(Lang.t("Не удалось защитить ","Could not protect ")+name);
        if(name.equals("scam-neural-worker")&&!tmp.setExecutable(true,true))throw new IOException(Lang.t("Не удалось разрешить запуск нейромодуля","Could not make the neural module executable"));
        file.delete();
        if(!tmp.renameTo(file))throw new IOException(Lang.t("Не удалось сохранить ","Could not save ")+name);
        return file;
    }
    private static ByteBuffer job(Context context,ScamTransport scamBurst,Consumer<String> observer) throws Exception {
        // P206: the merge worker runs only in the original (SCAMERA-signed) APK
        if(!com.particlesdevs.photoncamera.util.OriginalBuild.check(context))
            throw new IOException(Lang.t("Изменённая копия SCAMERA: обработка недоступна","Modified copy of SCAMERA: processing unavailable"));
        final int w=scamBurst.width(),h=scamBurst.height();
        // P371 (speed, worker_prespawn): the worker spawned at the shutter for this burst's arena (its start, CRE and GPU
        // warm-up ran during the capture), else null: spawned below as before
        final java.util.List<String> standbyNotes=new java.util.ArrayList<>(); // (review M2a: a standby that died meanwhile)
        final Standby adopted=Standby.take(context,scamBurst,carryReport,standbyNotes);
        File dir=adopted!=null?adopted.dir:new File(context.getCacheDir(),"scam-neural-job-"+UUID.randomUUID());
        if(adopted==null&&!dir.mkdir())throw new IOException(Lang.t("Не удалось создать папку задания","Could not create the job folder"));
        com.particlesdevs.photoncamera.util.WorkerSpawn.Child process=adopted!=null?adopted.child:null;
        boolean handedOff=false; // P371 (ok_read): the result taken at the completion line, the worker still exiting
        SharedMemory scamIn=null,scamOut=null;
        // Without root: the worker and its QNN/CRE runtime are installed as native
        // libraries and run as the app's own child process (sandboxed, same NPU/GPU).
        // su remains the optional fallback for builds without them.
        final boolean direct=com.particlesdevs.photoncamera.util.WorkerSpawn.available(context);
        final long startMs=android.os.SystemClock.elapsedRealtime();
        com.particlesdevs.photoncamera.processing.ShotTimeline.mark("job");
        SharedPreferences prefs=context.getSharedPreferences("scam_capture_report",Context.MODE_PRIVATE);
        StringBuilder report=new StringBuilder("SCAMERA: neural inference job\n");
        // P27: a retry after a failed merge keeps the first attempt in its report.
        final String carried=carryReport;
        if(carried!=null)report.append("PREVIOUS ATTEMPT:\n").append(carried).append("\nRETRY:\n");
        for(String note:standbyNotes)report.append(note).append('\n');
        // W1.5: apply (the in-memory report is current at once; the disk write leaves the shot's path).
        prefs.edit().putString("report",report.toString()).putBoolean("complete",false).apply();
        final long[] lastReportWriteMs={startMs};
        // P212d: the ultrashort frames' row profiles (the worker's P197 row check) for the flicker vote, whatever the report's length
        final java.util.List<String> rowProfiles=new java.util.ArrayList<>();
        // P212h (A5 = M5): the frames the worker's effective-map code 64 stands for (its median coverage), whatever the report's length
        final float[] effMedian={Float.NaN};
        // P371: this job's number - a worker reaped after its result (ok_read) writes the stored report only while no newer job ran
        final int generation=JOB_GENERATION.incrementAndGet();
        Consumer<String> log=line->{
            String commit=null;
            synchronized(report){
                if(report.length()<128000)report.append(line).append('\n');
                if(line.indexOf(" row profile ")>0&&line.indexOf(" bands G ")>0&&rowProfiles.size()<16)rowProfiles.add(line);
                if(line.indexOf(EFFECTIVE_MAP_LINE)>=0)effMedian[0]=reportNumber(line,EFFECTIVE_MAP_LINE,"median frames=",Float.NaN);
                long now=android.os.SystemClock.elapsedRealtime();
                // Keep every line in memory; batch disk commits so the reader
                // cannot stall the native stdout pipe on frequent progress logs.
                // P371: the fsync'ed commit outside the lock (the processing thread takes it for its own lines and the report's
                // numbers), none for the completion line (the shot reads its result there; the finally block stores the report)
                if((now-lastReportWriteMs[0]>=500||line.startsWith("STOP:")||line.startsWith("CLIENT STOP:"))&&!line.equals("SCAM CAPTURE OK")){
                    commit=report.toString();lastReportWriteMs[0]=now;
                }
            }
            if(commit!=null&&generation==JOB_GENERATION.get())prefs.edit().putString("report",commit).commit();
            com.particlesdevs.photoncamera.processing.ShotTimeline.workerLine(line);
            observer.accept(line);
        };
        try {
            // Extract only the assets in this APK. Missing bundles fail before
            // requesting root; no fallback to Vivo firmware model files.
            if(!direct && !com.particlesdevs.photoncamera.settings.PreferenceKeys.isRootEnabled())
                throw new IOException(Lang.t("Обработчик не установлен в этой сборке; включите «Root-доступ» в «Настройки → Система»","The processor is not installed in this build; turn on “Root access” in Settings → System"));
            if(adopted==null){ // (P371: a worker spawned at the shutter has its folder and its markers before the go)
                linkRuntime(context,dir);
                // The su launcher runs the worker executable from the APK's assets (the app process spawns the installed one).
                if(!direct){
                    final ZipFile apk=apk(context);
                    final String name="scam-neural-worker",prefix="assets/scam-neural/arm64-v8a/";
                    java.util.zip.ZipEntry entry=apk.getEntry(prefix+name);
                    if(entry==null)throw new IOException(Lang.t("Неполный APK: отсутствует ","Incomplete APK: missing ")+name+Lang.t(". Установите сборку Bundled.",". Install the Bundled build."));
                    // Extracted once per installed APK (was every shot, ~0.2 s); the job
                    // directory only links to it. The root worker still verifies hashes.
                    File cached=cachedAsset(context,apk,entry,prefix,name);
                    try{android.system.Os.symlink(cached.getAbsolutePath(),new File(dir,name).getAbsolutePath());}
                    catch(android.system.ErrnoException e){throw new IOException(Lang.t("Не удалось подготовить ","Could not prepare ")+name+": "+e.getMessage());}
                }
            }
            // "Bundled" CRE source: the worker skips /vendor and loads the APK copy with
            // compat stubs, the path non-scam devices take automatically.
            // "Vendor" never falls back to the APK copy.
            if(adopted==null){
                String marker=creSourceMarker();
                if(marker!=null && !new File(dir,marker).createNewFile())throw new IOException(Lang.t("Не удалось создать маркер CRE","Could not create the CRE marker"));
            }
            // Only the hybrid burst asks for the hybrid merge: a ScamBurst reaches the worker when the engine is
            // the network or the burst is a Quad/Tetra mosaic (ScamProcessor), and the hybrid would read the mosaic
            // as plain Bayer (vivo tele in ISZ: blue image, 3 October). Without the model the worker falls back itself.
            // P277: also a lab burst replayed from its file (ScamLabBurst)
            final boolean hybridMerge=scamBurst.hybrid();
            // P80: the hybrid RGB comes back as half floats (marker rgb-half): half the bytes written, read and uploaded
            final boolean rgbHalf=hybridMerge&&scamBurst.rgbHalfTransport();
            if(rgbHalf&&!new File(dir,"rgb-half").createNewFile())throw new IOException(Lang.t("Не удалось создать маркер half","Could not create the half marker"));
            if(hybridMerge){
                // SCAM Hybrid merge (scam-hybrid.h): no neural model, any GPU. The worker reads the marker
                // and the tuning lines written from the SCAM HDR settings.
                if(!new File(dir,"hybrid-merge").createNewFile())throw new IOException(Lang.t("Не удалось создать маркер склейки SHDR","Could not create the SHDR merge marker"));
                if(scamBurst.stages()&&!new File(dir,"stage-dump").createNewFile())log.accept("CLIENT: stage-dump marker not written");
                // P188: scam_dev "keep_burst 1" - the worker keeps the shot's RAW burst for an offline replay. P191: one file per shot
                // in Download/SCAMERA (scam_burst-<time>.bin, next to the debug log), not the package's external files: a file
                // manager on Android 16 no longer opens Android/data (owner, 2026-10-17)
                // P191 (owner, 2026-10-17: «Добавь настройку с сохранением рав серии в группу настроек "Диагностика"»): the row
                // «Сохранять RAW-серию» of Diagnostics; scam_dev.txt "keep_burst" still overrides it
                // P277: a lab shot keeps its burst in its own Lab folder (the worker keeps one copy: the lab's wins)
                final File labBurst=scamBurst.keepable()?ScamLab.burstTarget():null;
                if(labBurst!=null){
                    try(java.io.FileWriter kb=new java.io.FileWriter(new File(dir,"keep-burst"))){
                        kb.write(labBurst.getAbsolutePath());
                    }catch(IOException kbe){log.accept("CLIENT: lab keep-burst marker not written: "+kbe.getMessage());}
                }else if(scamBurst.keepable()&&com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevSwitch("keep_burst",
                        com.particlesdevs.photoncamera.settings.PreferenceKeys.isKeepRawBurst())){
                    File kept=com.particlesdevs.photoncamera.util.ScameraFiles.dir();
                    if(!kept.isDirectory())log.accept("CLIENT: keep-burst folder not made: "+kept);
                    String name="scam_burst-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.ROOT).format(new java.util.Date())+".bin";
                    try(java.io.FileWriter kb=new java.io.FileWriter(new File(dir,"keep-burst"))){
                        kb.write(new File(kept,name).getAbsolutePath());
                        ScamLab.keeping(new File(kept,name)); // P359: the lab's metadata beside it
                    }catch(IOException kbe){log.accept("CLIENT: keep-burst marker not written: "+kbe.getMessage());}
                }
                // A worker of this process died inside the CRE: this one aligns with SCAMERA's own tile alignment.
                if(adopted==null&&scamBurst.creOff()&&!new File(dir,"cre-off").createNewFile()) // (P371: a standby has its own)
                    throw new IOException(Lang.t("Не удалось создать маркер CRE","Could not create the CRE marker"));
                // P30: the worker keeps its compiled GPU programs in the app's cache (−0.6 s per shot after the first).
                if(adopted==null)writeGlCache(context,dir);
                final String settingsTuning=scamBurst.settingsTuning(),burstTuning=scamBurst.burstTuning();
                if(labBurst!=null)ScamLab.recordTuning(settingsTuning,burstTuning);
                String tuning=settingsTuning+burstTuning;
                if(!tuning.isEmpty())try(java.io.FileWriter tw=new java.io.FileWriter(new File(dir,"hybrid_tuning.txt"))){tw.write(tuning);}
                log.accept("CLIENT: SCAM Hybrid merge requested"+(tuning.isEmpty()?"":" tuning="+tuning.replace('\n',' ')));
            }
            if(adopted==null)writeDumpForward(context,dir);
            final long assetsDone=android.os.SystemClock.elapsedRealtime();
            File input=new File(dir,"input.f32"),output=new File(dir,"output.f32");
            // SCAM: burst and result through shared memory (memfd) instead of ~0.6 GB
            // written to and read back from flash per shot; the root worker opens
            // them as /proc/<pid>/fd/<n>. Files remain the fallback.
            // P30: with the app-process worker and shared memory the worker starts first and the burst is written while it
            // starts (spawn, CRE and GPU driver), then a byte on a pipe (job file "wait-go") lets it map the burst.
            boolean deferredWrite=false;
            boolean standbyGone=false; // P371 (review M2): the standby died before its go byte: a worker spawned below instead
            // P30: the frames already lie in the shot's arena (memfd): only the header is written, the worker maps it.
            // P371: a standby's arena is the one its take() checked (the header written there); its worker holds it as fd 3
            final android.os.ParcelFileDescriptor arena=adopted!=null?adopted.arena:android.os.Build.VERSION.SDK_INT>=30?scamBurst.sharedBurst():null;
            if(adopted!=null){
                scamIn=SharedMemory.wrap(arena);
                scamOut=adopted.out;
                log.accept("CLIENT: burst in the shot arena (no copy)");
            } else if(arena!=null){
                scamIn=SharedMemory.wrap(arena);
                scamOut=SharedMemory.create("scamera-scam-out");
                if(scamOut==null){scamIn.close();scamIn=null;}
                else log.accept("CLIENT: burst in the shot arena (no copy)");
            }
            if(scamIn==null){
                scamIn=SharedMemory.create("scamera-scam-in");
                scamOut=scamIn==null?null:SharedMemory.create("scamera-scam-out");
                if(scamOut!=null){
                    deferredWrite=direct;
                    if(!deferredWrite)try(FileChannel channel=scamIn.writeChannel()){scamBurst.write(channel);}
                } else {
                    if(scamIn!=null){scamIn.close();scamIn=null;}
                    scamBurst.write(input);
                }
            }
            android.os.ParcelFileDescriptor[] goPipe=null;
            if(deferredWrite){
                goPipe=android.os.ParcelFileDescriptor.createPipe();
                try(java.io.FileWriter marker=new java.io.FileWriter(new File(dir,"wait-go"))){marker.write("5");}
            }
            if(scamOut==null){
                // Create as app UID before root truncates/writes it: no chmod,
                // chown, shared-storage input, or globally readable temp files.
                if(!output.createNewFile())throw new IOException(Lang.t("Не удалось создать файл результата","Could not create the result file"));
            }
            final long inputDone=android.os.SystemClock.elapsedRealtime();
            log.accept("HEX CLIENT PREP ms: assets="+(assetsDone-startMs)+" raw_write="+(inputDone-assetsDone));
            if(hybridMerge){
                com.particlesdevs.photoncamera.processing.ShotTimeline.mark("spawn");
                log.accept(javaPrepLine());
            }
            if(adopted!=null){
                // P371: the standby's go byte (every file it reads after it is written above, the header in the arena)
                com.particlesdevs.photoncamera.processing.ShotTimeline.markAt("prespawn",adopted.spawnedNs);
                log.accept(Lang.t("WORKER: отдельный процесс приложения (без root)","WORKER: separate app process (no root)"));
                log.accept("CLIENT: worker started at the shutter "+(android.os.SystemClock.elapsedRealtimeNanos()-adopted.spawnedNs)/1_000_000
                        +" ms before the go (scam_dev worker_prespawn)");
                com.particlesdevs.photoncamera.processing.ShotTimeline.note("w_gpu_priority",adopted.normalGpu?"normal":"medium");
                com.particlesdevs.photoncamera.processing.ShotTimeline.note("w_nice",adopted.nice==com.particlesdevs.photoncamera.util.WorkerEnv.KEEP_NICE?"inherited":String.valueOf(adopted.nice));
                // review M2 (a, b): a standby that died since take() (the go pipe's reader gone: EPIPE) gives its lines to this report
                // and a worker spawned now takes the job (the burst is in the arena, the output memfd still empty)
                String gone=null;
                try{if(adopted.child.waitFor(0))gone="exited "+adopted.child.exitValue();else adopted.go();}
                catch(IOException goFailed){gone=String.valueOf(goFailed.getMessage());}
                if(gone!=null){
                    adopted.closeGo();adopted.child.destroy();
                    try{adopted.reader.join(500);}catch(InterruptedException e){Thread.currentThread().interrupt();} // its last lines
                    for(String l:adopted.route.drop())log.accept("STANDBY: "+l);
                    log.accept("CLIENT: the worker started at the shutter is gone ("+gone+"): a worker started now");
                    standbyGone=true;process=null;
                    if(!new File(dir,"wait-go").delete())log.accept("CLIENT: wait-go marker left");
                }
            }
            if(adopted!=null&&!standbyGone){
                // (its go byte went above)
            } else if(direct){
                String dirPath=dir.getAbsolutePath();
                java.util.ArrayList<String> args=new java.util.ArrayList<>();
                android.os.ParcelFileDescriptor[] fds=new android.os.ParcelFileDescriptor[0];
                // memfd burst/result inherited as fd 3/4 (the sandbox cannot open /proc/<pid>/fd).
                if(scamOut!=null)fds=goPipe!=null?new android.os.ParcelFileDescriptor[]{scamIn.fd,scamOut.fd,goPipe[0]}
                        :new android.os.ParcelFileDescriptor[]{scamIn.fd,scamOut.fd};
                java.util.Collections.addAll(args,"--scam-capture",dirPath,scamOut!=null?"fd:3":input.getAbsolutePath(),
                        scamOut!=null?"fd:4":output.getAbsolutePath());
                log.accept(Lang.t("WORKER: отдельный процесс приложения (без root)","WORKER: separate app process (no root)"));
                // P242 (speed-ux B6): the merge's GPU priority of this job, for the A/B (with the thermal state, P238)
                final boolean normalGpu=com.particlesdevs.photoncamera.util.WorkerEnv.gpuNormalPriority();
                log.accept("WORKER GPU priority: "+(normalGpu?"normal (scam_dev worker_gpu_normal_priority 1)":"medium (EGL_IMG_context_priority, P364 default)"));
                com.particlesdevs.photoncamera.processing.ShotTimeline.note("w_gpu_priority",normalGpu?"normal":"medium");
                // P371: the worker's CPU priority (scam_dev worker_nice; the A/B against the processing thread's nice 10)
                final int workerNice=com.particlesdevs.photoncamera.util.WorkerEnv.workerNice();
                log.accept("WORKER nice: "+(workerNice==com.particlesdevs.photoncamera.util.WorkerEnv.KEEP_NICE?"inherited":String.valueOf(workerNice)));
                com.particlesdevs.photoncamera.processing.ShotTimeline.note("w_nice",workerNice==com.particlesdevs.photoncamera.util.WorkerEnv.KEEP_NICE?"inherited":String.valueOf(workerNice));
                process=com.particlesdevs.photoncamera.util.WorkerSpawn.start(context,args,
                        com.particlesdevs.photoncamera.util.WorkerSpawn.environment(context,dir),fds);
                if(goPipe!=null){
                    goPipe[0].close();
                    final long writeStarted=android.os.SystemClock.elapsedRealtime();
                    try(java.io.OutputStream go=new android.os.ParcelFileDescriptor.AutoCloseOutputStream(goPipe[1])){
                        try(FileChannel channel=scamIn.writeChannel()){scamBurst.write(channel);}
                        go.write(1); // closing the pipe without this byte stops the worker
                    }
                    log.accept("HEX CLIENT PREP ms: burst written while the worker started in "+(android.os.SystemClock.elapsedRealtime()-writeStarted));
                }
            } else {
            String command="export CLASSPATH="+quote(context.getApplicationInfo().sourceDir)+
                    "; export LD_LIBRARY_PATH="+quote("/system/lib64:/system_ext/lib64:"+dir.getAbsolutePath()+":/vendor/lib64")+
                    "; export ADSP_LIBRARY_PATH="+quote(dir.getAbsolutePath()+";/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/vendor/dsp;/system/lib/rfsa/adsp")+
                    "; exec /system/bin/app_process64 /system/bin "+ScamNeuralWorker.class.getName()+" "+quote(dir.getAbsolutePath());
            command+=" --scam-capture "+quote(scamIn!=null?scamIn.path():input.getAbsolutePath())
                    +" "+quote(scamOut!=null?scamOut.path():output.getAbsolutePath());
            log.accept(Lang.t("ROOT: запуск отдельного процесса; разрешите запрос root","ROOT: starting a separate process; allow the root request"));
            process=com.particlesdevs.photoncamera.util.WorkerSpawn.Child.of(new ProcessBuilder("su","-c",command).redirectErrorStream(true).start());
            }
            final com.particlesdevs.photoncamera.util.WorkerSpawn.Child child=process;
            final boolean[] completed={false};
            // P220: the worker judged by its heartbeat (HYBRID PROGRESS every 5 s) instead of the 14-minute alarm: no progress for
            // max(90 s, 4 x its slowest step) stops it (scam_dev worker_watchdog: 0 off, 1 log only, 2 on); the heartbeat also
            // moves the processing ring and the keep-alive notification (MergeProgress)
            final WorkerWatchdog watchdog=new WorkerWatchdog(Math.round(com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevNumber("worker_watchdog",
                    WorkerWatchdog.ACTIVE)),android.os.SystemClock::elapsedRealtime);
            if(watchdog.mode()!=WorkerWatchdog.ACTIVE)log.accept("CLIENT: worker watchdog "+(watchdog.mode()==WorkerWatchdog.OFF?"off":"log only")
                    +" (scam_dev worker_watchdog "+watchdog.mode()+")");
            if(hybridMerge)com.particlesdevs.photoncamera.processing.MergeProgress.begin(android.os.SystemClock.elapsedRealtime());
            // P371 (speed, ok_read): the result is read at the worker's completion line and its exit is reaped off the shot's path
            // (a merge up to 16 MP through the memfd; the larger ones and the file route wait for the exit as before).
            // scam_dev ok_read 0: as before.
            final WorkerCompletion completion=new WorkerCompletion();
            final boolean okRead=hybridMerge&&scamOut!=null&&(long)scamBurst.width()*scamBurst.height()<=ScamHybridBurst.CLASSIC_INPUT_PIXELS
                    &&com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevSwitch("ok_read",true);
            final Consumer<String> onLine=line->{
                if(line.equals("SCAM CAPTURE OK"))completed[0]=true;
                final WorkerWatchdog.Beat beat=watchdog.onLine(line);
                if(beat!=null&&hybridMerge)com.particlesdevs.photoncamera.processing.MergeProgress.update(beat.pass,beat.share());
                log.accept(line);
                completion.line(line);
            };
            final Thread reader;
            if(adopted!=null&&!standbyGone){ // P371: its lines since the shutter, then every later one
                reader=adopted.reader;
                adopted.route.attach(onLine,completion::outputEnded);
            } else {
            reader=new Thread(()->{
                try(BufferedReader lines=new BufferedReader(new InputStreamReader(child.output))){String line;while((line=lines.readLine())!=null)onLine.accept(line);}
                catch(IOException e){log.accept("READ: "+e);}
                finally{completion.outputEnded();}
            },"scam-neural-output");
            reader.setDaemon(true);reader.start();
            }
            // P27 any resolution: a Hybrid merge above 16 MP gets a wait in proportion to its pixels (900 s up to 16 MP, unchanged).
            final long timeoutS=hybridMerge?ScamHybridBurst.workerTimeoutSeconds(scamBurst.width(),scamBurst.height()):900;
            if(timeoutS>900)log.accept("CLIENT: worker timeout "+timeoutS+" s for "+scamBurst.width()+"x"+scamBurst.height());
            final WorkerWatchdog.Worker watched=completion.watched(new WorkerWatchdog.Worker(){
                @Override public boolean waitFor(long ms) throws InterruptedException{return child.waitFor(ms);}
                @Override public void destroy(){child.destroy();}
            },okRead);
            if(!watchdog.await(watched,TimeUnit.SECONDS.toMillis(timeoutS),log)){process.destroy();throw new WorkerTimeoutException(Lang.t("Тайм-аут нейромодуля; снимок не обработан","Neural module timed out; the shot was not processed"));}
            if(okRead&&completion.ok()){
                handedOff=true; // P371: its exit is reaped in the finally block below
                log.accept("CLIENT: result taken at the completion line (the worker's exit is reaped off the shot's path)");
            }else{
            reader.join(5000);
            if(process.exitValue()!=0||!completed[0]){
                // P26: the X200 Pro (Mali) worker vanished mid-merge with nothing in the log; say how it ended.
                final int code=process.exitValue();
                final String[] signals={"","SIGHUP","SIGINT","SIGQUIT","SIGILL","SIGTRAP","SIGABRT","SIGBUS","SIGFPE","SIGKILL (low memory or killed)","SIGUSR1","SIGSEGV"};
                log.accept("WORKER EXIT: "+(code>128&&code-128<signals.length?"signal "+(code-128)+" "+signals[code-128]
                        :code>128?"signal "+(code-128):"code "+code)+(completed[0]?"":", no completion line"));
            }
            if(reader.isAlive()||process.exitValue()!=0||!completed[0])throw new IOException(
                    hybridMerge?Lang.t("SHDR: склейка не завершена. Отчёт: Система → Диагностика → «Отчёт последней съёмки SHDR».","SHDR: merge not finished. Report: System → Diagnostics → “Last SHDR capture report”."):
                    Lang.t("SCAM HDR не завершён. Откройте SCAM HDR — проверка запуска → Отчёт последней съёмки SCAM HDR.","SCAM HDR not finished. Open “SCAM HDR: launch check” → “Last SCAM HDR capture report”."));
            }
            final int ow=scamBurst.outputWidth(),oh=scamBurst.outputHeight();
            long expected=(long)ow*oh*(rgbHalf?6:12);
            if(expected<=0||expected>Integer.MAX_VALUE)throw new IOException(Lang.t("Слишком большой нейрорезультат","Neural result too large"));
            final long outputBytes=scamOut!=null?scamOut.size():output.length();
            // The trailers after the RGB (merged RAW, effective-frame map, clip flags; see trailers)
            final boolean map=scamBurst.effectiveMap();
            if(!map)log.accept("CLIENT: no effective-frame map asked for ("+ow+"x"+oh+": no denoise reads it)");
            final long[] layout=trailers(outputBytes,expected,scamBurst.mergedRawPixels()*2,(long)ow*oh,scamBurst.mergedDng(),map,scamBurst.clipFlags());
            if(layout==null)throw new IOException(Lang.t("Неверный размер нейрорезультата","Wrong neural result size"));
            final long dngBytes=layout[0],effBytes=layout[1],clipBytes=layout[2];
            {ScamHybridBurst.lastMergedDng=null;ScamHybridBurst.lastEffectiveFrames=null;ScamRgb.dropClipFlags();
                // P371: the report as it stands at the completion line (the reader may still append the worker's last lines)
                final String rep;
                synchronized(report){ScamHybridBurst.lastEffectiveMedian=effMedian[0];rep=report.toString();}
                ScamHybridBurst.lastBentoApplied=rep.indexOf("HYBRID BENTO: applied")>=0;
                ScamHybridBurst.lastReport=rep; // MergeStamp
                ScamHybridBurst.lastBentoFactor=reportNumber(rep,"HYBRID BENTO: applied","factor=",1f);
                ScamHybridBurst.lastMergedDngHeadroom=Math.max(1f,reportNumber(rep,"HYBRID DNG:","headroom=",1f));
                ScamHybridBurst.lastMergeFrames=com.particlesdevs.photoncamera.processing.DngMetadata.mergeFrames(rep); // P214c
                ScamHybridBurst.lastBentoUsClipped=reportNumber(rep,"HYBRID BENTO: applied","usClippedRatio=",0f);
                final float noiseK=reportNumber(rep,"HYBRID NOISE CHECK:","-> model x",1f);
                ScamHybridBurst.lastNoiseFactor=noiseK>=1f/16&&noiseK<=4f?noiseK:1f;
                ScamHybridBurst.lastDcgNoise=dcgNoise(rep); // P320
                ScamHybridBurst.lastRgbHalf=rgbHalf;}
            final long readStart=android.os.SystemClock.elapsedRealtime();
            // P179 (200 MP: the app was killed for LOW_MEMORY): an output above 1 GiB is read from its end and the memfd shortened
            // behind each read, so the result and the memfd are never both whole at once (the same bytes; trailers first, then
            // the RGB in slices from its end).
            final boolean shrink=scamOut!=null&&outputBytes>(1L<<30);
            // P371 (speed, zero_copy_read): the RGB and the clip flags are views of the result memfd (an adopted arena: its pages
            // mapped in place) instead of a parallel pread into 85 MB of fresh native buffers (36-54 ms, their first-touch faults
            // included); the same bytes, freed by their consumers as before (Allocator.free). scam_dev zero_copy_read 0: read.
            int adoptedArena=0;
            if(!shrink&&scamOut!=null&&com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevSwitch("zero_copy_read",true)){
                try{adoptedArena=com.particlesdevs.photoncamera.util.Allocator.arenaAdopt(scamOut.fd.getFd(),outputBytes);}
                catch(RuntimeException|LinkageError e){adoptedArena=0;}
            }
            ByteBuffer result=null,clip=null;
            boolean viewed=false,clipKept=false,taken=false;
            // P371 (review B1): the adopted arena is released only once every trailer is read (its release used to punch the gap
            // between the RGB and the clip views - the merged DNG and the effective map - before their reads: zeros from ~21 MP
            // with RAW saving, on 2x grids); on any way out the views are freed with it
            try{
            result=adoptedArena>0?com.particlesdevs.photoncamera.util.Allocator.arenaView(adoptedArena,0,(int)expected):null;
            viewed=result!=null;
            if(!viewed)result=com.particlesdevs.photoncamera.util.Allocator.allocate((int)expected);
            if(result==null)throw new IOException(Lang.t("Недостаточно памяти для результата","Not enough memory for the result"));
            result.order(ByteOrder.nativeOrder());
            if(viewed)log.accept("CLIENT: result read in place (the memfd mapped, scam_dev zero_copy_read)");
            if(!shrink&&!viewed){
                try{
                    if(scamOut!=null)scamOut.readFully(result,0); // P48: parallel pread of the memfd
                    else try(FileChannel channel=new FileInputStream(output).getChannel()){
                        long position=0;
                        while(result.hasRemaining()){int n=channel.read(result,position);if(n<0)throw new EOFException(Lang.t("Неполный результат","Incomplete result"));position+=n;}
                    }
                }
                catch(Exception e){com.particlesdevs.photoncamera.util.Allocator.free(result);result=null;throw e;}
                result.flip();
            }
            // the trailers from the last one back (a shrinking read drops each from the memfd once it is read)
            // P187: in native memory, which ScamRgb frees right after the upload (the Java heap kept them until a GC)
            if(clipBytes>0&&viewed){ // P371: a view as well
                clip=com.particlesdevs.photoncamera.util.Allocator.arenaView(adoptedArena,expected+dngBytes+effBytes,(int)clipBytes);
                if(clip!=null){ScamRgb.keepNativeClipFlags(clip);clipKept=true;}
            }
            if(clipBytes>0&&!clipKept){clip=com.particlesdevs.photoncamera.util.Allocator.allocate((int)clipBytes);if(clip==null)log.accept("CLIENT: clip flags skipped: no memory for "+clipBytes+" B");}
            if(clipKept){}
            else if(clip!=null){
                try{
                    if(scamOut!=null)scamOut.readFully(clip,expected+dngBytes+effBytes);
                    else try(FileChannel channel=new FileInputStream(output).getChannel()){
                        long position=expected+dngBytes+effBytes;
                        while(clip.hasRemaining()){int n=channel.read(clip,position);if(n<0)throw new EOFException(Lang.t("Неполные флаги клипа","Incomplete clip flags"));position+=n;}
                    }
                    clip.flip();ScamRgb.keepNativeClipFlags(clip);
                }catch(Exception e){com.particlesdevs.photoncamera.util.Allocator.free(clip);log.accept("CLIENT: clip flags not read: "+e);}
            }else if(scamBurst.clipFlags())log.accept("CLIENT: clip flags asked for but not returned");
            if(shrink)scamOut.truncate(expected+dngBytes+effBytes);
            // P27 any resolution: the two optional trailers live on the Java heap (1 B per output pixel each); a failed allocation
            // (OutOfMemoryError, not an Exception) skips the trailer instead of the shot.
            ByteBuffer eff=null;
            if(effBytes>0)try{eff=ByteBuffer.allocateDirect((int)effBytes);}catch(OutOfMemoryError oom){log.accept("CLIENT: effective-frame map skipped: "+oom);}
            if(eff!=null){
                try{
                    if(scamOut!=null)scamOut.readFully(eff,expected+dngBytes);
                    else try(FileChannel channel=new FileInputStream(output).getChannel()){
                        long position=expected+dngBytes;
                        while(eff.hasRemaining()){int n=channel.read(eff,position);if(n<0)throw new EOFException(Lang.t("Неполная карта кадров","Incomplete frame map"));position+=n;}
                    }
                    eff.flip();ScamHybridBurst.lastEffectiveFrames=eff;
                }catch(Exception e){log.accept("CLIENT: effective-frame map not read: "+e);}
            }
            if(shrink)scamOut.truncate(expected+dngBytes);
            if(dngBytes>0){
                ByteBuffer dng=com.particlesdevs.photoncamera.util.Allocator.allocate((int)dngBytes);
                if(dng!=null){
                    dng.order(ByteOrder.nativeOrder());
                    try{
                        if(scamOut!=null)scamOut.readFully(dng,expected);
                        else try(FileChannel channel=new FileInputStream(output).getChannel()){
                            long position=expected;
                            while(dng.hasRemaining()){int n=channel.read(dng,position);if(n<0)throw new EOFException(Lang.t("Неполный RAW","Incomplete RAW"));position+=n;}
                        }
                        dng.flip();ScamHybridBurst.lastMergedDng=dng;
                    }catch(Exception e){com.particlesdevs.photoncamera.util.Allocator.free(dng);log.accept("CLIENT: merged DNG not read: "+e);}
                }
            }
            if(shrink){
                try{
                    scamOut.truncate(expected);
                    readShrinking(scamOut,result,0,SHRINK_SLICE);
                }
                catch(Exception e){com.particlesdevs.photoncamera.util.Allocator.free(result);result=null;throw e;}
                result.flip();
                log.accept("CLIENT: "+(outputBytes>>20)+" MB read from the end, the memfd shortened behind each read");
            }
            // the merged DNG and the effective map are copies now: their pages of the result go back (as the memfd did when the
            // job closed it; the views keep the RGB and the clip flags)
            if(adoptedArena>0&&dngBytes+effBytes>0)com.particlesdevs.photoncamera.util.Allocator.arenaPunch(adoptedArena,expected,dngBytes+effBytes);
            taken=true;
            }finally{
                if(adoptedArena>0){
                    if(!taken){ // the views go with the arena (the copy route frees its own buffers above)
                        if(viewed&&result!=null)com.particlesdevs.photoncamera.util.Allocator.free(result);
                        if(clipKept)ScamRgb.dropClipFlags(clip);
                    }
                    com.particlesdevs.photoncamera.util.Allocator.arenaRelease(adoptedArena); // the views keep it mapped
                }
            }
            ScamDiagnostics.buffer("02-after-ivst",result,ow,oh,3,true,rgbHalf);
            log.accept("HEX CLIENT OUTPUT ms="+(android.os.SystemClock.elapsedRealtime()-readStart));
            log.accept("HEX CLIENT TOTAL ms="+(android.os.SystemClock.elapsedRealtime()-startMs));
            com.particlesdevs.photoncamera.processing.ShotTimeline.mark("client_done");
            return result;
        } catch(Exception e){log.accept("CLIENT STOP: "+e.getMessage());throw e;}
        finally {
            com.particlesdevs.photoncamera.processing.MergeProgress.end(); // P220
            final boolean reap=handedOff&&process!=null; // P371: the worker past its completion line exits on its own (reaped below)
            if(adopted!=null)adopted.closeGo(); // P371: a standby that never got its go byte stops ("burst was not delivered")
            if(process!=null && process.isAlive() && !reap)process.destroy();
            if(scamIn!=null)scamIn.close();
            if(scamOut!=null)scamOut.close();
            // P371 (review M2b): the lines of a standby this job took but never attached (it threw first) go to its report
            if(adopted!=null&&!adopted.route.attached())for(String l:adopted.route.drop())log.accept("STANDBY: "+l);
            if(adopted!=null){ // P371 (review m3): a job that threw before its memfds took the standby's
                if(scamIn==null&&adopted.arena!=null)try{adopted.arena.close();}catch(IOException ignored){}
                if(scamOut!=adopted.out)adopted.out.close();
            }
            synchronized(report){
                // W1.11: apply and the folder deleted on the cleanup thread (both were on the shot's path, 25 ms).
                prefs.edit().putString("report",report.toString()).putBoolean("complete",true).apply();
                lastJobReport=report.toString();
                lastRowProfiles=java.util.Collections.unmodifiableList(new java.util.ArrayList<>(rowProfiles));
            }
            ScamDiagnostics.nativeFiles(dir,report.toString());
            if(scamBurst.stages()){ // lab stage view: read before the folder goes
                final File stages=new File(dir,"stages.bin");
                try{lastStages=stages.isFile()?ScamMergeStages.read(stages):null;}
                catch(IOException se){lastStages=null;log.accept("CLIENT: stage maps not read: "+se.getMessage());}
            }
            if(reap)reapLater(process,dir,report,prefs,generation);
            else deleteTreeLater(dir);
        }
    }

    /**
     * The QNN/CRE runtime (SCAM_FILES and HEX_FILES, all native libraries) linked into the job folder: it ships once, as native
     * libraries (the sandbox can map executable code only from nativeLibraryDir, and the su launcher reads the same files).
     */
    private static void linkRuntime(Context context,File dir) throws IOException {
        java.util.ArrayList<String> libraries=new java.util.ArrayList<>();
        for(String[] item:ScamNeuralWorker.SCAM_FILES)libraries.add(item[0]);
        for(String[] item:ScamNeuralWorker.HEX_FILES)libraries.add(item[0]);
        for(String name:libraries){
            File installed=com.particlesdevs.photoncamera.util.WorkerSpawn.library(context,name);
            if(installed==null)throw new IOException(Lang.t("Неполная установка: нет ","Incomplete install: missing ")+name);
            try{android.system.Os.symlink(installed.getAbsolutePath(),new File(dir,name).getAbsolutePath());}
            catch(android.system.ErrnoException e){throw new IOException(Lang.t("Не удалось подготовить ","Could not prepare ")+name+": "+e.getMessage());}
        }
    }

    /** The CRE source marker of the settings ("bundled": cre-force-bundled, "vendor": cre-vendor-only), null for the default. */
    private static String creSourceMarker(){
        String creSource=com.particlesdevs.photoncamera.settings.PreferenceKeys.getScamCreSource();
        return "bundled".equals(creSource)?"cre-force-bundled":"vendor".equals(creSource)?"cre-vendor-only":null;
    }

    /** P30: the worker keeps its compiled GPU programs in the app's cache (−0.6 s per shot after the first). */
    private static void writeGlCache(Context context,File dir) throws IOException {
        File glCache=new File(context.getCacheDir(),"hybrid-gl");
        if(glCache.isDirectory()||glCache.mkdirs())
            try(java.io.FileWriter cw=new java.io.FileWriter(new File(dir,"gl-cache"))){cw.write(glCache.getAbsolutePath());}
    }

    /**
     * Developer switch (adb: touch /sdcard/Download/SCAMERA/dump-forward): dump the network's input/output tiles. P191: the
     * marker and the dumps are in Download/SCAMERA, not Android/data (owner, 2026-10-17: «переноси всё туда чтобы в data ничего
     * не хранилось»)
     */
    private static void writeDumpForward(Context context,File dir) throws IOException {
        if(com.particlesdevs.photoncamera.util.ScameraFiles.exists(context,"dump-forward")){
            File dumps=com.particlesdevs.photoncamera.util.ScameraFiles.dir();
            try(java.io.FileWriter marker=new java.io.FileWriter(new File(dir,"dump-forward"))){marker.write(dumps.getAbsolutePath());}
        }
    }

    /**
     * P371 (speed, worker_prespawn): the files the worker reads before its go byte and the environment it starts with, as one
     * text: a worker spawned at the shutter is taken only when they are what the job would give it now.
     */
    private static String preGoKey(Context context,boolean creOff){
        return creSourceMarker()+"|"+creOff+"|"+com.particlesdevs.photoncamera.util.ScameraFiles.exists(context,"dump-forward")
                +"|"+com.particlesdevs.photoncamera.util.WorkerEnv.gpuNormalPriority()+"|"+com.particlesdevs.photoncamera.util.WorkerEnv.workerNice();
    }

    /** review M2: off by default until a phone measures it (it forks the camera app during the 0.4 s bracket capture). */
    private static boolean prespawnOn(){
        try{return com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevSwitch("worker_prespawn",false);}
        catch(RuntimeException|LinkageError e){return false;}
    }

    /** P371: the lines of a standby's reader before its job takes them (kept in order), then the job's (attach). */
    static final class LineRoute {
        private final java.util.ArrayList<String> early=new java.util.ArrayList<>();
        private Consumer<String> sink;private Runnable end;private boolean ended;
        static final int EARLY_LIMIT=4096;
        synchronized void line(String l){
            if(sink!=null){sink.accept(l);return;}
            if(early.size()<EARLY_LIMIT)early.add(l);
        }
        synchronized void ended(){ended=true;if(end!=null)end.run();}
        /** The lines so far into {@code s} in their order, every later one as it comes; {@code e} at the end of the output. */
        synchronized void attach(Consumer<String> s,Runnable e){
            for(String l:early)s.accept(l);
            early.clear();
            sink=s;end=e;
            if(ended)e.run();
        }
        synchronized boolean attached(){return sink!=null;}
        synchronized java.util.List<String> drop(){
            final java.util.List<String> lines=new java.util.ArrayList<>(early);early.clear();return lines;
        }
    }

    /**
     * P371 (speed, worker_prespawn; capture-prespawn-worker): a merge worker spawned when the shot's requests are submitted, with
     * the shot arena's memfd (the ZSL ring's) as fd 3, its output memfd as fd 4 and the wait-go pipe as fd 5 (P30). Its exec, CRE
     * load and GPU warm-up (eglInitialize, the alignment context and programs) run during the 0.4 s capture instead of after the
     * Java prep (X8U: spawn to the first homography ~0.2 s); everything per shot (tuning, rgb-half, keep-burst, the header) is
     * written after it and read by the worker only after the go byte - the same input, the same output. Taken by the next
     * job of the same arena and pre-go markers, else stopped (its go pipe closed: «burst was not delivered») and reaped.
     */
    static final class Standby {
        final File dir;final com.particlesdevs.photoncamera.util.WorkerSpawn.Child child;final SharedMemory out;
        final android.os.ParcelFileDescriptor goWrite;final long arenaIno,arenaDev,spawnedNs;final String preGo;
        final boolean normalGpu;final int nice;
        final LineRoute route=new LineRoute();final Thread reader;
        /** The burst's arena as job() reads it (take: the header written into it). */
        android.os.ParcelFileDescriptor arena;
        private boolean goDone;

        private Standby(File dir,com.particlesdevs.photoncamera.util.WorkerSpawn.Child child,SharedMemory out,android.os.ParcelFileDescriptor goWrite,
                        long arenaIno,long arenaDev,long spawnedNs,String preGo,boolean normalGpu,int nice){
            this.dir=dir;this.child=child;this.out=out;this.goWrite=goWrite;this.arenaIno=arenaIno;this.arenaDev=arenaDev;
            this.spawnedNs=spawnedNs;this.preGo=preGo;this.normalGpu=normalGpu;this.nice=nice;
            reader=new Thread(()->{
                try(BufferedReader lines=new BufferedReader(new InputStreamReader(child.output))){String line;while((line=lines.readLine())!=null)route.line(line);}
                catch(IOException e){route.line("READ: "+e);}
                finally{route.ended();}
            },"scam-neural-output");
            reader.setDaemon(true);
        }

        /** The go byte: the worker maps the burst and merges (every file it reads after it is in place). */
        synchronized void go() throws IOException {
            if(goDone)throw new IOException("standby: go twice");
            goDone=true;
            try(java.io.OutputStream go=new android.os.ParcelFileDescriptor.AutoCloseOutputStream(goWrite)){go.write(1);}
        }

        /** Closes the go pipe if no byte went (the worker then stops at once); idempotent. */
        synchronized void closeGo(){
            if(goDone)return;
            goDone=true;
            try{goWrite.close();}catch(IOException ignored){}
        }

        /** Not taken: stopped, its lines logged, reaped and its folder deleted off the shot's path. */
        void discard(String why){
            closeGo();
            if(arena!=null){try{arena.close();}catch(IOException ignored){}arena=null;}
            out.close();
            Log.i("SCAM_HDR","CLIENT: worker started at the shutter not used ("+why+")");
            final Runnable reap=()->{
                try{if(!child.waitFor(10000))child.destroy();}catch(InterruptedException e){child.destroy();}
                try{reader.join(2000);}catch(InterruptedException ignored){}
                for(String l:route.drop())Log.d("SCAM_HDR","standby: "+l);
                deleteTree(dir);
            };
            try{CLEANUP.execute(reap);}catch(RuntimeException e){reap.run();}
        }

        private static final Object LOCK=new Object();
        private static Standby current;private static boolean spawning;
        /** A standby waits at most this long for its job (a capture that failed or was cancelled). */
        static final long EXPIRE_MS=20000;
        /** job() waits at most this long for a standby still being spawned. */
        static final long SPAWNING_WAIT_MS=400;
        // review M1: at the processing thread's priority (Java 4: nice 10), so a worker that keeps the spawning thread's nice
        // (worker_nice out of range) runs as one spawned by the processing thread
        private static final java.util.concurrent.ScheduledExecutorService SPAWN=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{
            Thread t=new Thread(r,"SCAMERA-worker-prespawn");t.setDaemon(true);t.setPriority(4);return t;
        });
        /** review M2d: bumped by discardAll (the camera closed); a standby spawned for an older epoch is discarded once made. */
        private static long epoch;

        /** Spawns a standby for {@code arenaId} on its own thread (the camera thread only queues it); a previous one goes. */
        static void spawnLater(Context context,int arenaId){
            final long at;
            synchronized(LOCK){
                if(current!=null){final Standby old=current;current=null;old.discard("a newer shot");}
                spawning=true;at=epoch;
            }
            try{SPAWN.execute(()->spawn(context,arenaId,at));}
            catch(RuntimeException e){synchronized(LOCK){spawning=false;LOCK.notifyAll();}}
        }

        private static void spawn(Context context,int arenaId,long at){
            Standby made=null;
            File dir=null;SharedMemory out=null;android.os.ParcelFileDescriptor[] go=null;android.os.ParcelFileDescriptor arena=null;
            try{
                final int fd=com.particlesdevs.photoncamera.util.Allocator.arenaFd(arenaId);
                if(fd<0)throw new IOException("arena "+arenaId+" gone");
                arena=android.os.ParcelFileDescriptor.adoptFd(fd);
                final android.system.StructStat st=android.system.Os.fstat(arena.getFileDescriptor());
                dir=new File(context.getCacheDir(),"scam-neural-job-"+UUID.randomUUID());
                if(!dir.mkdir())throw new IOException("job folder");
                linkRuntime(context,dir);
                final String marker=creSourceMarker();
                if(marker!=null&&!new File(dir,marker).createNewFile())throw new IOException("CRE marker");
                final boolean creOff=ScamHybridBurst.avoidedSteps.contains(ScamHybridBurst.STEP_CRE);
                if(creOff&&!new File(dir,"cre-off").createNewFile())throw new IOException("CRE marker");
                writeGlCache(context,dir);
                writeDumpForward(context,dir);
                out=SharedMemory.create("scamera-scam-out");
                if(out==null)throw new IOException("no memfd");
                go=android.os.ParcelFileDescriptor.createPipe();
                try(java.io.FileWriter w=new java.io.FileWriter(new File(dir,"wait-go"))){w.write("5");}
                final java.util.ArrayList<String> args=new java.util.ArrayList<>();
                java.util.Collections.addAll(args,"--scam-capture",dir.getAbsolutePath(),"fd:3","fd:4");
                final String preGo=preGoKey(context,creOff);
                final boolean normalGpu=com.particlesdevs.photoncamera.util.WorkerEnv.gpuNormalPriority();
                final int nice=com.particlesdevs.photoncamera.util.WorkerEnv.workerNice();
                final long spawnedNs=android.os.SystemClock.elapsedRealtimeNanos();
                final com.particlesdevs.photoncamera.util.WorkerSpawn.Child child=com.particlesdevs.photoncamera.util.WorkerSpawn.start(context,args,
                        com.particlesdevs.photoncamera.util.WorkerSpawn.environment(context,dir),arena,out.fd,go[0]);
                go[0].close();
                arena.close();arena=null; // the worker holds its own fd 3
                made=new Standby(dir,child,out,go[1],st.st_ino,st.st_dev,spawnedNs,preGo,normalGpu,nice);
                made.reader.start();
                Log.i("SCAM_HDR","CLIENT: worker started at the shutter for arena "+arenaId+" in "
                        +(android.os.SystemClock.elapsedRealtimeNanos()-spawnedNs)/1_000_000+" ms");
            }catch(Exception e){
                Log.w("SCAM_HDR","CLIENT: no worker at the shutter: "+e);
                if(made==null){
                    if(go!=null){try{go[0].close();}catch(IOException ignored){}try{go[1].close();}catch(IOException ignored){}}
                    if(out!=null)out.close();
                    if(dir!=null)deleteTreeLater(dir);
                }
            }finally{
                if(arena!=null)try{arena.close();}catch(IOException ignored){}
            }
            final Standby ready=made;
            boolean stale=false;
            synchronized(LOCK){
                spawning=false;
                stale=ready!=null&&at!=epoch;
                if(ready!=null&&!stale)current=ready;
                LOCK.notifyAll();
            }
            if(stale){ready.discard("the camera closed while it started");return;}
            if(ready!=null)try{SPAWN.schedule(()->expire(ready),EXPIRE_MS,java.util.concurrent.TimeUnit.MILLISECONDS);}catch(RuntimeException ignored){}
        }

        /**
         * review M2d: the camera closes (pause, a session rebuilt by closeCamera): a standby waiting for its job, or one still
         * starting, goes (it held the ZSL ring's memfd as fd 3 for up to EXPIRE_MS); a job that comes later spawns its own worker.
         */
        static void discardAll(String why){
            final Standby s;
            synchronized(LOCK){epoch++;s=current;current=null;}
            if(s!=null)s.discard(why);
        }

        private static void expire(Standby s){
            synchronized(LOCK){if(current!=s)return;current=null;}
            s.discard("no job for "+EXPIRE_MS/1000+" s");
        }

        /**
         * The standby for this job, or null (none, or not this burst's: then stopped). Its arena must be the burst's (the header is
         * written into it here) and the pre-go markers what this job would give; a retry or a non-hybrid job never takes one.
         */
        static Standby take(Context context,ScamTransport burst,String carried,java.util.List<String> notes){
            Standby s;
            synchronized(LOCK){
                final long until=android.os.SystemClock.elapsedRealtime()+SPAWNING_WAIT_MS;
                while(spawning&&current==null){
                    final long left=until-android.os.SystemClock.elapsedRealtime();
                    if(left<=0)break;
                    try{LOCK.wait(left);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
                }
                s=current;current=null;
            }
            if(s==null)return null;
            String why=null;
            // review M2a: a standby that died at the shutter (its lines, its crash, for this job's report) is not taken
            boolean dead;
            try{dead=s.child.waitFor(0);}catch(InterruptedException e){Thread.currentThread().interrupt();dead=false;}
            if(dead){
                why="it exited ("+s.child.exitValue()+")";
                try{s.reader.join(500);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                notes.add("CLIENT: the worker started at the shutter "+why+"; its lines:");
                for(String l:s.route.drop())notes.add("STANDBY: "+l);
            }
            else if(carried!=null)why="a retry";
            else if(!burst.hybrid())why="not a hybrid merge";
            else if(!prespawnOn())why="worker_prespawn 0";
            else if(!s.preGo.equals(preGoKey(context,burst.creOff())))why="the markers changed";
            if(why==null){
                final android.os.ParcelFileDescriptor arena=burst.sharedBurst();
                if(arena==null)why="the burst is not in an arena";
                else{
                    try{
                        final android.system.StructStat st=android.system.Os.fstat(arena.getFileDescriptor());
                        if(st.st_ino!=s.arenaIno||st.st_dev!=s.arenaDev)why="another arena";
                    }catch(android.system.ErrnoException e){why="arena: "+e.getMessage();}
                    if(why!=null)try{arena.close();}catch(IOException ignored){}
                    else s.arena=arena;
                }
            }
            if(why!=null){s.discard(why);return null;}
            return s;
        }
    }

    /**
     * P371 (speed, worker_prespawn): the merge worker of a shot whose RAWs go into the arena {@code arenaId} is spawned now (the
     * shot's requests are submitted), on a thread of its own; job() takes it. Off by default (scam_dev worker_prespawn 1 turns it
     * on; review M2), without the app-process worker or memfd (SDK 30), outside the original build, with the GPU merge unusable
     * in this session: nothing.
     */
    public static void prespawn(Context context,int arenaId){
        if(context==null||arenaId<=0||android.os.Build.VERSION.SDK_INT<30||!prespawnOn())return;
        if(ScamHybridBurst.gpuMergeUnusable())return; // review M2c: the session merges on the CPU (cpuSingle): no worker comes
        try{
            final Context app=context.getApplicationContext();
            if(!com.particlesdevs.photoncamera.util.WorkerSpawn.available(app)||!com.particlesdevs.photoncamera.util.OriginalBuild.check(app))return;
            Standby.spawnLater(app,arenaId);
        }catch(RuntimeException|LinkageError e){Log.w("SCAM_HDR","CLIENT: no worker at the shutter: "+e);}
    }

    /** P371 (review M2d): the camera closes - a worker started at the shutter and not yet taken goes. */
    public static void discardStandby(String why){
        try{Standby.discardAll(why);}catch(RuntimeException|LinkageError e){Log.w("SCAM_HDR","CLIENT: standby not discarded: "+e);}
    }

    /** P371: jobs started in this process (a reaped worker writes the stored report only while its job is the latest). */
    private static final java.util.concurrent.atomic.AtomicInteger JOB_GENERATION=new java.util.concurrent.atomic.AtomicInteger();
    /**
     * P371: a worker past its completion line waits this long to exit before it is killed (its teardown takes ~0.1 s; review m4:
     * a worker hung in its teardown kept 1.4-2.8 GB for 30 s, and the result is complete anyway).
     */
    static final long REAP_TIMEOUT_MS=5000;

    /**
     * P371 (ok_read): the exit of a worker whose result was taken at its completion line, on the cleanup thread: an unclean exit
     * is logged (and added to the stored report while no newer job ran), then the job's folder goes.
     */
    private static void reapLater(final com.particlesdevs.photoncamera.util.WorkerSpawn.Child child,final File dir,final StringBuilder report,
                                  final SharedPreferences prefs,final int generation){
        final Runnable reap=()->{
            final String line=WorkerCompletion.reap(new WorkerCompletion.Exit(){
                @Override public boolean waitFor(long ms) throws InterruptedException{return child.waitFor(ms);}
                @Override public int exitValue(){return child.exitValue();}
                @Override public void destroy(){child.destroy();}
            },REAP_TIMEOUT_MS);
            if(line!=null){
                Log.w("SCAM_HDR",line);
                if(generation==JOB_GENERATION.get()){
                    final String rep;
                    synchronized(report){report.append(line).append('\n');rep=report.toString();}
                    prefs.edit().putString("report",rep).apply();
                }
            }
            deleteTree(dir);
        };
        // (review m4: on a thread of its own - the cleanup thread's folder deletions and standby reaps do not queue behind it)
        try{final Thread t=new Thread(reap,"SCAMERA-worker-reap");t.setDaemon(true);t.start();}
        catch(RuntimeException|OutOfMemoryError e){try{CLEANUP.execute(reap);}catch(RuntimeException e2){reap.run();}}
    }

    /** Lab stage view: the stage maps of the last job that asked for them (ScamTransport.stages); the lab takes and clears it. */
    public static volatile ScamMergeStages lastStages;
    /** P27: the report of the last merge job (ScamHybridBurst reads how the worker ended to pick its fallback tier). */
    public static volatile String lastJobReport;
    /** P212d: the row-profile lines of the last merge job (capture.RowFlicker votes on the lamp flicker from them). */
    public static volatile java.util.List<String> lastRowProfiles=java.util.Collections.emptyList();
    /** P27: text put at the top of the next merge job's report (the failed first attempt of a retry). */
    public static volatile String carryReport;
    /** P212h (A5): the worker's report line of the effective-frame map ("... median frames=F code scale=K": code 64 = F frames). */
    static final String EFFECTIVE_MAP_LINE="HYBRID EFFECTIVE MAP:";

    /** Number after `key=` on the first report line containing `line` (worker report), or `fallback`. */
    /**
     * P320: the worker's «HYBRID NOISE CHECK DCG: slope=S hcg=O1 lcg=O2 knee=T ...» as {S, O1, O2, T} (normalised units); null
     * without that line or with a value out of range (the denoise then keeps the camera's model and the worker's factor).
     */
    static float[] dcgNoise(CharSequence rep){
        final String line="HYBRID NOISE CHECK DCG:";
        final float s=reportFloat(rep,line,"slope="),hcg=reportFloat(rep,line,"hcg="),
                lcg=reportFloat(rep,line,"lcg="),knee=reportFloat(rep,line,"knee=");
        if(!(s>0f)||hcg<0f||lcg<0f||knee<0f||knee>1f||!Float.isFinite(s)||!Float.isFinite(hcg)||!Float.isFinite(lcg))return null;
        return new float[]{s,hcg,lcg,knee};
    }
    /** A number after {@code key} on the report line starting with {@code line}, exponents allowed; -1 when absent. */
    static float reportFloat(CharSequence rep,String line,String key){
        String report=rep.toString();
        int at=report.indexOf(line); if(at<0)return -1f;
        int end=report.indexOf('\n',at); String l=end<0?report.substring(at):report.substring(at,end);
        int k=l.indexOf(key); if(k<0)return -1f;
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("-?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?").matcher(l.substring(k+key.length()));
        try{ return m.lookingAt()?Float.parseFloat(m.group()):-1f; }catch(NumberFormatException e){ return -1f; }
    }
    static float reportNumber(CharSequence rep,String line,String key,float fallback){
        String report=rep.toString();
        int at=report.indexOf(line); if(at<0)return fallback;
        int end=report.indexOf('\n',at); String l=end<0?report.substring(at):report.substring(at,end);
        int k=l.indexOf(key); if(k<0)return fallback;
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("-?[0-9]+(\\.[0-9]+)?").matcher(l.substring(k+key.length()));
        try{ return m.find()?Float.parseFloat(m.group()):fallback; }catch(NumberFormatException e){ return fallback; }
    }
}

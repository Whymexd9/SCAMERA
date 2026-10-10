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
    // Profiles that passed the full chart suite. Persisted per app build (the gate
    // is deterministic for one model/runtime/profile), so a restart does not redo
    // the 54-execution suite (~4 s); a validated profile still runs the four
    // smoke charts on every shot. Never reused between ISO/CFA pairs.
    private static final java.util.Set<String> validatedHexProfiles = new java.util.HashSet<>();
    private static boolean hexProfilesLoaded;
    private static SharedPreferences hexProfileStore(Context context) {
        return context.getSharedPreferences("scam_hex_validated_profiles", Context.MODE_PRIVATE);
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
    /** The worker did not finish in time. ScamHybridBurst skips its conservative retry on this type (the text is localized). */
    public static final class WorkerTimeoutException extends IOException {
        WorkerTimeoutException(String message) { super(message); }
    }
    private static String quote(String s) { return "'"+s.replace("'","'\\''")+"'"; }
    public static synchronized void selfTest(Context context,Consumer<String> log) throws Exception {
        job(context,null,0,0,0,true,false,false,null,null,log);
    }
    public static synchronized ByteBuffer process(Context context,ByteBuffer raw,int w,int h,int redQuad) throws Exception {
        return job(context,raw,w,h,redQuad,false,false,false,null,null,line->Log.d("ScamNeural",line));
    }
    static synchronized ByteBuffer processBurst(Context context,HexQuadBurst burst) throws Exception {
        return job(context,null,burst.width,burst.height,burst.red,true,false,false,burst,null,line->Log.d("ScamNeural",line));
    }
    public static synchronized void selfTestScam(Context context,Consumer<String> log) throws Exception {
        job(context,null,0,0,0,true,true,false,null,null,log);
    }
    static synchronized ByteBuffer processScamBurst(Context context,ScamTransport burst) throws Exception {
        return job(context,null,burst.width(),burst.height(),burst.cfa(),true,true,false,null,burst,line->Log.i("SCAM_HDR",line));
    }
    public static synchronized void selfTestScamTone(Context context,Consumer<String> log) throws Exception {
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
        @Override public void close() { try { fd.close(); } catch (IOException ignored) {} }
        static SharedMemory wrap(android.os.ParcelFileDescriptor fd) { return new SharedMemory(fd); }
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
    private static ByteBuffer job(Context context,ByteBuffer raw,int w,int h,int redQuad,boolean hex,boolean scam,boolean scamTone,HexQuadBurst burst,ScamTransport scamBurst,Consumer<String> observer) throws Exception {
        if(raw!=null && (w<8||h<8||w%8!=0||h%8!=0||(long)w*h>16000000||raw.remaining()!=(long)w*h*4))
            throw new IOException(Lang.t("Неподдерживаемый размер RAW","Unsupported RAW size"));
        File dir=new File(context.getCacheDir(),"scam-neural-job-"+UUID.randomUUID());
        if(!dir.mkdir())throw new IOException(Lang.t("Не удалось создать папку задания","Could not create the job folder"));
        com.particlesdevs.photoncamera.util.WorkerSpawn.Child process=null;
        SharedMemory scamIn=null,scamOut=null;
        // Without root: the worker and its QNN/CRE runtime are installed as native
        // libraries and run as the app's own child process (sandboxed, same NPU/GPU).
        // su remains the optional fallback for builds without them.
        final boolean direct=(hex||scam) && com.particlesdevs.photoncamera.util.WorkerSpawn.available(context);
        final String profileKey=burst==null?"":burst.options.profileKey(burst.iso,burst.red);
        if(burst!=null)loadHexProfiles(context);
        final boolean cachedProfile=burst!=null&&validatedHexProfiles.contains(profileKey);
        final long startMs=android.os.SystemClock.elapsedRealtime();
        if(scamBurst!=null)com.particlesdevs.photoncamera.processing.ShotTimeline.mark("job");
        // A self-test must never overwrite the failed photograph's report.
        SharedPreferences prefs=context.getSharedPreferences(
                scamBurst!=null?"scam_capture_report":scamTone?"scam_tone_report":scam?"scam_root_report":raw!=null||burst!=null?"scam_neural_capture_report":"scam_neural_report",Context.MODE_PRIVATE);
        StringBuilder report=new StringBuilder("SCAMERA: neural inference job\n");
        // P27: a retry after a failed merge keeps the first attempt in its report.
        final String carried=carryReport;
        if(scamBurst!=null&&carried!=null)report.append("PREVIOUS ATTEMPT:\n").append(carried).append("\nRETRY:\n");
        // W1.5: apply (the in-memory report is current at once; the disk write leaves the shot's path).
        prefs.edit().putString("report",report.toString()).putBoolean("complete",false).apply();
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
            if(scamBurst!=null)com.particlesdevs.photoncamera.processing.ShotTimeline.workerLine(line);
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
                throw new IOException(hex||scam?Lang.t("Обработчик не установлен в этой сборке; включите «Root-доступ» в «Настройки → Система»","The processor is not installed in this build; turn on “Root access” in Settings → System")
                        :Lang.t("Этот нейроремозаик работает только с root: включите «Root-доступ» в «Настройки → Система»","This neural remosaic works only with root: turn on “Root access” in Settings → System"));
            {
                final ZipFile apk=apk(context);
                java.util.ArrayList<String> names=new java.util.ArrayList<>();
                java.util.HashSet<String> scamAssets=new java.util.HashSet<>();
                names.add("scam-neural-worker");
                if(scam)for(String[] item:scamTone?ScamNeuralWorker.SCAM_TONE_FILES:ScamNeuralWorker.SCAM_FILES){names.add(item[0]);scamAssets.add(item[0]);}
                final boolean quad=burst!=null&&burst.quad;
                for(String[] item:hex?ScamNeuralWorker.HEX_FILES:ScamNeuralWorker.FILES)
                    if(!scam && !quad || item[0].endsWith(".so"))names.add(item[0]);
                if(quad)for(String[] item:ScamNeuralWorker.QUAD_FILES)names.add(item[0]);
                for(String name:names){
                    if(direct && name.equals("scam-neural-worker"))continue;
                    String prefix=scamAssets.contains(name)?"assets/scam/arm64-v8a/":hex&&!name.equals("scam-neural-worker")?"assets/scam-hexquad/arm64-v8a/":"assets/scam-neural/arm64-v8a/";
                    // The QNN/CRE runtime of the SCAM and HexQuad sets ships once, as native
                    // libraries: the sandbox can map executable code only from nativeLibraryDir,
                    // and the su launcher reads the same files. (The tele576 set has its own
                    // QNN build and stays an asset.)
                    final boolean shared=name.endsWith(".so") && !prefix.equals("assets/scam-neural/arm64-v8a/");
                    File installed=shared?com.particlesdevs.photoncamera.util.WorkerSpawn.library(context,name):null;
                    if(direct&&shared&&installed==null)throw new IOException(Lang.t("Неполная установка: нет ","Incomplete install: missing ")+name);
                    if(installed!=null){
                        try{android.system.Os.symlink(installed.getAbsolutePath(),new File(dir,name).getAbsolutePath());}
                        catch(android.system.ErrnoException e){throw new IOException(Lang.t("Не удалось подготовить ","Could not prepare ")+name+": "+e.getMessage());}
                        continue;
                    }
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
            if(scamBurst!=null){
                String creSource=com.particlesdevs.photoncamera.settings.PreferenceKeys.getScamCreSource();
                String marker="bundled".equals(creSource)?"cre-force-bundled":"vendor".equals(creSource)?"cre-vendor-only":null;
                if(marker!=null && !new File(dir,marker).createNewFile())throw new IOException(Lang.t("Не удалось создать маркер CRE","Could not create the CRE marker"));
            }
            // Only the hybrid burst asks for the hybrid merge: a ScamBurst reaches the worker when the engine is
            // the network or the burst is a Quad/Tetra mosaic (HdrxProcessor), and the hybrid would read the mosaic
            // as plain Bayer (vivo tele in ISZ: blue image, 3 October). Without the model the worker falls back itself.
            final boolean hybridMerge=scamBurst instanceof ScamHybridBurst;
            if(hybridMerge){
                // SCAM Hybrid merge (scam-hybrid.h): no neural model, any GPU. The worker reads the marker
                // and the tuning lines written from the SCAM HDR settings.
                if(!new File(dir,"hybrid-merge").createNewFile())throw new IOException(Lang.t("Не удалось создать маркер склейки Hybrid","Could not create the Hybrid merge marker"));
                // A worker of this process died inside the CRE: this one aligns with SCAMERA's own tile alignment.
                if(((ScamHybridBurst)scamBurst).creOff()&&!new File(dir,"cre-off").createNewFile())
                    throw new IOException(Lang.t("Не удалось создать маркер CRE","Could not create the CRE marker"));
                // P30: the worker keeps its compiled GPU programs in the app's cache (−0.6 s per shot after the first).
                File glCache=new File(context.getCacheDir(),"hybrid-gl");
                if(glCache.isDirectory()||glCache.mkdirs())
                    try(java.io.FileWriter cw=new java.io.FileWriter(new File(dir,"gl-cache"))){cw.write(glCache.getAbsolutePath());}
                String tuning=com.particlesdevs.photoncamera.settings.PreferenceKeys.hybridTuningText()+((ScamHybridBurst)scamBurst).tuningOverride();
                if(!tuning.isEmpty())try(java.io.FileWriter tw=new java.io.FileWriter(new File(dir,"hybrid_tuning.txt"))){tw.write(tuning);}
                log.accept("CLIENT: SCAM Hybrid merge requested"+(tuning.isEmpty()?"":" tuning="+tuning.replace('\n',' ')));
            }
            if(scamBurst!=null){
                // Developer switch (created with adb): dump the network's input/output tiles.
                File external=context.getExternalFilesDir(null);
                if(external!=null && new File(external,"dump-forward").exists()){
                    try(java.io.FileWriter marker=new java.io.FileWriter(new File(dir,"dump-forward"))){marker.write(external.getAbsolutePath());}
                }
            }
            final long assetsDone=android.os.SystemClock.elapsedRealtime();
            File input=new File(dir,"input.f32"),output=new File(dir,"output.f32");
            if(burst!=null)burst.write(input);
            // SCAM: burst and result through shared memory (memfd) instead of ~0.6 GB
            // written to and read back from flash per shot; the root worker opens
            // them as /proc/<pid>/fd/<n>. Files remain the fallback.
            // P30: with the app-process worker and shared memory the worker starts first and the burst is written while it
            // starts (spawn, CRE and GPU driver), then a byte on a pipe (job file "wait-go") lets it map the burst.
            boolean deferredWrite=false;
            // P30: the frames already lie in the shot's arena (memfd): only the header is written, the worker maps it.
            final android.os.ParcelFileDescriptor arena=scamBurst!=null&&android.os.Build.VERSION.SDK_INT>=30?scamBurst.sharedBurst():null;
            if(arena!=null){
                scamIn=SharedMemory.wrap(arena);
                scamOut=SharedMemory.create("scamera-scam-out");
                if(scamOut==null){scamIn.close();scamIn=null;}
                else log.accept("CLIENT: burst in the shot arena (no copy)");
            }
            if(scamBurst!=null&&scamIn==null){
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
            if(raw!=null || burst!=null || (scamBurst!=null && scamOut==null)){
                if(raw!=null)try(FileChannel channel=new FileOutputStream(input).getChannel()){ByteBuffer data=raw.duplicate();while(data.hasRemaining())channel.write(data);}
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
            if(direct){
                String dirPath=dir.getAbsolutePath();
                java.util.ArrayList<String> args=new java.util.ArrayList<>();
                android.os.ParcelFileDescriptor[] fds=new android.os.ParcelFileDescriptor[0];
                if(burst!=null)java.util.Collections.addAll(args,burst.quad?"--quad-capture":cachedProfile?"--hexquad-capture-cached":"--hexquad-capture",
                        dirPath,input.getAbsolutePath(),output.getAbsolutePath());
                else if(scamBurst!=null){
                    // memfd burst/result inherited as fd 3/4 (the sandbox cannot open /proc/<pid>/fd).
                    if(scamOut!=null)fds=goPipe!=null?new android.os.ParcelFileDescriptor[]{scamIn.fd,scamOut.fd,goPipe[0]}
                            :new android.os.ParcelFileDescriptor[]{scamIn.fd,scamOut.fd};
                    java.util.Collections.addAll(args,"--scam-capture",dirPath,scamOut!=null?"fd:3":input.getAbsolutePath(),
                            scamOut!=null?"fd:4":output.getAbsolutePath());
                }
                else if(scamTone)java.util.Collections.addAll(args,"--scam-tone-check",dirPath);
                else if(scam)java.util.Collections.addAll(args,"--scam-check",dirPath);
                else java.util.Collections.addAll(args,"--hexquad-check",dirPath);
                log.accept(Lang.t("WORKER: отдельный процесс приложения (без root)","WORKER: separate app process (no root)"));
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
            if(raw!=null)command+=" "+quote(input.getAbsolutePath())+" "+quote(output.getAbsolutePath())+" "+w+" "+h+" "+redQuad;
            if(burst!=null)command+=(burst.quad?" --quad-capture ":cachedProfile?" --hexquad-capture-cached ":" --hexquad-capture ")+quote(input.getAbsolutePath())+" "+quote(output.getAbsolutePath());
            else if(scamBurst!=null)command+=" --scam-capture "+quote(scamIn!=null?scamIn.path():input.getAbsolutePath())
                    +" "+quote(scamOut!=null?scamOut.path():output.getAbsolutePath());
            else if(scamTone)command+=" --scam-tone-check";
            else if(scam)command+=" --scam";
            else if(hex)command+=" --hexquad";
            log.accept(Lang.t("ROOT: запуск отдельного процесса; разрешите запрос root","ROOT: starting a separate process; allow the root request"));
            process=com.particlesdevs.photoncamera.util.WorkerSpawn.Child.of(new ProcessBuilder("su","-c",command).redirectErrorStream(true).start());
            }
            final com.particlesdevs.photoncamera.util.WorkerSpawn.Child child=process;
            final boolean[] completed={false};
            Thread reader=new Thread(()->{
                try(BufferedReader lines=new BufferedReader(new InputStreamReader(child.output))){String line;while((line=lines.readLine())!=null){if(scamBurst!=null?line.equals("SCAM CAPTURE OK"):scamTone?line.startsWith("SCAM TONE RUNTIME CHECK COMPLETE:"):scam?line.equals("SCAM RUNTIME CHECK COMPLETE"):burst!=null?line.equals("HEXQUAD CAPTURE OK"):hex?line.startsWith("HEXQUAD CHECK COMPLETE:"):line.equals("NEURAL JOB OK"))completed[0]=true;log.accept(line);}}
                catch(IOException e){log.accept("READ: "+e);}
            },"scam-neural-output");
            reader.setDaemon(true);reader.start();
            // P27 any resolution: a Hybrid merge above 16 MP gets a wait in proportion to its pixels (900 s up to 16 MP, unchanged).
            final long timeoutS=scamBurst instanceof ScamHybridBurst?ScamHybridBurst.workerTimeoutSeconds(scamBurst.width(),scamBurst.height())
                    :burst!=null||scamBurst!=null?900:scamTone?420:200;
            if(timeoutS>900)log.accept("CLIENT: worker timeout "+timeoutS+" s for "+scamBurst.width()+"x"+scamBurst.height());
            if(!process.waitFor(TimeUnit.SECONDS.toMillis(timeoutS))){process.destroy();throw new WorkerTimeoutException(Lang.t("Тайм-аут нейромодуля; снимок не обработан","Neural module timed out; the shot was not processed"));}
            reader.join(5000);
            if(process.exitValue()!=0||!completed[0]){
                // P26: the X200 Pro (Mali) worker vanished mid-merge with nothing in the log; say how it ended.
                final int code=process.exitValue();
                final String[] signals={"","SIGHUP","SIGINT","SIGQUIT","SIGILL","SIGTRAP","SIGABRT","SIGBUS","SIGFPE","SIGKILL (low memory or killed)","SIGUSR1","SIGSEGV"};
                log.accept("WORKER EXIT: "+(code>128&&code-128<signals.length?"signal "+(code-128)+" "+signals[code-128]
                        :code>128?"signal "+(code-128):"code "+code)+(completed[0]?"":", no completion line"));
            }
            if(reader.isAlive()||process.exitValue()!=0||!completed[0])throw new IOException(
                    scamBurst instanceof ScamHybridBurst?Lang.t("Hybrid: склейка не завершена. Отчёт: SCAM HDR — проверка запуска → Отчёт последней съёмки SCAM HDR.","Hybrid: merge not finished. Report: “SCAM HDR: launch check” → “Last SCAM HDR capture report”."):
                    scamBurst!=null?Lang.t("SCAM HDR не завершён. Откройте SCAM HDR — проверка запуска → Отчёт последней съёмки SCAM HDR.","SCAM HDR not finished. Open “SCAM HDR: launch check” → “Last SCAM HDR capture report”."):
                    (scam?Lang.t("Проверка SCAM HDR не завершена. Скопируйте этот отчёт. ","SCAM HDR check not finished. Copy this report. "):Lang.t("Нейроремозаик не завершён. Откройте SCAM Neural — проверка → ","Neural remosaic not finished. Open SCAM Neural check → "))+
                    (raw!=null||burst!=null?Lang.t("Отчёт последней съёмки","Last capture report"):Lang.t("Скопировать отчёт","Copy report"))+".");
            if(raw==null&&burst==null&&scamBurst==null)return null;
            final int ow=scamBurst!=null?scamBurst.outputWidth():w,oh=scamBurst!=null?scamBurst.outputHeight():h;
            long expected=scamBurst!=null?(long)ow*oh*12:burst!=null?burst.options.outputBytes(w,h):(long)w*h*4;
            if(expected<=0||expected>Integer.MAX_VALUE)throw new IOException(Lang.t("Слишком большой нейрорезультат","Neural result too large"));
            final long outputBytes=scamOut!=null?scamOut.size():output.length();
            // Trailers after the RGB, each optional, in this order: merged Bayer RAW (sensor grid, w*h*2, if requested), the
            // effective-frame map (OUTPUT grid, ow*oh), the clip flags (OUTPUT grid, ow*oh; SCAM Hybrid, only if requested and only
            // after the map). The fullest layout that matches the file size wins.
            long dngBytes=0,effBytes=0,clipBytes=0;boolean sized=scamBurst==null&&outputBytes==expected;
            if(scamBurst!=null){
                final long rawTrailer=(long)w*h*2,outTrailer=(long)ow*oh;
                search:
                for(long d:scamBurst.mergedDng()?new long[]{rawTrailer,0}:new long[]{0})
                    for(long e:new long[]{outTrailer,0})
                        for(long c:scamBurst.clipFlags()&&e>0?new long[]{outTrailer,0}:new long[]{0})
                            if(outputBytes==expected+d+e+c){dngBytes=d;effBytes=e;clipBytes=c;sized=true;break search;}
            }
            if(!sized)throw new IOException(Lang.t("Неверный размер нейрорезультата","Wrong neural result size"));
            if(scamBurst!=null){ScamBurst.lastMergedDng=null;ScamBurst.lastEffectiveFrames=null;ScamRgb.lastClipFlags=null;
                ScamHybridBurst.lastBentoApplied=report.indexOf("HYBRID BENTO: applied")>=0;
                ScamHybridBurst.lastBentoFactor=reportNumber(report,"HYBRID BENTO: applied","factor=",1f);
                ScamHybridBurst.lastBentoUsClipped=reportNumber(report,"HYBRID BENTO: applied","usClippedRatio=",0f);
                final float noiseK=reportNumber(report,"HYBRID NOISE CHECK:","-> model x",1f);
                ScamHybridBurst.lastNoiseFactor=noiseK>=1f/16&&noiseK<=4f?noiseK:1f;}
            final long readStart=android.os.SystemClock.elapsedRealtime();
            ByteBuffer result=(burst!=null||scamBurst!=null?com.particlesdevs.photoncamera.util.Allocator.allocate((int)expected):ByteBuffer.allocateDirect((int)expected));
            if(result==null)throw new IOException(Lang.t("Недостаточно памяти для результата","Not enough memory for the result"));
            result.order(ByteOrder.nativeOrder());
            try{
                if(scamOut!=null)scamOut.readFully(result,0); // P48: parallel pread of the memfd
                else try(FileChannel channel=new FileInputStream(output).getChannel()){
                    long position=0;
                    while(result.hasRemaining()){int n=channel.read(result,position);if(n<0)throw new EOFException(Lang.t("Неполный результат","Incomplete result"));position+=n;}
                }
            }
            catch(Exception e){if(burst!=null||scamBurst!=null)com.particlesdevs.photoncamera.util.Allocator.free(result);throw e;}
            result.flip();
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
                        dng.flip();ScamBurst.lastMergedDng=dng;
                    }catch(Exception e){com.particlesdevs.photoncamera.util.Allocator.free(dng);log.accept("CLIENT: merged DNG not read: "+e);}
                }
            }
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
                    eff.flip();ScamBurst.lastEffectiveFrames=eff;
                }catch(Exception e){log.accept("CLIENT: effective-frame map not read: "+e);}
            }
            ByteBuffer clip=null;
            if(clipBytes>0)try{clip=ByteBuffer.allocateDirect((int)clipBytes);}catch(OutOfMemoryError oom){log.accept("CLIENT: clip flags skipped: "+oom);}
            if(clip!=null){
                try{
                    if(scamOut!=null)scamOut.readFully(clip,expected+dngBytes+effBytes);
                    else try(FileChannel channel=new FileInputStream(output).getChannel()){
                        long position=expected+dngBytes+effBytes;
                        while(clip.hasRemaining()){int n=channel.read(clip,position);if(n<0)throw new EOFException(Lang.t("Неполные флаги клипа","Incomplete clip flags"));position+=n;}
                    }
                    clip.flip();ScamRgb.lastClipFlags=clip;
                }catch(Exception e){log.accept("CLIENT: clip flags not read: "+e);}
            }else if(scamBurst!=null&&scamBurst.clipFlags())log.accept("CLIENT: clip flags asked for but not returned");
            if(scamBurst!=null)ScamDiagnostics.buffer("02-after-ivst",result,ow,oh,3,true);
            if(burst!=null){if(validatedHexProfiles.size()>=32)validatedHexProfiles.clear();validatedHexProfiles.add(profileKey);saveHexProfiles(context);}
            log.accept("HEX CLIENT OUTPUT ms="+(android.os.SystemClock.elapsedRealtime()-readStart));
            log.accept("HEX CLIENT TOTAL ms="+(android.os.SystemClock.elapsedRealtime()-startMs));
            if(scamBurst!=null)com.particlesdevs.photoncamera.processing.ShotTimeline.mark("client_done");
            return result;
        } catch(Exception e){if(validatedHexProfiles.remove(profileKey))saveHexProfiles(context);log.accept("CLIENT STOP: "+e.getMessage());throw e;}
        finally {
            if(process!=null && process.isAlive())process.destroy();
            if(scamIn!=null)scamIn.close();
            if(scamOut!=null)scamOut.close();
            synchronized(report){
                // W1.11: apply and the folder deleted on the cleanup thread (both were on the shot's path, 25 ms).
                prefs.edit().putString("report",report.toString()).putBoolean("complete",true).apply();
                if(scamBurst!=null)lastJobReport=report.toString();
            }
            if(scamBurst!=null)ScamDiagnostics.nativeFiles(dir,report.toString());
            deleteTreeLater(dir);
        }
    }

    /** P27: the report of the last merge job (ScamHybridBurst reads how the worker ended to pick its fallback tier). */
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

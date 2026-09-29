package com.particlesdevs.photoncamera.capture;

import android.content.Context;
import android.hardware.camera2.*;
import android.os.SystemClock;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;

public final class VivoStockAe implements AutoCloseable {
    private static final String DONOR="b2e3e12469a05cf62c2a5892b2b619228fb29a3ccb8fa9fe5f934df0dd5842e9";
    private static final String INJECTOR="4952c58fa7f3caca7816e5e8a2b8be9c2b88b95a28110296504007b661c2fc4f";
    private static final CaptureResult.Key<float[]> DEBUG=new CaptureResult.Key<>("vivo.feedback.AECRealtimeDebugData",float[].class);
    private static final CaptureResult.Key<float[]> AEC=new CaptureResult.Key<>("vivo.control.Vivo3rdAlgoAECFrameControl",float[].class);
    public final int generation;
    private final LinkedHashMap<Long,Preview> previews=new LinkedHashMap<>();
    private final LinkedHashMap<Long,Sample> snapshots=new LinkedHashMap<>();
    private final HashMap<Integer,Sample> pending=new HashMap<>();
    private volatile boolean closed;
    private boolean subscribed;
    private int firstEnter=-1;
    private Plan latest;
    private final TreeMap<Long,Plan> plans=new TreeMap<>();
    private String failure="Ожидание стокового AE";
    private static native byte[] nativeSolve(byte[] snapshot);

    // One injected observer per app process, shared by camera sessions. Attaching
    // takes seconds, so it is started when the camera opens and survives session
    // reconfiguration; it is stopped only after IDLE_STOP_NS without subscribers.
    private static final long IDLE_STOP_NS=90_000_000_000L;
    private static final Object SHARED=new Object();
    private static final Set<VivoStockAe> listeners=new java.util.concurrent.CopyOnWriteArraySet<>();
    private static Context sharedContext;
    private static Thread sharedThread;
    private static Process sharedProcess;
    private static long idleSince=SystemClock.elapsedRealtimeNanos();
    private static String verifiedInjector;

    public static boolean supportedDevice(){return "PD2454".equals(android.os.Build.DEVICE);}
    public static void warmUp(Context context) {
        if(!supportedDevice())return;
        synchronized(SHARED) {
            idleSince=SystemClock.elapsedRealtimeNanos();
            if(sharedContext==null)sharedContext=context.getApplicationContext();
            if(sharedThread!=null && sharedThread.isAlive())return;
            sharedThread=new Thread(VivoStockAe::runShared,"SCAMERA-stock-AE");
            sharedThread.setDaemon(true);sharedThread.start();
        }
    }

    public VivoStockAe(Context context,int generation,String cameraId) {
        this.generation=generation;
        // Calibration was measured separately on all three rear RAW cameras.
        if(!supportedDevice() || !("3".equals(cameraId)||"4".equals(cameraId)||"5".equals(cameraId))) {
            failure="Стоковый AE: gain-калибровка этой камеры ещё не проверена";return;
        }
        subscribed=true;listeners.add(this);warmUp(context);
    }
    private static final class Preview {
        final float[] debug;final long timestamp,received;
        Preview(float[] debug,long timestamp){this.debug=debug.clone();this.timestamp=timestamp;received=SystemClock.elapsedRealtimeNanos();}
    }
    private static final class Sample {
        final JSONObject enter;final HashMap<Integer,byte[]> data=new HashMap<>();
        final HashMap<Integer,Integer> scalar=new HashMap<>();
        byte[] plan;
        Sample(JSONObject enter){this.enter=enter;}
    }
    public synchronized void offer(TotalCaptureResult result) {
        if(closed)return;
        try {
            float[] d=result.get(DEBUG);Long timestamp=result.get(CaptureResult.SENSOR_TIMESTAMP);
            if(d==null || d.length<11 || timestamp==null || timestamp<=0 || d[0]<0 || d[0]>=16777216 || d[0]!=(long)d[0])return;
            long id=(long)d[0];previews.put(id,new Preview(d,timestamp));trim(previews);bind(id);
        }catch(RuntimeException unavailable){failure="Стоковый AE: vendor-метаданные недоступны";}
    }
    private static <T> void trim(LinkedHashMap<Long,T> map) {while(map.size()>48)map.remove(map.keySet().iterator().next());}
    private synchronized void bind(long id) {
        Preview preview=previews.get(id);Sample sample=snapshots.get(id);
        if(preview==null || sample==null || closed)return;
        try {
            ByteBuffer p=ByteBuffer.wrap(hex(sample.enter.getString("input"),0xe8)).order(ByteOrder.LITTLE_ENDIAN);
            if((float)p.getLong(0)!=preview.debug[5] ||
               Float.floatToRawIntBits(p.getFloat(8))!=Float.floatToRawIntBits(preview.debug[2]) ||
               Float.floatToRawIntBits(p.getFloat(12))!=Float.floatToRawIntBits(preview.debug[8]) ||
               Float.floatToRawIntBits(p.getFloat(0x5c))!=Float.floatToRawIntBits(preview.debug[9]))
                throw new IOException("Несовпадение Camera2/native AE кадра");
            VivoNiceAeContext niceContext=new VivoNiceAeContext(p.array(),sample.data.get(0x298),sample.plan);
            Plan next=new Plan(sample.plan,niceContext,id,preview.timestamp,preview.received,generation);
            plans.put(next.timestamp,next);while(plans.size()>48)plans.pollFirstEntry();
            if(latest==null || latest.timestamp<next.timestamp){latest=next;failure=null;}
            notifyAll();
        }catch(Exception invalid){latest=null;plans.clear();failure=invalid.toString();}
    }
    public synchronized Plan freeze() {
        return freeze(Long.MAX_VALUE);
    }
    public synchronized Plan freeze(long shutterTimestamp) {
        Map.Entry<Long,Plan> entry=plans.floorEntry(shutterTimestamp);
        Plan selected=entry==null?null:entry.getValue();
        if(closed || latest==null || selected==null || SystemClock.elapsedRealtimeNanos()-selected.received>800_000_000L)
            throw new IllegalStateException(failure==null?"Стоковый AE устарел":failure);
        return selected;
    }
    // Frames after the shutter may stand in only when no pre-shutter plan can
    // exist (observer attached after the press); ZSL N frames are still checked
    // against the chosen plan's exposure and gain.
    private static final long POST_SHUTTER_TOLERANCE_NS=3_000_000_000L;
    private static final long STALE_REPLACEMENT_NS=250_000_000L;
    // The observer delivers a preview frame's native AE after the frame's
    // TotalCaptureResult, so a plan for a frame before the shutter can still be
    // in flight when the shutter is pressed. Returns null while the caller may
    // keep polling (mayWait), never blocks: preview results feeding offer()
    // arrive on the same camera thread that polls.
    public synchronized Plan tryFreeze(long shutterTimestamp,boolean mayWait) {
        if(!closed) {
            Map.Entry<Long,Plan> entry=plans.floorEntry(shutterTimestamp);
            if(entry!=null && SystemClock.elapsedRealtimeNanos()-entry.getValue().received<=800_000_000L)
                return entry.getValue();
            // Observer events arrive in frame order: once a later frame is
            // planned, no plan for an earlier frame will follow.
            Map.Entry<Long,Plan> after=plans.ceilingEntry(shutterTimestamp+1);
            if(after!=null) {
                // Right after a NICE bracket the manual frames carry no plan, so the
                // last pre-shutter plan is older than the freshness limit; a plan for
                // a frame just after the press describes the same scene.
                if(entry!=null && after.getKey()-shutterTimestamp<=STALE_REPLACEMENT_NS) {
                    android.util.Log.w("NICE_AE","pre-shutter plan stale (bracket gap); using frame +"
                        +(after.getKey()-shutterTimestamp)/1000000+" ms");
                    return after.getValue();
                }
                if(entry==null && after.getKey()-shutterTimestamp<=POST_SHUTTER_TOLERANCE_NS) {
                    android.util.Log.w("NICE_AE","no pre-shutter plan (observer attached after press); using frame +"
                        +(after.getKey()-shutterTimestamp)/1000000+" ms");
                    return after.getValue();
                }
            } else if(mayWait && !(plans.isEmpty() && failure!=null)) return null;
            android.util.Log.w("NICE_AE","no usable plan: shutter="+shutterTimestamp+" floorAgeMs="
                +(entry==null?-1:(SystemClock.elapsedRealtimeNanos()-entry.getValue().received)/1000000)
                +" nextPlanDeltaMs="+(after==null?-1:(after.getKey()-shutterTimestamp)/1000000)
                +" plans="+plans.size()+" mayWait="+mayWait+" failure="+failure);
        }
        return freeze(shutterTimestamp);
    }
    private static byte[] hex(String text,int expected)throws IOException {
        if(text.length()!=expected*2)throw new IOException("AE field extent");
        byte[] out=new byte[expected];
        for(int i=0;i<expected;i++) {
            int hi=Character.digit(text.charAt(i*2),16),lo=Character.digit(text.charAt(i*2+1),16);
            if(hi<0 || lo<0)throw new IOException("AE hexadecimal field");out[i]=(byte)((hi<<4)|lo);
        }
        return out;
    }
    private static byte[] field(JSONObject object,String name,int size)throws Exception {return hex(object.getString(name),size);}
    private static void bank(ByteBuffer out,JSONObject bank)throws Exception {
        JSONArray tables=bank.getJSONArray("tables");if(tables.length()<1 || tables.length()>16)throw new IOException("AE bank extent");
        out.putInt(tables.length());
        for(int i=0;i<tables.length();i++) {
            JSONObject table=tables.getJSONObject(i);ByteBuffer h=ByteBuffer.wrap(field(table,"header",32)).order(ByteOrder.LITTLE_ENDIAN);
            int count=h.getInt(4);if(count<2 || count>1024)throw new IOException("AE table extent");
            byte[] rows=field(table,"rows",count*24);out.putFloat(h.getFloat(0)).putFloat(h.getFloat(24)).putInt(count);
            for(int j=0;j<count;j++)out.put(rows,j*24,4).put(rows,j*24+8,12);
        }
        ByteBuffer h=ByteBuffer.wrap(field(bank,"header",0x48)).order(ByteOrder.LITTLE_ENDIAN);
        int count=h.getInt(0x38);if(count<0 || count>1024)throw new IOException("AE blur extent");
        out.putInt(count).put(field(bank,"blurRows",count*12));
    }
    private byte[] solve(Sample s,JSONObject leave)throws Exception {
        ByteBuffer bytes=ByteBuffer.allocate(1024*1024).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put(field(s.enter,"input",0xe8)).put(field(s.enter,"common",0xb8)).put(field(s.enter,"calculator",0x74));
        int[] offsets={0x298,0x300,0xc0,0x10,0x2a0,0x2c0},sizes={0x930,0x440,12,8,0xb0,0x44};
        for(int i=0;i<offsets.length;i++) {
            byte[] value=s.data.get(offsets[i]);if(value==null || value.length!=sizes[i])throw new IOException("Missing scoped AE accessor");bytes.put(value);
        }
        bytes.put(field(s.enter,"motion",0x65c));Integer type=s.scalar.get(0x168);
        if(type==null)throw new IOException("Missing native sensor type");bytes.putInt(type);
        bank(bytes,s.enter.getJSONObject("bank"));bank(bytes,s.enter.getJSONObject("alternateBank"));
        byte[] solved=nativeSolve(Arrays.copyOf(bytes.array(),bytes.position()));
        byte[] original=field(leave,"output",0x84),after=field(leave,"input",0xe8);
        if(solved==null || solved.length!=100)throw new IOException("AE solver result extent");
        int[] destinations={0,16,32,48,64,72,76,92,96},sources={0,16,32,64,96,104,116,0x60,0xd8},counts={16,16,16,16,8,4,16,4,4};
        for(int k=0;k<counts.length;k++)for(int j=0;j<counts[k];j++)
            if(solved[destinations[k]+j]!=(k>=7?after:original)[sources[k]+j])throw new IOException("Portable/vendor AE mismatch");
        return solved;
    }
    private synchronized void event(JSONObject e)throws Exception {
        String kind=e.getString("event");
        if(kind.equals("error")){pending.clear();throw new IOException("AE observer: "+e.optString("error"));}
        if(kind.equals("finished"))throw new IOException("AE observer stopped: "+e.optString("reason"));
        if(!e.has("sample"))return;
        int id=e.getInt("sample");
        if(kind.equals("enter")) {
            if(pending.size()>16 || pending.containsKey(id) || !"0x1f67a0".equals(e.getString("vtableOffset")) || e.isNull("frameId"))throw new IOException("AE context identity");
            if(firstEnter<0)firstEnter=id;
            pending.put(id,new Sample(e));return;
        }
        // A session subscribing to the already-running observer can receive the
        // tail of a sample whose enter it never saw; that sample is skipped.
        if(firstEnter<0 || id<firstEnter)return;
        Sample s=pending.get(id);if(s==null)throw new IOException("AE scope missing");
        if(e.getInt("thread")!=s.enter.getInt("thread"))throw new IOException("AE thread mismatch");
        if(kind.equals("accessor")) {
            int off=e.getInt("offset");
            if(!e.isNull("data")) {
                String value=e.getString("data");if(value.length()>32768*2)throw new IOException("AE accessor extent");
                byte[] data=hex(value,value.length()/2),prior=s.data.put(off,data);
                if(prior!=null && !Arrays.equals(prior,data))throw new IOException("AE accessor changed");
            } else {
                int value=e.getInt("scalar");Integer prior=s.scalar.put(off,value);
                if(prior!=null && prior!=value)throw new IOException("AE scalar changed");
            }
        } else if(kind.equals("leave")) {
            pending.remove(id);if(e.getInt("returnBits")!=0)throw new IOException("Vendor AE failed");
            s.plan=solve(s,e);long frame=Long.parseLong(s.enter.getString("frameId"));
            snapshots.put(frame,s);trim(snapshots);bind(frame);
        }
    }
    private static String quote(String s){return "'"+s.replace("'","'\\''")+"'";}
    private static boolean injectorMatches(File file)throws Exception {
        if(!file.isFile())return false;
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)){byte[] chunk=new byte[65536];int n;while((n=in.read(chunk))>=0)md.update(chunk,0,n);}
        StringBuilder sha=new StringBuilder();for(byte b:md.digest())sha.append(String.format(Locale.ROOT,"%02x",b&255));
        return INJECTOR.contentEquals(sha);
    }
    private static File asset(Context context,String name,boolean gzip)throws Exception {
        File dir=new File(context.getFilesDir(),"stock-ae");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("AE directory");
        File output=new File(dir,gzip?"frida-inject":name);
        if(gzip) {
            // Hashing the 58 MB injector costs noticeable time; do it once per
            // process for an unchanged file.
            String key=output.getAbsolutePath()+':'+output.length()+':'+output.lastModified();
            if(key.equals(verifiedInjector))return output;
            if(injectorMatches(output)){verifiedInjector=key;return output;}
        }
        File staged=File.createTempFile("ae-", ".tmp",dir);
        try(InputStream raw=context.getAssets().open("vivo-aec/"+name);
            InputStream in=gzip?new GZIPInputStream(raw):raw;OutputStream out=new FileOutputStream(staged)) {
            byte[] chunk=new byte[65536];int n;while((n=in.read(chunk))>=0)out.write(chunk,0,n);
        }
        if(gzip && !injectorMatches(staged)){staged.delete();throw new IOException("AE injector checksum");}
        if(!staged.renameTo(output)){staged.delete();throw new IOException("AE asset publication");}
        if(gzip)verifiedInjector=output.getAbsolutePath()+':'+output.length()+':'+output.lastModified();
        return output;
    }
    private static boolean idleExpired() {
        return listeners.isEmpty() && SystemClock.elapsedRealtimeNanos()-idleSince>IDLE_STOP_NS;
    }
    private static void runShared() {
        // Closing the injector's stdin makes observer-run.sh detach cleanly; with
        // the camera closed no events arrive, so a watchdog must do it.
        Thread watchdog=new Thread(()->{
            while(true) {
                try{Thread.sleep(5000);}catch(InterruptedException interrupted){return;}
                synchronized(SHARED){if(idleExpired()){closeSharedProcess();return;}}
            }
        },"SCAMERA-stock-AE-idle");
        watchdog.setDaemon(true);watchdog.start();
        try {
            while(true) {
                synchronized(SHARED){if(idleExpired())break;}
                observeOnce();
                synchronized(SHARED){if(idleExpired())break;}
                try{Thread.sleep(1000);}catch(InterruptedException interrupted){break;}
            }
        } finally {
            watchdog.interrupt();
            synchronized(SHARED){if(sharedThread==Thread.currentThread())sharedThread=null;}
        }
    }
    private static void broadcastReset(String reason) {
        for(VivoStockAe listener:listeners)listener.reset(reason);
    }
    private static void observeOnce() {
        String failure=null;
        try {
            Context context;synchronized(SHARED){context=sharedContext;}
            System.loadLibrary("vivoAe");
            File injector=asset(context,"frida-inject.bin",true),script=asset(context,"observer.js",false),
                policy=asset(context,"policy-fix.sh",false),runner=asset(context,"observer-run.sh",false);
            // Injectors left by a previous app process (killed app, hung attach) keep
            // their agent in the camera provider; stop them before attaching again.
            String command="for q in $(pidof frida-inject); do case \"$(tr '\\0' ' ' </proc/$q/cmdline 2>/dev/null)\" in *stock-ae/frida-inject*) kill -TERM $q;; esac; done 2>/dev/null; "
                +"sleep 0.3; for q in $(pidof frida-inject); do case \"$(tr '\\0' ' ' </proc/$q/cmdline 2>/dev/null)\" in *stock-ae/frida-inject*) kill -KILL $q;; esac; done 2>/dev/null; "
                +"set -e; test \"$(sha256sum /vendor/lib64/camera/components/com.vivo.stats.aec.so | cut -d ' ' -f 1)\" = "+DONOR
                +"; p=$(pidof vendor.qti.camera.provider-service_64); case \"$p\" in ''|*[!0-9]*) exit 21;; esac; "
                +"sh "+quote(policy.getAbsolutePath())+" \"$p\" >&2; chmod 700 "+quote(injector.getAbsolutePath())
                +"; exec sh "+quote(runner.getAbsolutePath())+" "+quote(injector.getAbsolutePath())+" \"$p\" "
                +quote(script.getAbsolutePath())+" "+quote(injector.getParent());
            Process child=new ProcessBuilder("su","-c",command).start();
            synchronized(SHARED){sharedProcess=child;if(idleExpired()){closeSharedProcess();return;}}
            Thread errors=new Thread(()->{try(BufferedReader r=new BufferedReader(new InputStreamReader(child.getErrorStream()))){String line;while((line=r.readLine())!=null)android.util.Log.w("NICE_AE",line);}catch(IOException ignored){}},"SCAMERA-AE-errors");errors.setDaemon(true);errors.start();
            // Diagnostics only: raw observer events are copied while the flag
            // file cache/ae-dump exists (created via adb/root), capped at 64 MB.
            File dumpFlag=new File(context.getCacheDir(),"ae-dump");
            java.io.Writer dump=dumpFlag.exists()?new java.io.FileWriter(new File(context.getCacheDir(),"ae-dump.log"),true):null;
            long dumped=0;
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(child.getInputStream()))) {
                String line;while((line=reader.readLine())!=null) {
                    if(line.length()>262144)throw new IOException("AE event extent");
                    if(!line.startsWith("SCAMERA_AE_CONTEXT "))continue;
                    if(dump!=null && dumped<64L*1024*1024){dump.write(line);dump.write('\n');dumped+=line.length();}
                    JSONObject message=new JSONObject(line.substring(19));
                    if("finished".equals(message.optString("event")))throw new IOException("AE observer interval ended");
                    if("attached".equals(message.optString("event")))android.util.Log.i("NICE_AE","observer attached");
                    for(VivoStockAe listener:listeners)listener.dispatch(message);
                }
            }
            finally{if(dump!=null)try{dump.close();}catch(IOException ignored){}}
        }catch(Exception|LinkageError error){failure="Стоковый AE: "+error;}
        finally{
            synchronized(SHARED){closeSharedProcess();}
            broadcastReset(failure==null?"Ожидание стокового AE":failure);
        }
    }
    private static void closeSharedProcess() {
        if(sharedProcess!=null)try{sharedProcess.getOutputStream().close();}catch(IOException ignored){}
        sharedProcess=null;
    }
    private synchronized void dispatch(JSONObject message) {
        if(closed)return;
        try{event(message);}
        catch(Exception invalid){failure=invalid.toString();latest=null;plans.clear();pending.clear();}
    }
    // Observer restarted: its sample numbering starts over.
    private synchronized void reset(String reason) {
        failure=reason;latest=null;plans.clear();pending.clear();snapshots.clear();firstEnter=-1;
    }
    @Override public synchronized void close(){
        closed=true;latest=null;plans.clear();pending.clear();snapshots.clear();previews.clear();notifyAll();
        if(subscribed){listeners.remove(this);synchronized(SHARED){idleSince=SystemClock.elapsedRealtimeNanos();}}
    }

    public static final class Plan {
        public final long frameId,timestamp;final long received;public final int generation;
        public final String sceneDescription;
        private final long[] shutter=new long[4];private final int[] iso=new int[4];private final double[] product=new double[4];
        private final float[] gain=new float[4];
        private boolean degenerate;
        /** Built by the SCAMERA planner: verified against Camera2 exposure/ISO, not vivo AE tags. */
        private boolean camera2Domain;
        Plan(byte[] data,VivoNiceAeContext niceContext,long frameId,long timestamp,long received,int generation)throws IOException {
            if(niceContext==null)throw new IOException("SCAM HDR AE: контекст сцены не проверен");
            sceneDescription=niceContext.describe();
            this.frameId=frameId;this.timestamp=timestamp;this.received=received;this.generation=generation;
            ByteBuffer p=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            for(int i=0;i<4;i++) {
                float ns=p.getFloat(i*16),gain=p.getFloat(i*16+4);
                if(!Float.isFinite(ns)||!Float.isFinite(gain)||ns<=0||gain<=0)throw new IOException("Invalid stock exposure");
                shutter[i]=Math.round((double)ns);iso[i]=(int)Math.round((double)gain*50.);product[i]=(double)ns*gain;
                this.gain[i]=gain;
            }
            // Bright or flat scenes: the stock solver may return S/ES equal to
            // each other or to N. Kept, and repaired at capture time against
            // the sensor limits (withDistinctBracket); not a reason to drop the frame.
            degenerate=!(product[2]<product[1]&&product[1]<product[0]&&product[0]<=product[3]);
        }
        private Plan(Plan base) {
            frameId=base.frameId;timestamp=base.timestamp;received=base.received;generation=base.generation;
            sceneDescription=base.sceneDescription;degenerate=base.degenerate;camera2Domain=base.camera2Domain;
            System.arraycopy(base.shutter,0,shutter,0,4);System.arraycopy(base.iso,0,iso,0,4);
            System.arraycopy(base.product,0,product,0,4);System.arraycopy(base.gain,0,gain,0,4);
        }
        /** Handheld cap for the lengthened L; a longer stock L is never shortened. */
        private static final long LONG_BOOST_SHUTTER_CAP_NS=125_000_000L;
        /**
         * Copy with L (slot 3) lengthened by ev: shutter first up to
         * max(stock L, 1/8 s), the rest as gain within the sensor range.
         * Verification then checks the capture against these new values.
         */
        private static final double BRACKET_STEP=0.5; // -1 EV between N, S and ES when repairing
        private static final double DISTINCT=0.97;
        /** Sets slot to product target: keep gain, change shutter; at the shutter floor, lower gain. */
        private void setProduct(int slot,double target,android.util.Range<Long> times,android.util.Range<Integer> isos) {
            long ns=Math.max(times.getLower(),Math.min(times.getUpper(),Math.round(target/gain[slot])));
            int newIso=(int)Math.max(isos.getLower(),Math.min(isos.getUpper(),Math.round(target/ns*50.)));
            shutter[slot]=ns;iso[slot]=newIso;gain[slot]=newIso/50f;product[slot]=(double)ns*gain[slot];
        }
        /**
         * Copy with ES < S < N <= L enforced for degenerate stock plans:
         * L at least N, S at most N-1EV, ES at most S-1EV, within sensor limits.
         * Returns this when the stock plan is already distinct.
         */
        public Plan withDistinctBracket(CameraCharacteristics characteristics) {
            if(!degenerate)return this;
            android.util.Range<Long> times=characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            android.util.Range<Integer> isos=characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if(times==null||isos==null)throw new IllegalStateException("SCAM HDR: диапазоны сенсора недоступны");
            String before=describePlan();
            Plan p=new Plan(this);p.degenerate=false;
            if(p.product[3]<p.product[0]){p.shutter[3]=p.shutter[0];p.iso[3]=p.iso[0];p.gain[3]=p.gain[0];p.product[3]=p.product[0];}
            if(p.product[1]>=p.product[0]*DISTINCT)p.setProduct(1,p.product[0]*BRACKET_STEP,times,isos);
            if(p.product[2]>=p.product[1]*DISTINCT)p.setProduct(2,p.product[1]*BRACKET_STEP,times,isos);
            if(!(p.product[2]<p.product[1]&&p.product[1]<p.product[0]&&p.product[0]<=p.product[3]))
                throw new IllegalStateException("SCAM HDR: сцена слишком яркая для раздельных S/ES даже на минимальной выдержке");
            android.util.Log.w("NICE_CAPTURE","degenerate stock plan repaired: "+before+" -> "+p.describePlan());
            return p;
        }
        private String describePlan() {
            StringBuilder b=new StringBuilder();String[] names={"N","S","ES","L"};
            for(int i=0;i<4;i++)b.append(names[i]).append('=').append(shutter[i]/1e6).append("ms*").append(gain[i]).append(i<3?" ":"");
            return b.toString();
        }
        private Plan(long timestamp,int generation,String description) {
            frameId=-1;this.timestamp=timestamp;received=SystemClock.elapsedRealtimeNanos();this.generation=generation;
            sceneDescription=description;camera2Domain=true;
        }
        /** Sets slot to a product: shutter first within [minShutter, capShutter], then ISO. */
        private void realize(int slot,double target,long capShutter,android.util.Range<Long> times,android.util.Range<Integer> isos) {
            double g=gain[slot];
            long ns=Math.max(times.getLower(),Math.min(capShutter,Math.round(target/g)));
            int newIso=(int)Math.max(isos.getLower(),Math.min(isos.getUpper(),Math.round(target/ns*50.)));
            shutter[slot]=ns;iso[slot]=newIso;gain[slot]=newIso/50f;product[slot]=(double)ns*gain[slot];
        }
        private void realizeShort(int slot,double target,long maxShutter,android.util.Range<Long> times,android.util.Range<Integer> isos) {
            int newIso=(int)Math.max(isos.getLower(),Math.min(Math.min(iso[slot],isos.getUpper()),Math.round(target/maxShutter*50.)));
            long ns=Math.max(times.getLower(),Math.min(maxShutter,Math.round(target/(newIso/50.))));
            shutter[slot]=ns;iso[slot]=newIso;gain[slot]=newIso/50f;product[slot]=(double)ns*gain[slot];
        }
        /**
         * SCAMERA bracket planner (no root, any Camera2 RAW device): N is the preview
         * exposure at the shutter, so buffered ZSL frames match it; L = N + lEv;
         * S/ES = N - sEv / N - esEv, made shallower when nothing clips and deeper
         * when a large area clips (clipFraction of the newest ZSL RAW).
         *
         * nTrueIso: N's real gain as ISO when the vendor AE reports it (at night the
         * preview runs above the Camera2 range, e.g. ISO 30000 while Camera2 reads
         * 12800, or 13450 on a 3200-max module). Brackets are planned against that
         * real exposure and requested within the Camera2 range; N itself is still
         * verified against its Camera2 metadata (nIso).
         */
        public static Plan scameraPlanner(long nShutter,int nIso,double nTrueIso,float clipFraction,double lEv,double sEv,double esEv,
                boolean adaptive,CameraCharacteristics characteristics,long timestamp,int generation) {
            android.util.Range<Long> times=characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            android.util.Range<Integer> isos=characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if(times==null||isos==null||nShutter<=0||nIso<=0)throw new IllegalStateException("SCAM HDR: нет экспозиции превью или диапазонов сенсора");
            if(adaptive) {
                if(clipFraction<0.0005f){sEv=Math.min(sEv,2);esEv=Math.min(esEv,4);}
                else if(clipFraction>0.02f){esEv=Math.max(esEv,7);}
            }
            esEv=Math.max(esEv,sEv+1);
            final double trueIso=nTrueIso>0?nTrueIso:nIso;
            Plan p=new Plan(timestamp,generation,String.format(java.util.Locale.ROOT,
                    "planner=SCAMERA clip=%.4f L=+%.1fEV S=-%.1fEV ES=-%.1fEV trueISO=%.0f",clipFraction,lEv,sEv,esEv,trueIso));
            for(int i=0;i<4;i++){p.shutter[i]=nShutter;p.iso[i]=nIso;p.gain[i]=(float)(trueIso/50);p.product[i]=(double)nShutter*trueIso/50;}
            double n=p.product[0];
            // L: shutter first (gain at most the sensor maximum), then ISO within range.
            {
                final double target=n*Math.pow(2,lEv),gMax=isos.getUpper()/50.;
                final double g=Math.min(gMax,Math.max(isos.getLower()/50.,trueIso/50));
                long ns=Math.max(times.getLower(),Math.min(Math.max(nShutter,LONG_BOOST_SHUTTER_CAP_NS),Math.round(target/g)));
                int newIso=(int)Math.max(isos.getLower(),Math.min(isos.getUpper(),Math.round(target/ns*50.)));
                if((double)ns*newIso/50<n) {
                    // Above-range N gain: even the maximum gain at the handheld cap is
                    // darker than N. Lengthen L just enough to keep L >= N.
                    ns=Math.min(times.getUpper(),(long)Math.ceil(n/gMax*1.05));newIso=isos.getUpper();
                }
                p.shutter[3]=ns;p.iso[3]=newIso;p.gain[3]=newIso/50f;p.product[3]=(double)ns*p.gain[3];
            }
            // Short frames like the stock plan: lower gain to the sensor floor first,
            // then shorten the shutter (very short shutters get HAL gain rounding).
            p.realizeShort(1,n*Math.pow(2,-sEv),nShutter,times,isos);
            p.realizeShort(2,n*Math.pow(2,-esEv),nShutter,times,isos);
            // Daylight: N already close to the minimum shutter, so S and ES both land
            // on the sensor floor. Put ES on the floor and S halfway (in EV) to N.
            double floor=(double)times.getLower()*isos.getLower()/50.;
            if(!(p.product[2]<p.product[1]&&p.product[1]<p.product[0]) && n>floor*1.25) {
                p.realizeShort(2,floor,nShutter,times,isos);
                p.realizeShort(1,Math.sqrt(n*p.product[2]),nShutter,times,isos);
                android.util.Log.w("NICE_CAPTURE","SCAMERA planner: S/ES limited by the minimum shutter, spread "
                        +String.format(java.util.Locale.ROOT,"S=-%.2fEV ES=-%.2fEV",
                        Math.log(n/p.product[1])/Math.log(2),Math.log(n/p.product[2])/Math.log(2)));
            }
            if(!(p.product[2]<p.product[1]&&p.product[1]<p.product[0]))
                throw new IllegalStateException("SCAM HDR: сцена слишком яркая для раздельных S/ES даже на минимальной выдержке");
            if(!(p.product[0]<=p.product[3]))
                throw new IllegalStateException("SCAM HDR: длинный кадр L не набирает экспозицию N в пределах сенсора ("+p.describePlan()+")");
            android.util.Log.i("NICE_CAPTURE","SCAMERA planner: "+p.sceneDescription+" -> "+p.describePlan());
            return p;
        }
        /** N's real exposure as ISO from the vendor AE (gain*50), or -1. */
        public static double vendorIso(CaptureResult result) {
            float[] aec=result==null?null:result.get(AEC);
            if(aec==null||aec.length<35||!Float.isFinite(aec[2])||aec[2]<=0)return -1;
            return aec[2]*50.;
        }
        public Plan withLongBoost(double ev,CameraCharacteristics characteristics) {
            if(!(ev>0))return this;
            android.util.Range<Long> times=characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            android.util.Range<Integer> isos=characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if(times==null||isos==null)return this;
            Plan p=new Plan(this);
            double target=product[3]*Math.pow(2,ev);
            long cap=Math.min(times.getUpper(),Math.max(shutter[3],LONG_BOOST_SHUTTER_CAP_NS));
            long ns=Math.max(shutter[3],Math.min(cap,Math.round(target/gain[3])));
            int newIso=(int)Math.max(iso[3],Math.min(isos.getUpper(),Math.round(target/ns*50.)));
            p.shutter[3]=ns;p.iso[3]=newIso;p.gain[3]=newIso/50f;p.product[3]=(double)ns*p.gain[3];
            android.util.Log.i("NICE_CAPTURE","long boost "+ev+" EV: L "+shutter[3]/1e6+"ms*"+gain[3]
                    +" -> "+ns/1e6+"ms*"+p.gain[3]+" (actual +"+String.format(java.util.Locale.ROOT,"%.2f",
                    Math.log(p.product[3]/product[3])/Math.log(2))+" EV)");
            return p;
        }
        /** L exposure over N (products), as planned. */
        public double longRatio(){return product[3]/product[0];}
        private static int slot(int index){if(index<0||index>=7)throw new IllegalArgumentException("NICE request index");return index<4?0:index==4?3:index==5?1:2;}
        public long shutter(int index){return shutter[slot(index)];}
        public int iso(int index){return iso[slot(index)];}
        public ImageFrame.CaptureRole role(int index){return index<4?ImageFrame.CaptureRole.NORMAL:index==4?ImageFrame.CaptureRole.LONG:index==5?ImageFrame.CaptureRole.SHORT:ImageFrame.CaptureRole.EXTRA_SHORT;}
        // The vendor analog-gain domain is converted to Camera2 ISO with a fixed
        // gain*50 rounding (see the Plan constructor). That rounding can place a
        // value one or two units beyond the device's exposed SENSITIVITY_RANGE
        // even though the underlying vendor gain is genuinely at the sensor's
        // floor/ceiling. Only that specific rounding margin is clamped; a larger
        // gap (like a mismatched gain-domain conversion) must keep failing loudly.
        private static final int ISO_ROUNDING_TOLERANCE=2;
        private static final long SHUTTER_ROUNDING_TOLERANCE_NS=100_000L;
        private static long clampNear(long value,long lower,long upper,long tolerance,String what) {
            if(value<lower) {
                if(lower-value>tolerance)throw new IllegalStateException("Стоковая экспозиция вне диапазона Camera2: "+what);
                return lower;
            }
            if(value>upper) {
                if(value-upper>tolerance)throw new IllegalStateException("Стоковая экспозиция вне диапазона Camera2: "+what);
                return upper;
            }
            return value;
        }
        /** Whether every slot fits this camera's Camera2 ranges within the rounding tolerances apply() allows. */
        public boolean fitsCamera(CameraCharacteristics characteristics) {
            android.util.Range<Long> times=characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            android.util.Range<Integer> gains=characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if(times==null||gains==null)return false;
            for(int s=0;s<4;s++) {
                if(shutter[s]<times.getLower()-SHUTTER_ROUNDING_TOLERANCE_NS||shutter[s]>times.getUpper()+SHUTTER_ROUNDING_TOLERANCE_NS)return false;
                if(iso[s]<gains.getLower()-ISO_ROUNDING_TOLERANCE||iso[s]>gains.getUpper()+ISO_ROUNDING_TOLERANCE)return false;
            }
            return true;
        }
        public String describeRanges() {
            return "ISO="+java.util.Arrays.toString(iso)+" shutterNs="+java.util.Arrays.toString(shutter);
        }
        public static String describeCamera(CameraCharacteristics characteristics) {
            return "camera ISO="+characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    +" shutterNs="+characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        }
        public void apply(CaptureRequest.Builder builder,int index,CameraCharacteristics characteristics) {
            int slot=slot(index);
            android.util.Range<Long> times=characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            android.util.Range<Integer> gains=characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if(times==null||gains==null)throw new IllegalStateException("Стоковая экспозиция вне диапазона Camera2: диапазон недоступен");
            long clampedShutter=clampNear(shutter[slot],times.getLower(),times.getUpper(),SHUTTER_ROUNDING_TOLERANCE_NS,"выдержка");
            int clampedIso=(int)clampNear(iso[slot],gains.getLower(),gains.getUpper(),ISO_ROUNDING_TOLERANCE,"ISO");
            builder.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_OFF);
            builder.set(CaptureRequest.CONTROL_AE_LOCK,false);builder.set(CaptureRequest.CONTROL_ENABLE_ZSL,false);
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME,clampedShutter);builder.set(CaptureRequest.SENSOR_SENSITIVITY,clampedIso);
        }
        public void verify(CaptureRequest request,TotalCaptureResult result) {
            ImageFrame.NiceCaptureTag tag=(ImageFrame.NiceCaptureTag)request.getTag();
            int slot;
            switch(tag.role) {
                case NORMAL:slot=0;break;
                case SHORT:slot=1;break;
                case EXTRA_SHORT:slot=2;break;
                case LONG:slot=3;break;
                default:throw new IllegalStateException("SCAM HDR: неизвестная роль RAW");
            }
            verifyExposure(result,slot,BRACKET_TOLERANCE_EV);
        }
        public void verifyZslNormal(CaptureResult result,long shutterTimestamp) {
            Long timestamp=result==null?null:result.get(CaptureResult.SENSOR_TIMESTAMP);
            if(timestamp==null || timestamp<=0 || timestamp>shutterTimestamp)
                throw new IllegalStateException("SCAM HDR ZSL: RAW не предшествует нажатию");
            verifyExposure(result,0,ZSL_TOLERANCE_EV);
        }
        /**
         * Bracket frames (L/S/ES): below ~0.2 ms the sensor quantizes the shutter to
         * lines and makes up the rest with gain (e.g. 43 us ISO 72 -> ISO 86), so the
         * split and a few tenths of an EV of the product differ from the plan. The
         * pipeline uses the measured exposure, so only a frame that is clearly not
         * the requested one (another AE state) is rejected.
         */
        private static final double BRACKET_TOLERANCE_EV=0.4;
        /**
         * ZSL N: the preview AE and the plan can split the same product differently
         * (gain 1.458 vs 1.43 with a 2% shorter shutter, 0.00 EV). Rejecting those
         * threw away all 20 buffered RAWs and re-shot N after the press (+0.5 s).
         */
        private static final double ZSL_TOLERANCE_EV=0.05;
        private void verifyExposure(CaptureResult result,int slot,double tolerance) {
            if(camera2Domain) {
                Long ns=result.get(CaptureResult.SENSOR_EXPOSURE_TIME);Integer sensitivity=result.get(CaptureResult.SENSOR_SENSITIVITY);
                if(ns==null||sensitivity==null||ns<=0||sensitivity<=0)throw new IllegalStateException("SCAM HDR: нет экспозиции Camera2 в результате");
                boolean exact=Math.abs((double)ns/shutter[slot]-1.)<=.015 && Math.abs(sensitivity-iso[slot])<=Math.max(2,iso[slot]*.015);
                if(exact)return;
                double ev=Math.abs(Math.log((double)ns*sensitivity/((double)shutter[slot]*iso[slot]))/Math.log(2));
                String detail=" (slot "+slot+": ISO "+sensitivity+"/"+iso[slot]+", shutter "+ns+"/"+shutter[slot]
                        +String.format(java.util.Locale.ROOT,", %.2f EV)",ev);
                if(ev>tolerance)
                    throw new IllegalStateException("SCAM HDR: выдержка/ISO RAW не совпали с планом SCAMERA"+detail);
                android.util.Log.w("NICE_CAPTURE","sensor rounding accepted"+detail);
                return;
            }
            float[] aec=result.get(AEC);
            if(aec==null||aec.length<35||!Float.isFinite(aec[2])||!Float.isFinite(aec[14])||aec[2]<=0||aec[14]<=0)
                throw new IllegalStateException("SCAM HDR: измеренный vendor gain отсутствует");
            double actual=(double)aec[2]*aec[14];
            boolean exact=Math.abs(actual/product[slot]-1.)<=.015 && Math.abs((double)aec[2]/gain[slot]-1.)<=.015
                    && Math.abs((double)aec[14]/shutter[slot]-1.)<=.015;
            if(exact)return;
            double ev=Math.abs(Math.log(actual/product[slot])/Math.log(2));
            String detail=" (slot "+slot+": gain "+aec[2]+"/"+gain[slot]+", shutter "+aec[14]+"/"+shutter[slot]
                    +String.format(java.util.Locale.ROOT,", %.2f EV)",ev);
            if(ev>tolerance)
                throw new IllegalStateException("SCAM HDR: выдержка/gain RAW не совпали со стоковым планом"+detail);
            android.util.Log.w("NICE_CAPTURE","sensor rounding accepted"+detail);
        }
    }
}

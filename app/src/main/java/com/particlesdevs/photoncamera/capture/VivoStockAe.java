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
    private final Context context;
    public final int generation;
    private final LinkedHashMap<Long,Preview> previews=new LinkedHashMap<>();
    private final LinkedHashMap<Long,Sample> snapshots=new LinkedHashMap<>();
    private final HashMap<Integer,Sample> pending=new HashMap<>();
    private volatile boolean closed;
    private Process process;
    private Plan latest;
    private String failure="Ожидание стокового AE";
    private static native byte[] nativeSolve(byte[] snapshot);

    public VivoStockAe(Context context,int generation,String cameraId) {
        this.context=context.getApplicationContext();this.generation=generation;
        // Calibration was measured separately on all three rear RAW cameras.
        if(!"PD2454".equals(android.os.Build.DEVICE) ||
                !("3".equals(cameraId)||"4".equals(cameraId)||"5".equals(cameraId))) {
            failure="Стоковый AE: gain-калибровка этой камеры ещё не проверена";return;
        }
        Thread worker=new Thread(this::run,"SCAMERA-stock-AE");worker.setDaemon(true);worker.start();
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
            Plan next=new Plan(sample.plan,id,preview.timestamp,preview.received,generation);
            if(latest==null || latest.timestamp<next.timestamp){latest=next;failure=null;}
        }catch(Exception invalid){failure=invalid.toString();}
    }
    public synchronized Plan freeze() {
        if(closed || latest==null || SystemClock.elapsedRealtimeNanos()-latest.received>800_000_000L)
            throw new IllegalStateException(failure==null?"Стоковый AE устарел":failure);
        return latest;
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
            pending.put(id,new Sample(e));return;
        }
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
    private File asset(String name,boolean gzip)throws Exception {
        File dir=new File(context.getFilesDir(),"stock-ae");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("AE directory");
        File output=new File(dir,gzip?"frida-inject":name);
        if(gzip && injectorMatches(output))return output;
        File staged=File.createTempFile("ae-", ".tmp",dir);
        try(InputStream raw=context.getAssets().open("vivo-aec/"+name);
            InputStream in=gzip?new GZIPInputStream(raw):raw;OutputStream out=new FileOutputStream(staged)) {
            byte[] chunk=new byte[65536];int n;while((n=in.read(chunk))>=0)out.write(chunk,0,n);
        }
        if(gzip && !injectorMatches(staged)){staged.delete();throw new IOException("AE injector checksum");}
        if(!staged.renameTo(output)){staged.delete();throw new IOException("AE asset publication");}
        return output;
    }
    private void run() {
        while(!closed) {
            observeOnce();
            if(!closed)try{Thread.sleep(1000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}
        }
    }
    private void observeOnce() {
        try {
            System.loadLibrary("vivoAe");
            File injector=asset("frida-inject.gz",true),script=asset("observer.js",false),policy=asset("policy-fix.sh",false),runner=asset("observer-run.sh",false);
            String command="set -e; test \"$(sha256sum /vendor/lib64/camera/components/com.vivo.stats.aec.so | cut -d ' ' -f 1)\" = "+DONOR
                +"; p=$(pidof vendor.qti.camera.provider-service_64); case \"$p\" in ''|*[!0-9]*) exit 21;; esac; "
                +"sh "+quote(policy.getAbsolutePath())+" \"$p\" >&2; chmod 700 "+quote(injector.getAbsolutePath())
                +"; exec sh "+quote(runner.getAbsolutePath())+" "+quote(injector.getAbsolutePath())+" \"$p\" "
                +quote(script.getAbsolutePath())+" "+quote(injector.getParent());
            Process child=new ProcessBuilder("su","-c",command).start();
            synchronized(this){process=child;if(closed){child.getOutputStream().close();return;}}
            Thread errors=new Thread(()->{try(BufferedReader r=new BufferedReader(new InputStreamReader(child.getErrorStream()))){String line;while((line=r.readLine())!=null)android.util.Log.w("NICE_AE",line);}catch(IOException ignored){}},"SCAMERA-AE-errors");errors.setDaemon(true);errors.start();
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(child.getInputStream()))) {
                String line;while(!closed && (line=reader.readLine())!=null) {
                    if(line.length()>262144)throw new IOException("AE event extent");
                    if(line.startsWith("SCAMERA_AE_CONTEXT ")) {
                        JSONObject message=new JSONObject(line.substring(19));
                        if("finished".equals(message.optString("event")))throw new IOException("AE observer interval ended");
                        try{event(message);}
                        catch(Exception invalid){synchronized(this){failure=invalid.toString();latest=null;pending.clear();}}
                    }
                }
            }
        }catch(Exception|LinkageError error){synchronized(this){failure="Стоковый AE: "+error;latest=null;}}
        finally{synchronized(this){latest=null;pending.clear();snapshots.clear();previews.clear();}closeProcess();}
    }
    private synchronized void closeProcess(){if(process!=null)try{process.getOutputStream().close();}catch(IOException ignored){}process=null;}
    @Override public synchronized void close(){closed=true;latest=null;pending.clear();snapshots.clear();previews.clear();closeProcess();}

    public static final class Plan {
        public final long frameId,timestamp;final long received;public final int generation;
        private final long[] shutter=new long[4];private final int[] iso=new int[4];private final double[] product=new double[4];
        Plan(byte[] data,long frameId,long timestamp,long received,int generation)throws IOException {
            this.frameId=frameId;this.timestamp=timestamp;this.received=received;this.generation=generation;
            ByteBuffer p=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            for(int i=0;i<4;i++) {
                float ns=p.getFloat(i*16),gain=p.getFloat(i*16+4);
                if(!Float.isFinite(ns)||!Float.isFinite(gain)||ns<=0||gain<=0)throw new IOException("Invalid stock exposure");
                shutter[i]=Math.round((double)ns);iso[i]=(int)Math.round((double)gain*50.);product[i]=(double)ns*gain;
            }
            if(!(product[2]<product[1]&&product[1]<product[0]&&product[0]<=product[3]))throw new IOException("Stock scene has no distinct S/ES");
        }
        private static int slot(int index){if(index<0||index>=7)throw new IllegalArgumentException("NICE request index");return index<4?0:index==4?3:index==5?1:2;}
        public long shutter(int index){return shutter[slot(index)];}
        public int iso(int index){return iso[slot(index)];}
        public ImageFrame.CaptureRole role(int index){return index<4?ImageFrame.CaptureRole.NORMAL:index==4?ImageFrame.CaptureRole.LONG:index==5?ImageFrame.CaptureRole.SHORT:ImageFrame.CaptureRole.EXTRA_SHORT;}
        public void apply(CaptureRequest.Builder builder,int index,CameraCharacteristics characteristics) {
            int slot=slot(index);
            android.util.Range<Long> times=characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            android.util.Range<Integer> gains=characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if(times==null||gains==null||!times.contains(shutter[slot])||!gains.contains(iso[slot]))throw new IllegalStateException("Стоковая экспозиция вне диапазона Camera2");
            builder.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_OFF);
            builder.set(CaptureRequest.CONTROL_AE_LOCK,false);builder.set(CaptureRequest.CONTROL_ENABLE_ZSL,false);
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME,shutter[slot]);builder.set(CaptureRequest.SENSOR_SENSITIVITY,iso[slot]);
        }
        public void verify(CaptureRequest request,TotalCaptureResult result) {
            ImageFrame.NiceCaptureTag tag=(ImageFrame.NiceCaptureTag)request.getTag();int slot=slot(tag.index);
            float[] aec=result.get(AEC);
            if(aec==null||aec.length<35||!Float.isFinite(aec[2])||!Float.isFinite(aec[14])||aec[2]<=0||aec[14]<=0)
                throw new IllegalStateException("NICE: измеренный vendor gain отсутствует");
            double actual=(double)aec[2]*aec[14];
            if(Math.abs(actual/product[slot]-1.)>.015)throw new IllegalStateException("NICE: измеренная экспозиция не совпала со стоковым планом");
        }
    }
}

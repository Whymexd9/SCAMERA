#!/usr/bin/env python3
"""ARM64 oracle -> portable query builder -> Java -> stub Camera2 request.

Checks cross-language values and scheduling, not HAL support or device capture.
"""
from pathlib import Path
import argparse
import subprocess
import sys
import tempfile

ROOT=Path(__file__).resolve().parents[1]
STUB=r'''package android.hardware.camera2;
import java.util.*;
public class CaptureRequest {
 public static class Key<T> {
  public final String name; public final Class<T> type;
  public Key(String n,Class<T> t){name=n;type=t;}
 }
 public static final Key<Boolean> CONTROL_ENABLE_ZSL=new Key<>("android.control.enableZsl",Boolean.class);
 public static final Key<Boolean> CONTROL_AE_LOCK=new Key<>("android.control.aeLock",Boolean.class);
 public static class Builder {
  public final Map<String,Object> fields=new HashMap<>();
  public String reject;
  public <T> void set(Key<T> key,T value) {
   if(key.name.equals(reject))throw new IllegalArgumentException("Unsupported vendor key");
   if(!key.type.isInstance(value))throw new AssertionError("Incorrect key type");
   fields.put(key.name,value);
  }
 }
}'''
RESULT_STUB=r'''package android.hardware.camera2;
import java.util.*;
public class CaptureResult {
 public static class Key<T> {
  private final String name;
  public Key(String n,Class<T> t){name=n;}
  public String getName(){return name;}
 }
 public static final Key<Long> SENSOR_TIMESTAMP=new Key<>("android.sensor.timestamp",Long.class);
 public final Map<Key<?>,Object> fields=new LinkedHashMap<>();
 public long frame=17; public int sequence=4; public CaptureRequest request;
 public long getFrameNumber(){return frame;}
 public int getSequenceId(){return sequence;}
 public CaptureRequest getRequest(){return request;}
 public List<Key<?>> getKeys(){return new ArrayList<>(fields.keySet());}
 @SuppressWarnings("unchecked") public <T>T get(Key<T> k){
  for(var entry:fields.entrySet())if(entry.getKey().getName().equals(k.getName()))return (T)entry.getValue();
  return null;
 }
 public <T>void put(String name,Class<T> type,T value){
  fields.keySet().removeIf(k->k.getName().equals(name));fields.put(new Key<>(name,type),value);
 }
}'''
TOTAL_STUB=r'''package android.hardware.camera2;
import java.util.*;
public class TotalCaptureResult extends CaptureResult {
 public final List<CaptureResult> partials=new ArrayList<>();
 public List<CaptureResult> getPartialResults(){return partials;}
}'''
CHECK=r'''import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import com.particlesdevs.photoncamera.capture.VivoNiceRequestPlan;
import com.particlesdevs.photoncamera.capture.VivoNiceAeSnapshot;
import java.nio.*; import java.nio.file.*; import java.util.*;
public class Check {
 static final String P="vivo.parameter.", A=P+"VivoAlgoAECFrameControl", C=P+"VivoAlgoCaptureFrameControl";
 static void check(boolean yes){if(!yes)throw new AssertionError();}
 static void rejected(Runnable r){try{r.run();}catch(IllegalArgumentException|IndexOutOfBoundsException ok){return;}
  throw new AssertionError("Invalid input accepted");}
 static TotalCaptureResult metadata(byte[] bytes) {
  var r=new TotalCaptureResult();r.request=new CaptureRequest();
  r.put("android.sensor.timestamp",Long.class,700L);
  ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
  for(String name:new String[]{"VivoAlgoAECFrameControl","VivoAlgoAECShortFrameControl"}){
   float[] a=new float[name.contains("Short")?49:48];
   for(int i=0;i<a.length;i++)a[i]=Float.intBitsToFloat(b.getInt());
   r.put(P+name,float[].class,a);
  }
  String[] names={"VivoAlgoCaptureFrameControl","rawHDRParams","niceHdrExpEVMode"};
  int[] sizes={9,3,2};
  for(int i=0;i<names.length;i++){
   int[] a=new int[sizes[i]];for(int j=0;j<a.length;j++)a[j]=b.getInt();
   r.put(P+names[i],int[].class,a);
  }
  r.put(P+"rawHDRCaptureDrcGain",Float.class,b.getFloat());return r;
 }
 static void verifyAe(byte[] bytes) {
  var r=metadata(bytes);var snapshot=VivoNiceAeSnapshot.read(r,8);
  check(snapshot.frameNumber==17&&snapshot.timestamp==700&&snapshot.sessionGeneration==8);
  check(Arrays.equals(bytes,snapshot.plan.copyPayload()));
  var split=new TotalCaptureResult();split.request=r.request;
  split.put("android.sensor.timestamp",Long.class,700L);
  var first=new CaptureResult();first.request=r.request;
  var second=new CaptureResult();second.request=r.request;
  int index=0;
  for(var entry:r.fields.entrySet())(index++%2==0?first:second).fields.put(entry.getKey(),entry.getValue());
  split.partials.add(first);split.partials.add(second);
  check(Arrays.equals(bytes,VivoNiceAeSnapshot.read(split,8).plan.copyPayload()));
  first.frame++;rejected(()->VivoNiceAeSnapshot.read(split,8));first.frame--;
  first.sequence++;rejected(()->VivoNiceAeSnapshot.read(split,8));first.sequence--;
  first.request=new CaptureRequest();rejected(()->VivoNiceAeSnapshot.read(split,8));first.request=r.request;
  first.put("android.sensor.timestamp",Long.class,701L);rejected(()->VivoNiceAeSnapshot.read(split,8));
  first.put("android.sensor.timestamp",Long.class,700L);
  split.put(P+"rawHDRCaptureDrcGain",Float.class,3.25f);
  byte[] changed=bytes.clone();ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN).putFloat(444,3.25f);
  check(Arrays.equals(changed,VivoNiceAeSnapshot.read(split,8).plan.copyPayload()));
  split.put(P+"rawHDRCaptureDrcGain",Float.class,Float.NaN);
  rejected(()->VivoNiceAeSnapshot.read(split,8));
  r.put(A,float[].class,new float[47]);rejected(()->VivoNiceAeSnapshot.read(r,8));
  check(Arrays.equals(bytes,snapshot.plan.copyPayload()));
  for(var entry:first.fields.entrySet())if(entry.getValue() instanceof float[])
   Arrays.fill((float[])entry.getValue(),0f);
  check(Arrays.equals(bytes,snapshot.plan.copyPayload()));
  var missing=metadata(bytes);missing.fields.keySet().removeIf(k->k.getName().equals(P+"rawHDRParams"));
  rejected(()->VivoNiceAeSnapshot.read(missing,8));
  var wrong=metadata(bytes);wrong.put(P+"rawHDRParams",Integer[].class,new Integer[]{1,2,3});
  rejected(()->VivoNiceAeSnapshot.read(wrong,8));
 }
 static int verify(byte[] bytes) {
  verifyAe(bytes);
  ByteBuffer in=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
  // Nonzero buffer position, read-only storage and caller byte order are safe.
  ByteBuffer source=ByteBuffer.allocate(456);source.position(8);source.put(bytes);source.position(8);
  var plan=VivoNiceRequestPlan.decode(source.asReadOnlyBuffer());
  check(source.position()==8 && Arrays.equals(bytes,plan.copyPayload()));
  Arrays.fill(source.array(),(byte)0); // Must not retain the producer buffer.
  byte[] copy=plan.copyPayload();copy[0]^=1;check(Arrays.equals(bytes,plan.copyPayload()));
  int past=in.getInt(388),future=in.getInt(392);
  check(plan.pastCount==past && plan.futureCount==future && plan.frameCount==past+future);
  CaptureRequest.Builder previous=null;
  for(int i=0;i<plan.frameCount;++i){
   var builder=new CaptureRequest.Builder();plan.applyLegacyExposureFields(builder,i);
   check(builder.fields.size()==6);
   float[] a=(float[])builder.fields.get(A);
   for(int j=0;j<48;++j)check(Float.floatToRawIntBits(a[j])==in.getInt(j*4));
   int[] c=(int[])builder.fields.get(C);
   for(int j=0;j<9;++j)check(c[j]==in.getInt(388+j*4));
   int[] raw=(int[])builder.fields.get(P+"rawHDRParams");
   for(int j=0;j<3;++j)check(raw[j]==in.getInt(424+j*4));
   Integer[] counts=(Integer[])builder.fields.get("vivo.control.RequestLeftInThisSnapshot");
   check(Arrays.equals(counts,i==0?new Integer[]{past,future}:new Integer[]{0,0}));
   check(builder.fields.get("android.control.enableZsl").equals(i<past));
   check(builder.fields.get("android.control.aeLock").equals(false));
   check(plan.usesZsl(i)==(i<past));
   if(previous!=null)check(previous.fields.get(A)!=a && previous.fields.get(C)!=c);
   // Neither another builder nor a mutation can change the frozen plan.
   a[0]=-23;c[0]=-12;raw[0]=-42;previous=builder;
  }
  check(Arrays.equals(bytes,plan.copyPayload()));
  var untouched=new CaptureRequest.Builder();
  rejected(()->plan.applyLegacyExposureFields(untouched,-1));
  rejected(()->plan.applyLegacyExposureFields(untouched,plan.frameCount));
  check(untouched.fields.isEmpty());
  var unsupported=new CaptureRequest.Builder();unsupported.reject=C;
  rejected(()->plan.applyLegacyExposureFields(unsupported,0));
  return plan.frameCount;
 }
 public static void main(String[] args)throws Exception {
  byte[] all=Files.readAllBytes(Path.of(args[0]));check(all.length%448==0);
  int frames=0;
  for(int offset=0;offset<all.length;offset+=448)frames+=verify(Arrays.copyOfRange(all,offset,offset+448));
  byte[] base=Arrays.copyOf(all,448);
  // Inactive opaque cells and the integer short-AEC tail survive bit for bit.
  ByteBuffer b=ByteBuffer.wrap(base).order(ByteOrder.LITTLE_ENDIAN);
  b.putInt(4*15,0x7fc12345);b.putInt(384,0x7fc54321);verify(base);
  for(int[] change:new int[][]{{388,-1},{388,17},{392,17},{388,16},{396,1},{444,0x7f800000},
       {0,0x7fc00000},{192+64,0x7f800000}}){
   byte[] bad=base.clone();ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(change[0],change[1]);
   rejected(()->VivoNiceRequestPlan.decode(ByteBuffer.wrap(bad)));
  }
  rejected(()->VivoNiceRequestPlan.decode(null));
  rejected(()->VivoNiceRequestPlan.decode(ByteBuffer.allocate(447)));
  rejected(()->VivoNiceRequestPlan.decode(ByteBuffer.allocate(449)));
  rejected(()->VivoNiceAeSnapshot.read(null,8));
  var noTimestamp=metadata(base);noTimestamp.put("android.sensor.timestamp",Long.class,0L);
  rejected(()->VivoNiceAeSnapshot.read(noTimestamp,8));
  System.out.println("PASS: "+all.length/448+" native plans -> "+frames+" stub Camera2 requests; raw bits, order, isolation and failures verified");
  System.out.println("PASS: same-frame AE snapshots, partial results, final overrides, stale identity and missing/invalid fields");
  System.out.println("No Camera2 session, VCF execution, model dispatch or TCE image call tested.");
 }
}'''

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('library',type=Path)
    args=ap.parse_args()
    with tempfile.TemporaryDirectory(prefix='nice-android-request-') as directory:
        tmp=Path(directory)
        subprocess.run([sys.executable,str(ROOT/'tools/check_vivo_nice_request.py'),
                        str(args.library),'--payloads',str(tmp/'payloads')],check=True)
        stub=tmp/'android/hardware/camera2/CaptureRequest.java'
        stub.parent.mkdir(parents=True);stub.write_text(STUB)
        result_stub=stub.with_name('CaptureResult.java');result_stub.write_text(RESULT_STUB)
        total_stub=stub.with_name('TotalCaptureResult.java');total_stub.write_text(TOTAL_STUB)
        check=tmp/'Check.java';check.write_text(CHECK)
        source=ROOT/'app/src/main/java/com/particlesdevs/photoncamera/capture/VivoNiceRequestPlan.java'
        ae_source=source.with_name('VivoNiceAeSnapshot.java')
        subprocess.run(['java','-m','jdk.compiler/com.sun.tools.javac.Main','-d',str(tmp),
                        str(stub),str(result_stub),str(total_stub),str(source),str(ae_source),str(check)],check=True)
        subprocess.run(['java','-cp',str(tmp),'Check',str(tmp/'payloads')],check=True)

if __name__=='__main__':main()

#!/usr/bin/env python3
from pathlib import Path
import ast
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
tree = ast.parse((ROOT/'tools/check_vivo_vcf2_request.py').read_text())
stubs = ast.literal_eval(next(n.value for n in tree.body if isinstance(n, ast.Assign)
                            and any(isinstance(t, ast.Name) and t.id == 'STUBS' for t in n.targets)))
request = stubs['android/hardware/camera2/CaptureRequest.java'].replace(
    'public final Map<String,Object> fields;', '''
 public static final Key<Integer> CONTROL_CAPTURE_INTENT=new Key<>("intent",Integer.class);
 public static final Key<Integer> CONTROL_AF_TRIGGER=new Key<>("afTrigger",Integer.class);
 public static final Key<Integer> CONTROL_AE_PRECAPTURE_TRIGGER=new Key<>("aeTrigger",Integer.class);
 public static final int CONTROL_CAPTURE_INTENT_STILL_CAPTURE=2, CONTROL_AF_TRIGGER_IDLE=0,
  CONTROL_AE_PRECAPTURE_TRIGGER_IDLE=0;
 @SuppressWarnings("unchecked") public <T>T get(Key<T> key){return (T)fields.get(key.name);}
 @SuppressWarnings({"unchecked","rawtypes"}) public List<Key<?>> getKeys(){
  var keys=new ArrayList<Key<?>>();fields.forEach((n,v)->keys.add(new Key(n,v.getClass())));return keys;
 }
 public final Map<String,Object> fields;''')
CHECK = '''import android.hardware.camera2.CaptureRequest;
import com.particlesdevs.photoncamera.capture.VivoVcf2PhotoProfile;import java.util.*;
public class Check {
 static int checks;static void check(boolean yes){checks++;if(!yes)throw new AssertionError();}
 public static void main(String[] args){
  var b=new CaptureRequest.Builder();
  b.fields.put("vivo.control.sensorMode",7);b.fields.put("android.sensor.pixelMode",1);
  b.fields.put("android.sensor.exposureTime",30000000L);b.fields.put("android.sensor.sensitivity",200);
  var original=new HashMap<>(b.fields);
  VivoVcf2PhotoProfile.session(b,4096,3072,"3",false);
  check(!b.fields.containsKey("vcf.parameter.SnapshotJpegStreamMap"));
  check(Arrays.equals((Integer[])b.fields.get("vivo.control.streamsUsage"),new Integer[]{2,1,0}));
  VivoVcf2PhotoProfile.session(b,4096,3072,"3",true);
  check(Arrays.equals((int[])b.fields.get("vcf.parameter.SnapshotJpegStreamMap"),new int[]{4096,3072,3}));
  VivoVcf2PhotoProfile.repeating(b);
  for(var e:original.entrySet())check(b.fields.get(e.getKey()).equals(e.getValue()));
  check(b.fields.get("vivo.capability.capture.rawHdr").equals(1));
  check(b.fields.get("vivo.capability.capture.hdr").equals(1));
  check(b.fields.get("vivo.capability.capture.nice").equals(1));
  b.set(CaptureRequest.CONTROL_AF_TRIGGER,1);b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,1);
  var still=new CaptureRequest.Builder();VivoVcf2PhotoProfile.copyPreview(b.build(),still);
  for(var e:original.entrySet())check(still.fields.get(e.getKey()).equals(e.getValue()));
  check(still.get(CaptureRequest.CONTROL_CAPTURE_INTENT)==2);
  check(still.get(CaptureRequest.CONTROL_AF_TRIGGER)==0);
  check(still.get(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER)==0);
  check(!still.fields.containsKey("vivo.parameter.VivoAlgoCaptureFrameControl"));
  System.out.println("PASS: "+checks+" VCF2 stream/profile/request-preservation checks; host stubs");
 }
}'''
with tempfile.TemporaryDirectory(prefix='vcf2-profile-') as directory:
    root = Path(directory)
    target = root/'android/hardware/camera2/CaptureRequest.java'
    target.parent.mkdir(parents=True)
    target.write_text(request)
    (root/'Check.java').write_text(CHECK)
    sources = [ROOT/'app/src/main/java/com/particlesdevs/photoncamera/capture'/name
               for name in ('VivoNicePreview.java', 'VivoVcf2PhotoProfile.java')]
    subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-d', directory,
                    str(target), str(root/'Check.java'), *map(str, sources)], check=True)
    subprocess.run(['java', '-cp', directory, 'Check'], check=True)

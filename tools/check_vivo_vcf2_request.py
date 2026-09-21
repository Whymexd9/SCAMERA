#!/usr/bin/env python3
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
'android/os/Handler.java': 'package android.os; public class Handler {}',
'android/hardware/camera2/CameraAccessException.java': 'package android.hardware.camera2; public class CameraAccessException extends Exception {}',
'android/hardware/camera2/CaptureRequest.java': '''package android.hardware.camera2;
import java.util.*;
public class CaptureRequest {
 public static class Key<T>{public final String name; public final Class<T> type;
  public Key(String n,Class<T> t){name=n;type=t;}}
 public final Map<String,Object> fields;
 CaptureRequest(Map<String,Object> f){fields=Map.copyOf(f);}
 public static class Builder {
  public final Map<String,Object> fields=new HashMap<>(); public String reject;
  public <T>void set(Key<T> key,T value){if(key.name.equals(reject))throw new IllegalArgumentException();
   if(!key.type.isInstance(value))throw new AssertionError();fields.put(key.name,value);}
  @SuppressWarnings("unchecked") public <T>T get(Key<T> key){return (T)fields.get(key.name);}
  public CaptureRequest build(){return new CaptureRequest(fields);}
 }
}''',
'android/hardware/camera2/CameraCaptureSession.java': '''package android.hardware.camera2;
import java.util.*; import android.os.Handler;
public class CameraCaptureSession {
 public static class CaptureCallback {}
 public int calls; public boolean fail; public List<CaptureRequest> submitted;
 public int captureBurst(List<CaptureRequest> requests,CaptureCallback callback,Handler handler)throws CameraAccessException{
  calls++;submitted=requests;if(fail)throw new CameraAccessException();return 23;
 }
}'''
}
CHECK=r'''import android.hardware.camera2.*; import android.os.Handler;
import com.particlesdevs.photoncamera.capture.VivoVcf2Request;
public class Check {
 static int checks; static final long ID=0x123456789abcdefL;
 interface Task{void run()throws Exception;}
 static void check(boolean yes){checks++;if(!yes)throw new AssertionError();}
 static void rejects(Task task)throws Exception {
  try{task.run();}catch(IllegalArgumentException|IllegalStateException|CameraAccessException expected){checks++;return;}
  throw new AssertionError("invalid transaction accepted");
 }
 public static void main(String[] args)throws Exception {
  var h=new Handler();var callback=new CameraCaptureSession.CaptureCallback();
  var session=new CameraCaptureSession();var builder=new CaptureRequest.Builder();
  builder.set(new CaptureRequest.Key<>("verified.template.field",Integer.class),8);
  var request=VivoVcf2Request.prepare(builder,ID,true);
  check(!request.acceptsVifCallback(ID));
  builder.fields.clear();check(request.submit(session,callback,h)==23);
  check(session.calls==1&&session.submitted.size()==1);
  var fields=session.submitted.get(0).fields;
  check(fields.size()==3&&fields.get("verified.template.field").equals(8));
  check(fields.get("vivo.capability.capture.id").equals(ID));
  check(fields.get("vivo.control.globalCaptureId").equals(ID));
  check(request.acceptsVifCallback(ID)&&!request.acceptsVifCallback(ID+1));
  rejects(()->request.submit(session,callback,h));check(session.calls==1);
  request.close();check(!request.acceptsVifCallback(ID));rejects(()->request.submit(session,callback,h));
  var disabled=VivoVcf2Request.prepare(new CaptureRequest.Builder(),ID+1,false);
  disabled.submit(session,callback,h);
  check(!disabled.acceptsVifCallback(ID+1));
  check(session.submitted.get(0).fields.get("vivo.capability.capture.id").equals(0L));
  check(session.submitted.get(0).fields.get("vivo.control.globalCaptureId").equals(ID+1));
  var failed=VivoVcf2Request.prepare(new CaptureRequest.Builder(),ID+2,true);session.fail=true;
  rejects(()->failed.submit(session,callback,h));check(!failed.acceptsVifCallback(ID+2));
  int calls=session.calls;rejects(()->failed.submit(session,callback,h));check(session.calls==calls);
  session.fail=false;
  var cancelled=VivoVcf2Request.prepare(new CaptureRequest.Builder(),ID+3,true);cancelled.close();
  rejects(()->cancelled.submit(session,callback,h));check(session.calls==calls);
  var legacy=new CaptureRequest.Builder();
  legacy.set(new CaptureRequest.Key<>("vivo.parameter.VivoAlgoCaptureFrameControl",int[].class),new int[]{4,3});
  rejects(()->VivoVcf2Request.prepare(legacy,ID,true));check(legacy.fields.size()==1);
  legacy.fields.clear();legacy.set(new CaptureRequest.Key<>("vivo.control.RequestLeftInThisSnapshot",Integer[].class),new Integer[]{4,3});
  rejects(()->VivoVcf2Request.prepare(legacy,ID,true));check(legacy.fields.size()==1);
  rejects(()->VivoVcf2Request.prepare(new CaptureRequest.Builder(),0,true));
  rejects(()->VivoVcf2Request.prepare(new CaptureRequest.Builder(),-1,true));
  rejects(()->VivoVcf2Request.prepare(null,ID,true));
  var unsupported=new CaptureRequest.Builder();unsupported.reject="vivo.control.globalCaptureId";
  rejects(()->VivoVcf2Request.prepare(unsupported,ID,true));check(session.calls==calls);
  var ready=VivoVcf2Request.prepare(new CaptureRequest.Builder(),ID+4,true);
  rejects(()->ready.submit(null,callback,h));check(ready.submit(session,callback,h)==23);
  System.out.println("PASS: "+checks+" VCF2 single-request/identity checks; host stubs, no HAL capture");
 }
}'''
with tempfile.TemporaryDirectory(prefix='vcf2-request-') as directory:
    root = Path(directory)
    for name, source in {**STUBS, 'Check.java': CHECK}.items():
        path = root/name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source)
    production = ROOT/'app/src/main/java/com/particlesdevs/photoncamera/capture/VivoVcf2Request.java'
    subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-d', directory,
                    *map(str, root.rglob('*.java')), str(production)], check=True)
    subprocess.run(['java', '-cp', directory, 'Check'], check=True)

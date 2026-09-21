#!/usr/bin/env python3
from pathlib import Path
import ast
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
tree = ast.parse((ROOT/'tools/check_vivo_vcf2_request.py').read_text())
STUBS = ast.literal_eval(next(n.value for n in tree.body if isinstance(n, ast.Assign)
                             and any(isinstance(t, ast.Name) and t.id == 'STUBS' for t in n.targets)))
STUBS.update({
'android/content/Context.java': 'package android.content; public class Context {}',
'android/os/Handler.java': '''package android.os;
import java.util.*;
public class Handler {
 public boolean reject; public List<Runnable> delayed=new ArrayList<>();
 public boolean postDelayed(Runnable r,long delay){if(reject)return false;delayed.add(r);return true;}
 public void removeCallbacks(Runnable r){delayed.remove(r);}
 public void expire(){for(Runnable r:new ArrayList<>(delayed))r.run();}
}''',
'android/hardware/camera2/CaptureResult.java': '''package android.hardware.camera2;
public class CaptureResult {
 public static class Key<T>{}
 public static final Key<Long> SENSOR_TIMESTAMP=new Key<>(); public Long timestamp=777L;
 @SuppressWarnings("unchecked") public <T>T get(Key<T> key){return (T)timestamp;}
}''',
'com/particlesdevs/photoncamera/capture/VivoVcf2Device.java': '''package com.particlesdevs.photoncamera.capture;
import android.content.Context; import android.os.Handler; import android.hardware.camera2.CaptureResult;
import java.io.IOException;
public class VivoVcf2Device {
 public interface Listener {
  void onBuffer(Buffer b);void onResult(long id,CaptureResult r,boolean partial);
  void onCaptureFrameDone(long id);void onDeviceUpdate(int s);void onError(int e);
  void onInfo(long id,int t,int v);void onVopCaptureDone(int r,long id,int a,int b,int c);void onNotify(int a,int b);
 }
 public static class Buffer {
  public long captureId;public int reads;public boolean fail;
  public byte[] bytes={-1,-40,-1,1,2,3};
  public Buffer(long id){captureId=id;}
  public byte[] copyJpegBytes()throws IOException{reads++;if(fail)throw new IOException("map failed");return bytes;}
 }
 public static VivoVcf2Device last;public Listener listener;public boolean closed;
 public static VivoVcf2Device open(Context c,Handler h,Listener l){last=new VivoVcf2Device();last.listener=l;return last;}
 public void close()throws ReflectiveOperationException{closed=true;}
}'''
})
CHECK = '''import android.content.Context; import android.os.Handler; import android.hardware.camera2.*;
import com.particlesdevs.photoncamera.capture.*; import java.util.*;
public class Check implements VivoVcf2Capture.Listener {
 static int checks;int done,failed;long lastId;byte[] bytes;CaptureResult metadata;
 public void onComplete(long id,byte[] jpeg,CaptureResult r){done++;lastId=id;bytes=jpeg;metadata=r;}
 public void onFailure(long id,String reason){failed++;lastId=id;}
 static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
 interface Task{void run()throws Exception;}
 static void rejects(Task task)throws Exception {try{task.run();}catch(IllegalStateException|CameraAccessException e){checks++;return;}throw new AssertionError();}
 public static void main(String[] args)throws Exception {
  var listener=new Check();var handler=new Handler();var session=new CameraCaptureSession();
  var callback=new CameraCaptureSession.CaptureCallback();var capture=new VivoVcf2Capture(new Context(),handler,listener);
  var result=new CaptureResult();
  capture.start(new CaptureRequest.Builder(),1,session,callback);
  check(session.submitted.size()==1&&handler.delayed.size()==1);
  rejects(()->capture.start(new CaptureRequest.Builder(),2,session,callback));
  var foreign=new VivoVcf2Device.Buffer(9);capture.onBuffer(foreign);check(foreign.reads==0);
  capture.onResult(9,result,false);capture.onResult(1,result,true);capture.onCaptureFrameDone(1);
  check(listener.done==0);
  var buffer=new VivoVcf2Device.Buffer(1);capture.onBuffer(buffer);capture.onBuffer(buffer);
  check(buffer.reads==1&&listener.done==0);
  capture.onResult(1,result,false);check(listener.done==1&&listener.lastId==1);
  check(listener.bytes==buffer.bytes&&listener.metadata==result&&handler.delayed.isEmpty());
  capture.onResult(1,result,false);capture.onBuffer(buffer);check(listener.done==1&&buffer.reads==1);
  capture.start(new CaptureRequest.Builder(),2,session,callback);capture.onResult(2,result,false);
  capture.onBuffer(new VivoVcf2Device.Buffer(2));check(listener.done==2);
  capture.start(new CaptureRequest.Builder(),3,session,callback);handler.expire();
  check(listener.failed==1&&handler.delayed.isEmpty());
  capture.onBuffer(new VivoVcf2Device.Buffer(3));check(listener.done==2);
  capture.start(new CaptureRequest.Builder(),4,session,callback);var invalid=new CaptureResult();invalid.timestamp=0L;
  capture.onResult(4,invalid,false);check(listener.failed==2);
  capture.start(new CaptureRequest.Builder(),5,session,callback);var bad=new VivoVcf2Device.Buffer(5);bad.fail=true;
  capture.onBuffer(bad);check(listener.failed==3&&handler.delayed.isEmpty());
  capture.start(new CaptureRequest.Builder(),6,session,callback);capture.fail(5,"late");check(listener.failed==3);
  capture.fail(6,"aborted");check(listener.failed==4);
  session.fail=true;rejects(()->capture.start(new CaptureRequest.Builder(),7,session,callback));
  check(handler.delayed.isEmpty());session.fail=false;
  handler.reject=true;int calls=session.calls;
  rejects(()->capture.start(new CaptureRequest.Builder(),8,session,callback));check(session.calls==calls);
  handler.reject=false;capture.start(new CaptureRequest.Builder(),9,session,callback);
  capture.onError(41);check(listener.failed==5&&handler.delayed.isEmpty());
  rejects(()->capture.start(new CaptureRequest.Builder(),10,session,callback));capture.close();check(VivoVcf2Device.last.closed);
  var second=new VivoVcf2Capture(new Context(),handler,listener);
  second.start(new CaptureRequest.Builder(),11,session,callback);second.close();
  second.onResult(11,result,false);second.onBuffer(new VivoVcf2Device.Buffer(11));handler.expire();
  check(listener.done==2&&listener.failed==5&&handler.delayed.isEmpty());
  rejects(()->second.start(new CaptureRequest.Builder(),12,session,callback));
  System.out.println("PASS: "+checks+" VCF2 transaction ordering/cancellation checks; host stubs");
 }
}'''
with tempfile.TemporaryDirectory(prefix='vcf2-capture-') as directory:
    root = Path(directory)
    for name, source in {**STUBS, 'Check.java': CHECK}.items():
        target = root/name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source)
    sources = [ROOT/'app/src/main/java/com/particlesdevs/photoncamera/capture'/name
               for name in ('VivoVcf2Request.java', 'VivoVcf2Capture.java')]
    subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-d', directory,
                    *map(str, root.rglob('*.java')), *map(str, sources)], check=True)
    subprocess.run(['java', '-cp', directory, 'Check'], check=True)

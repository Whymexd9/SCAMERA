#!/usr/bin/env python3
import ast
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
tree = ast.parse((ROOT / 'tools/check_vivo_vcf2_device.py').read_text())
stubs = ast.literal_eval(next(n.value for n in tree.body if isinstance(n, ast.Assign)
                             and any(isinstance(t, ast.Name) and t.id == 'STUBS' for t in n.targets)))
stubs.update({
    'android/content/pm/ApplicationInfo.java': '''package android.content.pm;
public class ApplicationInfo {public String sourceDir="/app/a'b/base.apk",nativeLibraryDir="/app/lib";}''',
    'android/content/Context.java': '''package android.content;
public class Context {public String name="com.test.good";
 public String getPackageName(){return name;}
 public android.content.pm.ApplicationInfo getApplicationInfo(){return new android.content.pm.ApplicationInfo();}}''',
    'android/content/ContextWrapper.java': '''package android.content;
public class ContextWrapper extends Context {public ContextWrapper(Context c){}}''',
    'androidx/annotation/Keep.java': 'package androidx.annotation; public @interface Keep {}',
    'android/os/Process.java': 'package android.os; public class Process {public static int myUid(){return 0;}}',
    'android/os/HandlerThread.java': '''package android.os;
public class HandlerThread extends Thread {public HandlerThread(String s){super(s);}public Object getLooper(){return null;}}''',
    'android/util/Log.java': '''package android.util;
public class Log {public static int i(String t,String m){return 0;}public static int e(String t,String m){return 0;}}''',
    'android/os/Handler.java': '''package android.os;
import java.util.*;import java.util.concurrent.*;
public class Handler {
 public Handler(){}public Handler(Object looper){}
 public final Queue<Runnable> queue=new ConcurrentLinkedQueue<>();
 public final List<Runnable> delayed=new CopyOnWriteArrayList<>();
 public boolean post(Runnable r){queue.add(r);return true;}
 public boolean postDelayed(Runnable r,long ms){delayed.add(r);return true;}
 public void removeCallbacks(Runnable r){delayed.remove(r);}
 public void drain(){Runnable r;while((r=queue.poll())!=null)r.run();}
 public void expire(){for(Runnable r:new ArrayList<>(delayed))r.run();}
}''',
    'android/hardware/camera2/CaptureResult.java': '''package android.hardware.camera2;
public class CaptureResult {public static class Key<T>{}public static final Key<Long> SENSOR_TIMESTAMP=new Key<>();
 public Long timestamp=777L;public long getFrameNumber(){return -1;}@SuppressWarnings("unchecked") public <T>T get(Key<T> k){return (T)timestamp;}}''',
})

FAKE_SU = r'''#!/usr/bin/env python3
import struct, sys, shlex
tokens=shlex.split(sys.argv[2])
assert tokens[1] == "CLASSPATH=/app/a'b/base.apk;"
assert '-Djava.library.path=/app/lib:/app/a\'b/base.apk!/lib/arm64-v8a' in tokens
mode=tokens[-1].split('.')[-1]
inp,out=sys.stdin.buffer,sys.stdout.buffer
def send(kind, ident):out.write(struct.pack('>iq',kind,ident))
out.write(struct.pack('>ii',0x56434632 if mode!='badmagic' else 0,1));out.flush()
if mode=='badmagic':sys.exit(0)
if mode!='timeout':send(1,0);out.flush()
while True:
 data=inp.read(12)
 if not data:break
 kind,ident=struct.unpack('>iq',data)
 if kind==11:continue
 assert kind==10
 send(2,ident);out.flush()
 if mode=='truncated':
  send(3,ident);out.write(struct.pack('>i',10)+b'\xff\xd8');out.flush();break
 send(4,ident+1);out.write(struct.pack('>q',999))
 send(4,ident);out.write(struct.pack('>q',777))
 send(3,ident);out.write(struct.pack('>i',6)+b'\xff\xd8\xff\x01\x02\x03');out.flush()
'''

CHECK = r'''package com.particlesdevs.photoncamera.capture;
import java.io.*;import java.util.*;import java.util.function.BooleanSupplier;
import android.content.Context;import android.os.Handler;
public class Check implements VivoVcf2Root.Listener {
 int jpeg,results,errors,armed;long timestamp;static int checks;
 public void onJpeg(long id,byte[] b){check(id==50&&b.length==6);jpeg++;}
 public void onFinal(long id,long t){check(id==50);results++;timestamp=t;}
 public void onFailure(long id,String message){errors++;}
 static void check(boolean ok){checks++;if(!ok)throw new AssertionError("check "+checks);}
 static void until(Handler h,BooleanSupplier done)throws Exception {
  long end=System.nanoTime()+3_000_000_000L;
  while(!done.getAsBoolean()&&System.nanoTime()<end){h.drain();Thread.sleep(5);}
  h.drain();check(done.getAsBoolean());
 }
 public static void main(String[] args)throws Exception {
  var stdout=System.out;var workerBytes=new ByteArrayOutputStream();
  System.setOut(new java.io.PrintStream(workerBytes));
  var worker=new VivoVcf2RootWorker();System.setOut(stdout);
  var field=VivoVcf2RootWorker.class.getDeclaredField("armed");field.setAccessible(true);field.setLong(worker,50);
  var metadata=new android.hardware.camera2.CaptureResult();
  worker.onResult(49,metadata,false);worker.onResult(50,metadata,true);check(workerBytes.size()==0);
  worker.onResult(50,metadata,false);int written=workerBytes.size();worker.onResult(50,metadata,false);
  check(written==20&&workerBytes.size()==written);
  var workerInput=new DataInputStream(new ByteArrayInputStream(workerBytes.toByteArray()));
  check(workerInput.readInt()==4&&workerInput.readLong()==50&&workerInput.readLong()==777);
  byte[] jpeg={(byte)255,(byte)216,(byte)255,0,1,2,3};
  var storage=new ByteArrayOutputStream();var out=new DataOutputStream(storage);
  VivoVcf2Wire.jpeg(out,50,jpeg);var in=new DataInputStream(new ByteArrayInputStream(storage.toByteArray()));
  check(in.readInt()==3&&in.readLong()==50);check(Arrays.equals(jpeg,VivoVcf2Wire.readJpeg(in)));
  for(int length:new int[]{-1,0,3,VivoVcf2Wire.MAX_JPEG+1}){
   storage.reset();out.writeInt(length);
   try{VivoVcf2Wire.readJpeg(new DataInputStream(new ByteArrayInputStream(storage.toByteArray())));throw new AssertionError();}
   catch(IOException expected){checks++;}
  }
  var context=new Context();var h=new Handler();var listener=new Check();
  var root=new VivoVcf2Root(context,h,listener);
  root.arm(50,()->listener.armed++);
  until(h,()->listener.jpeg==1&&listener.results==1);
  check(listener.armed==1&&listener.timestamp==777&&listener.errors==0&&h.delayed.isEmpty());
  root.retire(50);root.close();root.close();
  try{root.arm(51,()->{});throw new AssertionError();}catch(IOException expected){checks++;}
  for(String mode:new String[]{"badmagic","truncated","timeout"}){
   context=new Context();context.name="com.test."+mode;var hh=new Handler();var ll=new Check();
   var rr=new VivoVcf2Root(context,hh,ll);rr.arm(50,()->ll.armed++);
   if(mode.equals("timeout"))hh.expire();
   until(hh,()->ll.errors==1);check(ll.jpeg==0&&hh.delayed.isEmpty());rr.close();
  }
  System.out.println("PASS: "+checks+" root pipe/protocol checks; production Java compiled with Android stubs");
 }
}'''

with tempfile.TemporaryDirectory(prefix='vcf2-root-') as directory:
    base = Path(directory)
    for name, source in {**stubs, 'com/particlesdevs/photoncamera/capture/Check.java': CHECK}.items():
        path = base / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source)
    executable = base / 'su'
    executable.write_text(FAKE_SU)
    executable.chmod(0o700)
    production = ROOT / 'app/src/main/java/com/particlesdevs/photoncamera/capture'
    sources = [production / name for name in ('VivoVcf2Wire.java', 'VivoVcf2Device.java',
                                              'VivoVcf2Root.java', 'VivoVcf2RootWorker.java')]
    subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-d', directory,
                    *map(str, base.rglob('*.java')), *map(str, sources)], check=True)
    subprocess.run(['java', '-cp', directory, 'com.particlesdevs.photoncamera.capture.Check'],
                   env={**os.environ, 'PATH': directory + os.pathsep + os.environ['PATH']},
                   check=True, timeout=20)

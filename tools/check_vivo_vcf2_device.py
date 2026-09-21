#!/usr/bin/env python3
from pathlib import Path
import argparse
import hashlib
import subprocess
import tempfile
import zipfile
from enum import IntFlag
from loguru import logger
from androguard.core.dex import DEX, HiddenApiClassDataItem

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_SHA = '5da51afefe3c2902697f57d466525f83ea62c221303f0c664cac15c3c0ef685b'
CALLBACKS = {
    'onBufferCallback': '(JLandroid/os/ParcelFileDescriptor;IIIIII)V',
    'onVIFResultCallback': '(Landroid/hardware/VIFResult;)V',
    'onVIFPartialResultCallback': '(Landroid/hardware/VIFResult;)V',
    'onCaptureFrameDone': '(J)V', 'onDeviceUpdate': '(I)V', 'onError': '(I)V',
    'onVIFInfoUpdate': '(JII)V', 'onVOPCaptureDone': '(IJIII)V',
    'onVivoNotifyCallback': '(II)V',
}
STUBS = {
'android/content/Context.java': 'package android.content; public class Context {}',
'android/hardware/camera2/CaptureResult.java': '''package android.hardware.camera2;
public class CaptureResult { public long getFrameNumber(){return -1;} }''',
'android/hardware/VIFResult.java': '''package android.hardware;
public class VIFResult extends android.hardware.camera2.CaptureResult {
 private final long id; public VIFResult(long id){this.id=id;}
 public long getCaptureId(){return id;}
}''',
'android/os/Handler.java': '''package android.os;
import java.util.*;
public class Handler {
 public final Queue<Runnable> queue=new ArrayDeque<>();
 public boolean reject,fail;
 public boolean post(Runnable task){if(fail)throw new IllegalStateException("post failed");
  if(reject)return false;queue.add(task);return true;}
 public void drain(){while(!queue.isEmpty())queue.remove().run();}
}''',
'android/os/ParcelFileDescriptor.java': '''package android.os;
import java.io.*;
public class ParcelFileDescriptor implements AutoCloseable {
 public int closes; private final FileDescriptor descriptor=new FileDescriptor();
 public FileDescriptor getFileDescriptor(){return descriptor;}
 public int getFd(){return -1;}
 public static ParcelFileDescriptor dup(FileDescriptor fd)throws IOException{return new ParcelFileDescriptor();}
 public void close()throws IOException{closes++;}
}''',
'android/hardware/vivocamera/IVivoCameraDeviceCb.java': '''package android.hardware.vivocamera;
import android.os.ParcelFileDescriptor; import android.hardware.VIFResult;
public interface IVivoCameraDeviceCb {
 void onBufferCallback(long id,ParcelFileDescriptor fd,int size,int width,int height,int stride,int scanlines,int format);
 void onVIFResultCallback(VIFResult r); void onVIFPartialResultCallback(VIFResult r);
 void onCaptureFrameDone(long id); void onDeviceUpdate(int status); void onError(int error);
 void onVIFInfoUpdate(long id,int type,int value);
 void onVOPCaptureDone(int reader,long id,int a,int b,int c); void onVivoNotifyCallback(int a,int b);
}''',
'android/hardware/vivocamera/VivoCameraDevice.java': '''package android.hardware.vivocamera;
public class VivoCameraDevice {
 public static boolean fail; public int initializes,closes;
 public void initialize(){initializes++;if(fail)throw new IllegalStateException("initialize failed");}
 public void close(){closes++;}
}''',
'android/hardware/vivocamera/VivoCameraManager.java': '''package android.hardware.vivocamera;
import android.content.Context;
public class VivoCameraManager {
 public static final VivoCameraManager INSTANCE=new VivoCameraManager();
 public static boolean unavailable; public static IVivoCameraDeviceCb cb;
 public static VivoCameraDevice last;
 public static VivoCameraManager getInstance(){return INSTANCE;}
 public VivoCameraDevice open(Context c,IVivoCameraDeviceCb callback){
  cb=callback;last=new VivoCameraDevice();return unavailable?null:last;
 }
}'''
}
CHECK = r'''import java.io.*; import java.util.*;
import android.content.Context; import android.os.*; import android.hardware.*;
import android.hardware.vivocamera.*;
import com.particlesdevs.photoncamera.capture.VivoVcf2Device;
public class Check implements VivoVcf2Device.Listener {
 static int checks; static final long ID=0x123456789abcdefL;
 List<String> events=new ArrayList<>(); ParcelFileDescriptor retained;
 VivoVcf2Device.Buffer expired; boolean failBuffer;
 static void check(boolean b){checks++;if(!b)throw new AssertionError();}
 public void onBuffer(VivoVcf2Device.Buffer b){
  check(b.captureId==ID && b.size==640 && b.width==16 && b.height==12
   && b.stride==32 && b.scanlines==20 && b.format==35);
  expired=b;events.add("buffer");
  if(failBuffer)throw new IllegalStateException("consumer failed");
  try{retained=b.duplicateDescriptor();}catch(IOException e){throw new AssertionError(e);}
 }
 public void onResult(long id,android.hardware.camera2.CaptureResult r,boolean partial){
  check(id==ID && r.getFrameNumber()==-1);events.add(partial?"partial":"result");
 }
 public void onCaptureFrameDone(long id){check(id==ID);events.add("done");}
 public void onDeviceUpdate(int v){check(v==19);events.add("device");}
 public void onError(int v){check(v==1);events.add("error");}
 public void onInfo(long id,int a,int b){check(id==ID&&a==2&&b==3);events.add("info");}
 public void onVopCaptureDone(int reader,long id,int a,int b,int c){
  check(reader==5&&id==ID&&a==6&&b==7&&c==8);events.add("vop");
 }
 public void onNotify(int a,int b){check(a==4&&b==9);events.add("notify");}
 static void buffer(IVivoCameraDeviceCb cb,ParcelFileDescriptor fd){cb.onBufferCallback(ID,fd,640,16,12,32,20,35);}
 public static void main(String[] args)throws Exception {
  var h=new Handler();var listener=new Check();
  var d=VivoVcf2Device.open(new Context(),h,listener);var nativeDevice=VivoCameraManager.last;
  var cb=VivoCameraManager.cb;
  check(nativeDevice.initializes==1 && nativeDevice.closes==0);
  check(cb.equals(cb)&&!cb.equals(new Object())&&cb.hashCode()==System.identityHashCode(cb));
  check(cb.toString().contains("SCAMERA"));
  var fd=new ParcelFileDescriptor();buffer(cb,fd);
  cb.onVIFPartialResultCallback(new VIFResult(ID));cb.onVIFResultCallback(new VIFResult(ID));
  cb.onCaptureFrameDone(ID);cb.onDeviceUpdate(19);cb.onVIFInfoUpdate(ID,2,3);
  cb.onVOPCaptureDone(5,ID,6,7,8);cb.onVivoNotifyCallback(4,9);cb.onError(1);
  check(listener.events.isEmpty()&&fd.closes==0);h.drain();
  check(listener.events.equals(List.of("buffer","partial","result","done","device","info","vop","notify","error")));
  check(fd.closes==1&&listener.retained.closes==0);
  try{listener.expired.duplicateDescriptor();throw new AssertionError();}catch(IOException expected){checks++;}
  listener.expired.close();check(fd.closes==1);listener.retained.close();
  var waiting=new ParcelFileDescriptor();buffer(cb,waiting);cb.onCaptureFrameDone(ID);
  int before=listener.events.size();d.close();d.close();h.drain();
  check(waiting.closes==1&&nativeDevice.closes==1&&listener.events.size()==before);
  var late=new ParcelFileDescriptor();buffer(cb,late);cb.onError(1);h.drain();
  check(late.closes==1&&listener.events.size()==before);
  d=VivoVcf2Device.open(new Context(),h,listener);cb=VivoCameraManager.cb;
  h.reject=true;var rejected=new ParcelFileDescriptor();buffer(cb,rejected);check(rejected.closes==1);
  h.reject=false;h.fail=true;var failedPost=new ParcelFileDescriptor();
  try{buffer(cb,failedPost);throw new AssertionError();}catch(IllegalStateException expected){checks++;}
  check(failedPost.closes==1);h.fail=false;
  listener.failBuffer=true;var consumerFailure=new ParcelFileDescriptor();buffer(cb,consumerFailure);
  try{h.drain();throw new AssertionError();}catch(IllegalStateException expected){checks++;}
  check(consumerFailure.closes==1);d.close();
  VivoCameraDevice.fail=true;
  try{VivoVcf2Device.open(new Context(),h,listener);throw new AssertionError();}
  catch(java.lang.reflect.InvocationTargetException expected){checks++;}
  check(VivoCameraManager.last.closes==1);VivoCameraDevice.fail=false;
  VivoCameraManager.unavailable=true;
  try{VivoVcf2Device.open(new Context(),h,listener);throw new AssertionError();}
  catch(IllegalStateException expected){checks++;}
  System.out.println("PASS: "+checks+" VCF2 callback/lifetime checks; host stubs, no device capture");
 }
}'''

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('framework', type=Path)
    args = ap.parse_args()
    raw = args.framework.read_bytes()
    if hashlib.sha256(raw).hexdigest() != EXPECTED_SHA:
        raise ValueError('Unexpected framework version')
    logger.remove()
    # This DEX has domain flag bits newer than androguard's enum. Keep all bits;
    # only the local analysis parser changes, not the DEX or Android restrictions.
    class Domain(IntFlag):
        NONE = 0
        CORE_PLATFORM_API = 1
        TEST_API = 2
    HiddenApiClassDataItem.DomapiApiFlag = Domain
    with zipfile.ZipFile(args.framework) as archive:
        dex = DEX(archive.read('classes.dex'))
    classes = {c.get_name(): c for c in dex.get_classes()}
    def methods(name):
        return {m.get_name(): m.get_descriptor().replace(' ', '')
                for m in classes[name].get_methods()}
    assert methods('Landroid/hardware/vivocamera/IVivoCameraDeviceCb;') == CALLBACKS
    assert classes['Landroid/hardware/VIFResult;'].get_superclassname() == 'Landroid/hardware/camera2/CaptureResult;'
    assert methods('Landroid/hardware/VIFResult;')['getCaptureId'] == '()J'
    device = methods('Landroid/hardware/vivocamera/VivoCameraDevice;')
    assert device['close'] == device['initialize'] == '()V'
    manager = methods('Landroid/hardware/vivocamera/VivoCameraManager;')
    assert manager['getInstance'] == '()Landroid/hardware/vivocamera/VivoCameraManager;'
    assert manager['open'] == '(Landroid/content/Context;Landroid/hardware/vivocamera/IVivoCameraDeviceCb;)Landroid/hardware/vivocamera/VivoCameraDevice;'
    print('PASS: reflected method signatures match supplied framework DEX', flush=True)
    with tempfile.TemporaryDirectory(prefix='vcf2-device-') as directory:
        root = Path(directory)
        for name, source in {**STUBS, 'Check.java': CHECK}.items():
            path = root/name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
        production = ROOT/'app/src/main/java/com/particlesdevs/photoncamera/capture/VivoVcf2Device.java'
        subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-d', directory,
                        *map(str, root.rglob('*.java')), str(production)], check=True)
        subprocess.run(['java', '-cp', directory, 'Check'], check=True)

if __name__ == '__main__':
    main()

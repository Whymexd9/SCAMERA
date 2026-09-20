#!/usr/bin/env python3
"""Host regression for real ImageFrame metadata retention; Android APIs are stubs.

No device/photographic equivalence is claimed. Requires Java 17 with jdk.compiler.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
BASE = 'com/particlesdevs/photoncamera/'
STUBS = {
'android/graphics/ImageFormat.java': 'package android.graphics; public class ImageFormat {}',
'android/media/Image.java': 'package android.media; public class Image {}',
'android/hardware/camera2/CaptureRequest.java': '''package android.hardware.camera2;
public class CaptureRequest {private final Object tag;
 public CaptureRequest(Object tag){this.tag=tag;} public Object getTag(){return tag;}
}''',
'android/util/Pair.java': '''package android.util; public class Pair<A,B> {
 public final A first; public final B second;
 public Pair(A a,B b){first=a;second=b;}
}''',
'android/hardware/camera2/CaptureResult.java': '''package android.hardware.camera2;
import android.util.Pair; import java.util.*;
public class CaptureResult {
 public CaptureRequest request;
 public CaptureRequest getRequest(){return request;}
 public static class Key<T> {}
 public static final Key<Long> SENSOR_TIMESTAMP=new Key<>(), SENSOR_EXPOSURE_TIME=new Key<>();
 public static final Key<Integer> SENSOR_SENSITIVITY=new Key<>(), LENS_STATE=new Key<>();
 public static final Key<Float> LENS_FOCUS_DISTANCE=new Key<>();
 public static final Key<Pair<Double,Double>[]> SENSOR_NOISE_PROFILE=new Key<>();
 public static final int LENS_STATE_MOVING=1;
 private final Map<Key<?>,Object> values=new HashMap<>();
 public <T> void put(Key<T> key,T value){values.put(key,value);}
 @SuppressWarnings("unchecked") public <T> T get(Key<T> key){return (T)values.get(key);}
}''',
BASE+'util/Log.java': '''package com.particlesdevs.photoncamera.util;
public class Log {public static void d(String a,String b){}}''',
BASE+'control/GyroBurst.java': 'package com.particlesdevs.photoncamera.control; public class GyroBurst {}',
BASE+'processing/parameters/IsoExpoSelector.java': '''package com.particlesdevs.photoncamera.processing.parameters;
public class IsoExpoSelector {public static class ExpoPair {}}''',
BASE+'capture/RawFrameQuality.java': '''package com.particlesdevs.photoncamera.capture;
public class RawFrameQuality {public static double score(java.nio.ByteBuffer b,int... dims){return 0;}}''',
BASE+'util/Allocator.java': '''package com.particlesdevs.photoncamera.util; import java.nio.*;
public class Allocator {
 public static boolean binning=false;
 public static ByteBuffer allocateAndCopy(int n,ByteBuffer b,int s){return ByteBuffer.allocate(n);}
 public static ByteBuffer allocateAndCopyConvert(int n,ByteBuffer b,int w,int r,int s){return ByteBuffer.allocate(n);}
 public static ByteBuffer allocateAndCopyBinning(int n,ByteBuffer b,int w,int h,int r){return ByteBuffer.allocate(n);}
 public static ByteBuffer allocateAndCopyConvertBinning(int n,ByteBuffer b,int w,int r,int s){return ByteBuffer.allocate(n);}
 public static void free(ByteBuffer b){}
}''',
'Check.java': '''import android.hardware.camera2.CaptureResult;
import android.util.Pair; import java.nio.ByteBuffer;
import com.particlesdevs.photoncamera.processing.ImageFrame;
public class Check {
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static CaptureResult result(long timestamp,int iso){
  CaptureResult r=new CaptureResult();r.put(CaptureResult.SENSOR_TIMESTAMP,timestamp);
  r.put(CaptureResult.SENSOR_SENSITIVITY,iso);r.put(CaptureResult.SENSOR_EXPOSURE_TIME,10000000L);return r;
 }
 @SuppressWarnings("unchecked") public static void main(String[] args){
  ImageFrame f=new ImageFrame(ByteBuffer.allocate(8));f.timestamp=123456789012L;
  CaptureResult zsl=result(f.timestamp,200);
  zsl.put(CaptureResult.SENSOR_NOISE_PROFILE,new Pair[]{new Pair<Double,Double>(.2,.01)});
  f.setCaptureMetadata(zsl);
  check(f.getMatchedCaptureMetadata()==zsl,"ZSL full metadata lost");
  check(f.measuredIso==200 && f.noiseSlope==.2f,"measured calibration missing");
  f.setCaptureMetadata(null);
  check(f.getMatchedCaptureMetadata()==zsl && f.measuredIso==200,"second map drain erased ZSL");
  CaptureResult replacement=result(f.timestamp,400);f.setCaptureMetadata(replacement);
  check(f.getMatchedCaptureMetadata()==replacement && f.measuredIso==400,"reference update failed");
  check(Float.isNaN(f.noiseSlope)&&Float.isNaN(f.noiseOffset),"stale noise profile retained");
  CaptureResult wrong=result(f.timestamp+1,800);f.setCaptureMetadata(wrong);
  check(f.getMatchedCaptureMetadata()==null,"another RAW's calibration accepted");
  CaptureResult absent=new CaptureResult();f.setCaptureMetadata(absent);
  check(f.getMatchedCaptureMetadata()==null,"missing timestamp accepted");
  check(f.measuredIso==0 && f.measuredExposure==0,"missing measured exposure retained");
  ImageFrame other=new ImageFrame(ByteBuffer.allocate(8));other.timestamp=f.timestamp+1;
  other.setCaptureMetadata(wrong);f.setCaptureMetadata(zsl);
  check(other.getMatchedCaptureMetadata()==wrong && f.getMatchedCaptureMetadata()==zsl,"frame metadata shared");
  check(f.getCaptureRole()==null,"untagged PSL frame guessed as normal");
  f.fromZsl=true;
  check(f.getCaptureRole()==ImageFrame.CaptureRole.NORMAL,"ZSL normal role lost");
  f.fromZsl=false;
  zsl.request=new android.hardware.camera2.CaptureRequest(ImageFrame.CaptureRole.LONG);
  wrong.request=new android.hardware.camera2.CaptureRequest(ImageFrame.CaptureRole.SHORT);
  check(f.getCaptureRole()==ImageFrame.CaptureRole.LONG && other.getCaptureRole()==ImageFrame.CaptureRole.SHORT,
        "request roles not attached to their own RAW");
  java.util.List<ImageFrame> delivered=new java.util.ArrayList<>();
  delivered.add(other);delivered.add(f);java.util.Collections.reverse(delivered);delivered.remove(f);
  check(delivered.get(0).getCaptureRole()==ImageFrame.CaptureRole.SHORT,"dropped/reordered frame changed role");
  other.timestamp++;
  check(other.getCaptureRole()==null,"mismatched timestamp supplied a role");
  other.fromZsl=true;
  check(other.getCaptureRole()==null,"ZSL flag bypassed metadata match");
  System.out.println("PASS: actual ImageFrame retains timestamp-matched ZSL/reference metadata and resets missing noise");
  System.out.println("PASS: N/L/S request roles survive reordering/dropped frames; unknown roles rejected");
 }
}'''
}
with tempfile.TemporaryDirectory(prefix='nice-reference-metadata-') as tmp:
    out = Path(tmp)
    for name, content in STUBS.items():
        path = out/name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
    source = ROOT/'app/src/main/java'/BASE/'processing/ImageFrame.java'
    subprocess.run(['java','-m','jdk.compiler/com.sun.tools.javac.Main','-d',str(out),
                    *map(str,out.rglob('*.java')),str(source)],check=True)
    subprocess.run(['java','-cp',str(out),'Check'],check=True)

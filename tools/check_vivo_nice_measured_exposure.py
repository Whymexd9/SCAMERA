#!/usr/bin/env python3
import subprocess, tempfile
from pathlib import Path

root=Path(__file__).resolve().parents[1]
pkg='com/particlesdevs/photoncamera/'
post=pkg+'processing/opengl/postpipeline/'
sources={
'android/content/Context.java':'package android.content; public class Context {}',
'android/os/Build.java':'package android.os; public class Build {public static String MANUFACTURER="vivo",DEVICE="PD2454";}',
'android/hardware/camera2/CaptureResult.java':'''package android.hardware.camera2;
public class CaptureResult {public float[] aec; public static class Key<T> {public String name;public Key(String n,Class<T> t){name=n;}}
@SuppressWarnings("unchecked") public <T>T get(Key<T> k){return k.name.endsWith("AECFrameControl")?(T)aec:null;}}''',
pkg+'processing/ImageFrame.java':'''package com.particlesdevs.photoncamera.processing;
import android.hardware.camera2.CaptureResult;import java.nio.ByteBuffer;
public class ImageFrame {public int number,width=64,height=64,measuredIso=100;public long timestamp,measuredExposure=1000000;
public boolean fromZsl;public float noiseSlope=1,noiseOffset=0;public ByteBuffer buffer=ByteBuffer.allocate(8192);
public enum CaptureRole {NORMAL,SHORT,LONG,EXTRA_SHORT}
public CaptureRole role=CaptureRole.NORMAL;public CaptureRole getCaptureRole(){return role;}
public Pair pair=new Pair();public CaptureResult result=new CaptureResult();public CaptureResult getMatchedCaptureMetadata(){return result;}
public static class Pair {public boolean isHighlightFrame,isLongFrame;}}''',
pkg+'processing/render/Parameters.java':'''package com.particlesdevs.photoncamera.processing.render;
public class Parameters {public static class Size {public int x=64,y=64;} public Size rawSize=new Size();
public int cfaPattern,physicalID=3;public float whiteLevel=16383;public float[] blackLevel={64,64,64,64};public boolean quadCfa;}''',
pkg+'settings/PreferenceKeys.java':'''package com.particlesdevs.photoncamera.settings;
public class PreferenceKeys {public static boolean isNiceDiagnosticsEnabled(){return false;}public static boolean isRemosaicEnabled(){return false;}
public static float niceInternalValue(String key,float value){return value;}}''',
pkg+'util/Log.java':'package com.particlesdevs.photoncamera.util; public class Log {public static void i(String a,String b){}}',
pkg+'util/Allocator.java':'package com.particlesdevs.photoncamera.util; public class Allocator {public static boolean binning;}',
post+'VivoNiceScene.java':'''package com.particlesdevs.photoncamera.processing.opengl.postpipeline;
public class VivoNiceScene {static VivoNiceScene fromReference(com.particlesdevs.photoncamera.processing.ImageFrame f){return new VivoNiceScene();}
String describe(){return "";}void writeTransport(java.nio.ByteBuffer b){b.position(b.position()+32);}}''',
post+'NiceDiagnostics.java':'''package com.particlesdevs.photoncamera.processing.opengl.postpipeline;
public class NiceDiagnostics {static void begin(Object a,Object b,Object c,Object d){}}''',
post+'VivoNeuralClient.java':'''package com.particlesdevs.photoncamera.processing.opengl.postpipeline;
public class VivoNeuralClient {static java.nio.ByteBuffer processNiceBurst(android.content.Context c,VivoNiceBurst b){return null;}}''',
post+'Check.java':'''package com.particlesdevs.photoncamera.processing.opengl.postpipeline;
import com.particlesdevs.photoncamera.processing.ImageFrame;import com.particlesdevs.photoncamera.processing.render.Parameters;
import java.util.*;import java.lang.reflect.*;
public class Check {
 static Object make(List<ImageFrame> fs)throws Exception {Constructor<?> c=VivoNiceBurst.class.getDeclaredConstructor(List.class,Parameters.class);c.setAccessible(true);return c.newInstance(fs,new Parameters());}
 static float[] ratios(Object b)throws Exception {Field f=VivoNiceBurst.class.getDeclaredField("exposure");f.setAccessible(true);return (float[])f.get(b);}
 static void rejected(List<ImageFrame> fs)throws Exception {
  try{make(fs);throw new AssertionError("invalid 4+3 accepted");}
  catch(InvocationTargetException e){if(!(e.getCause() instanceof java.io.IOException))throw e;}
 }
 static void near(float x,double y){if(Math.abs(x-y)>1e-5*Math.max(1,Math.abs(y)))throw new AssertionError(x+" != "+y);}
 public static void main(String[] args)throws Exception {
  List<ImageFrame> fs=new ArrayList<>();
  float[] ms={2.49614191f,2.49614191f,2.49614191f,2.49614191f,1.75925505f,.22046f,4.98855686f};
  float[] gain={7.62135792f,7.62135792f,7.62135792f,7.62135792f,1.43150008f,1.51219499f,7.6270504f};
  for(int i=0;i<7;i++){ImageFrame f=new ImageFrame();f.number=i;f.timestamp=i+1;f.pair.isHighlightFrame=i==4||i==5;f.pair.isLongFrame=i==6;f.role=i==6?ImageFrame.CaptureRole.LONG:i>=4?ImageFrame.CaptureRole.SHORT:ImageFrame.CaptureRole.NORMAL;
   f.result.aec=new float[35];for(int j:new int[]{2,6,13,14})f.result.aec[j]=1;
   f.result.aec[2]=gain[i];f.result.aec[14]=ms[i]*1000000f;fs.add(f);}
  float[] r=ratios(make(fs));double base=(double)fs.get(0).result.aec[14]*gain[0];int[] order={0,1,2,3,6,4,5};
  for(int i=0;i<7;i++)near(r[i],(double)fs.get(order[i]).result.aec[14]*gain[order[i]]/base);
  // SQGhYcUe: N and S share the same shutter; gain carries their exposure difference.
  float[] secondMs={33.32622528f,33.32622528f,33.32622528f,33.32622528f,33.32622528f,8.32622528f,66.65956116f};
  float[] secondGain={9.55417538f,9.55417538f,9.55417538f,9.55417538f,1.68071389f,1.43121970f,9.55315208f};
  for(int i=0;i<7;i++){fs.get(i).result.aec[2]=secondGain[i];fs.get(i).result.aec[14]=secondMs[i]*1000000f;}
  r=ratios(make(fs));base=(double)fs.get(0).result.aec[14]*secondGain[0];
  for(int i=0;i<7;i++)near(r[i],(double)fs.get(order[i]).result.aec[14]*secondGain[order[i]]/base);
  // L is a matched request role, not a promise of strictly greater exposure.
  // Preserve the distinct RAW and its own AE even when L and N products match.
  float longTime=fs.get(6).result.aec[14],longGain=fs.get(6).result.aec[2];
  fs.get(6).result.aec[14]=fs.get(0).result.aec[14];
  fs.get(6).result.aec[2]=fs.get(0).result.aec[2];
  near(ratios(make(fs))[4],1);
  fs.get(6).result.aec[2]*=.5f;rejected(fs);
  fs.get(6).result.aec[14]=longTime;fs.get(6).result.aec[2]=longGain;
  rejected(fs.subList(0,5));
  long saved=fs.get(5).timestamp;fs.get(5).timestamp=fs.get(4).timestamp;rejected(fs);fs.get(5).timestamp=saved;
  fs.get(5).role=ImageFrame.CaptureRole.NORMAL;rejected(fs);fs.get(5).role=ImageFrame.CaptureRole.SHORT;
  for(ImageFrame f:fs){f.pair.isHighlightFrame=true;f.pair.isLongFrame=true;}
  ratios(make(fs));
  fs.get(5).role=null;rejected(fs);fs.get(5).role=ImageFrame.CaptureRole.SHORT;
  float savedTime=fs.get(5).result.aec[14],savedGain=fs.get(5).result.aec[2];
  fs.get(5).result.aec[14]=fs.get(4).result.aec[14];fs.get(5).result.aec[2]=fs.get(4).result.aec[2];
  rejected(fs);fs.get(5).result.aec[14]=savedTime;fs.get(5).result.aec[2]=savedGain;
  fs.get(5).result.aec=null;
  try{make(fs);throw new AssertionError("mixed domains accepted");}catch(InvocationTargetException e){if(!(e.getCause() instanceof java.io.IOException))throw e;}
  for(int i=0;i<7;i++){fs.get(i).result.aec=null;fs.get(i).measuredExposure=i==4?250000:i==5?50000:i==6?4000000:1000000;}
  r=ratios(make(fs));near(r[4],4);near(r[5],.25);near(r[6],.05);
  try{VivoNiceAe.fromFrame(fs.get(0)).measuredExposureProduct();throw new AssertionError();}catch(IllegalStateException expected){}
  System.out.println("PASS: actual burst uses matched vendor products, rejects mixed domains, preserves explicit Camera2 fallback");
 }
}'''}
with tempfile.TemporaryDirectory() as tmp:
 t=Path(tmp)
 for name,content in sources.items():
  p=t/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 for name in ['VivoNiceAe.java','VivoNiceBurst.java']:
  (t/post/name).write_text((root/'app/src/main/java'/post/name).read_text())
 subprocess.run(['java','-m','jdk.compiler/com.sun.tools.javac.Main','-d',tmp,*map(str,t.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'com.particlesdevs.photoncamera.processing.opengl.postpipeline.Check'],check=True)

#!/usr/bin/env python3
"""Check the production preview writer without building an APK or emulating HAL."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
STUB = '''package android.hardware.camera2;
import java.util.*;
public class CaptureRequest {
 public static class Key<T> {
  final String name; final Class<T> type;
  public Key(String n,Class<T> t){name=n;type=t;}
 }
 public static class Builder {
  public final Map<String,Object> fields=new HashMap<>();
  public String unsupported, failOnce;
  public int writes;
  @SuppressWarnings("unchecked") public <T>T get(Key<T> key){
   if(key.name.equals(unsupported))throw new IllegalArgumentException();
   return (T)fields.get(key.name);
  }
  public <T>void set(Key<T> key,T value){
   writes++;
   if(key.name.equals(failOnce)){failOnce=null;throw new IllegalArgumentException();}
   if(value==null)fields.remove(key.name);
   else {if(!key.type.isInstance(value))throw new AssertionError();fields.put(key.name,value);}
  }
 }
}'''
CHECK = '''import android.hardware.camera2.CaptureRequest;
import com.particlesdevs.photoncamera.capture.VivoNicePreview;
import java.util.*;
public class Check {
 static final String MAGIC="vivo.control.NiceMagicEnable", NICE="vivo.capability.capture.nice";
 static int checks;
 static void check(boolean ok){checks++;if(!ok)throw new AssertionError();}
 static void rejects(Runnable r){try{r.run();}catch(IllegalArgumentException e){checks++;return;}throw new AssertionError();}
 public static void main(String[] args){
  var b=new CaptureRequest.Builder();
  b.fields.put("vivo.control.sensorMode",7);
  b.fields.put("android.sensor.pixelMode",0);
  b.fields.put("android.control.aeMode",1);
  var baseline=new HashMap<>(b.fields);
  VivoNicePreview.applySession(b);
  check(b.fields.get(MAGIC).equals(1));check(!b.fields.containsKey(NICE));
  b.fields.remove(MAGIC);check(b.fields.equals(baseline));
  VivoNicePreview.applyRepeating(b);
  check(b.fields.get(MAGIC).equals(1)&&b.fields.get(NICE).equals(1));
  b.fields.remove(MAGIC);b.fields.remove(NICE);check(b.fields.equals(baseline));
  for(String key:new String[]{MAGIC,NICE}){
   b.unsupported=key;int writes=b.writes;
   rejects(()->VivoNicePreview.applyRepeating(b));
   check(b.writes==writes&&b.fields.equals(baseline));
  }
  b.unsupported=null;
  for(boolean existing:new boolean[]{false,true}){
   if(existing){b.fields.put(MAGIC,0);b.fields.put(NICE,2);}
   var before=new HashMap<>(b.fields);
   b.failOnce=NICE;rejects(()->VivoNicePreview.applyRepeating(b));
   check(b.fields.equals(before));
  }
  System.out.println("PASS: "+checks+" preview/session writer checks; host only, no HAL validation");
 }
}'''
with tempfile.TemporaryDirectory(prefix='nice-preview-') as directory:
    root = Path(directory)
    stub = root/'android/hardware/camera2/CaptureRequest.java'
    stub.parent.mkdir(parents=True)
    stub.write_text(STUB)
    (root/'Check.java').write_text(CHECK)
    production = ROOT/'app/src/main/java/com/particlesdevs/photoncamera/capture/VivoNicePreview.java'
    subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-d', directory,
                    str(stub), str(root/'Check.java'), str(production)], check=True)
    subprocess.run(['java', '-cp', directory, 'Check'], check=True)

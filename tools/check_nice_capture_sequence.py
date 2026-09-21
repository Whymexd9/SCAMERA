#!/usr/bin/env python3
from pathlib import Path
import subprocess
import tempfile
from check_nice_reference_metadata import STUBS, BASE, ROOT

CHECK = r'''import java.nio.ByteBuffer;
import java.util.*;
import android.hardware.camera2.*;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.capture.VivoNiceCaptureSequence;
public class Check {
 static int checks;
 static void check(boolean b){checks++;if(!b)throw new AssertionError();}
 static void rejects(Runnable f){try{f.run();}catch(IllegalStateException|IllegalArgumentException e){checks++;return;}throw new AssertionError("accepted invalid series");}
 static CaptureRequest request(long generation,int index,ImageFrame.CaptureRole role){
  return new CaptureRequest(new ImageFrame.NiceCaptureTag(generation,index,role));
 }
 static CaptureResult result(CaptureRequest q,long ts){
  CaptureResult r=new CaptureResult();r.request=q;r.put(CaptureResult.SENSOR_TIMESTAMP,ts);
  r.put(CaptureResult.SENSOR_EXPOSURE_TIME,10000000L);r.put(CaptureResult.SENSOR_SENSITIVITY,200);return r;
 }
 static ImageFrame raw(long ts){ImageFrame f=new ImageFrame(ByteBuffer.allocate(8));f.timestamp=ts;return f;}
 static ImageFrame past(long ts){ImageFrame f=raw(ts);f.fromZsl=true;f.setCaptureMetadata(result(new CaptureRequest(null),ts));return f;}
 public static void main(String[] args){
  CaptureRequest a=request(1,0,ImageFrame.CaptureRole.LONG),b=request(1,1,ImageFrame.CaptureRole.SHORT);
  var requests=List.of(a,b);var zsl=List.of(past(10),past(20));
  var seq=new VivoNiceCaptureSequence(requests,zsl);
  rejects(seq::requireCompleteMetadata);
  seq.completed(b,result(b,50));seq.completed(a,result(a,40));
  var frames=List.of(raw(50),zsl.get(1),raw(40),zsl.get(0));seq.bindAndValidate(frames);
  check(seq.futureCount==2&&seq.frameCount==4);
  check(frames.get(0).getCaptureRole()==ImageFrame.CaptureRole.SHORT);
  check(frames.get(2).getCaptureRole()==ImageFrame.CaptureRole.LONG);
  rejects(()->seq.bindAndValidate(List.of(raw(50),raw(40),zsl.get(0))));
  rejects(()->seq.bindAndValidate(List.of(raw(50),raw(50),zsl.get(0),zsl.get(1))));
  rejects(()->seq.bindAndValidate(List.of(raw(50),raw(41),zsl.get(0),zsl.get(1))));
  rejects(()->seq.bindAndValidate(List.of(raw(50),raw(40),past(11),zsl.get(1))));
  var old=new VivoNiceCaptureSequence(requests,zsl);
  var stale=request(0,0,ImageFrame.CaptureRole.LONG);old.completed(stale,result(stale,40));rejects(old::requireCompleteMetadata);
  var duplicate=new VivoNiceCaptureSequence(requests,zsl);
  duplicate.completed(a,result(a,40));duplicate.completed(a,result(a,40));duplicate.completed(b,result(b,50));rejects(duplicate::requireCompleteMetadata);
  var duplicateTime=new VivoNiceCaptureSequence(requests,zsl);
  duplicateTime.completed(a,result(a,40));duplicateTime.completed(b,result(b,40));rejects(duplicateTime::requireCompleteMetadata);
  var mismatch=new VivoNiceCaptureSequence(requests,zsl);mismatch.completed(a,result(b,40));rejects(mismatch::requireCompleteMetadata);
  var failed=new VivoNiceCaptureSequence(requests,zsl);failed.completed(a,result(a,40));failed.completed(b,result(b,50));failed.failed("buffer lost");rejects(failed::requireCompleteMetadata);
  var invalid=new VivoNiceCaptureSequence(requests,zsl);var r=result(a,40);r.put(CaptureResult.SENSOR_EXPOSURE_TIME,0L);invalid.completed(a,r);rejects(invalid::requireCompleteMetadata);
  var q3=List.of(request(2,0,ImageFrame.CaptureRole.SHORT),request(2,1,ImageFrame.CaptureRole.SHORT),request(2,2,ImageFrame.CaptureRole.LONG));
  var p4=List.of(past(63),past(64),past(65),past(66));
  var stockShape=new VivoNiceCaptureSequence(q3,p4);
  for(int i=2;i>=0;--i)stockShape.completed(q3.get(i),result(q3.get(i),83+i));
  var all7=new ArrayList<ImageFrame>(p4);all7.add(raw(85));all7.add(raw(83));all7.add(raw(84));
  stockShape.bindAndValidate(all7);
  check(stockShape.futureCount==3&&stockShape.frameCount==7);
  check(all7.get(4).getCaptureRole()==ImageFrame.CaptureRole.LONG);
  check(all7.get(5).getCaptureRole()==ImageFrame.CaptureRole.SHORT);
  var onlyPast=new VivoNiceCaptureSequence(List.of(),zsl);onlyPast.bindAndValidate(zsl);check(onlyPast.futureCount==0);
  var onlyFuture=new VivoNiceCaptureSequence(requests,List.of());onlyFuture.completed(a,result(a,40));onlyFuture.completed(b,result(b,50));onlyFuture.bindAndValidate(List.of(raw(50),raw(40)));
  rejects(()->new VivoNiceCaptureSequence(List.of(),List.of()));
  rejects(()->new VivoNiceCaptureSequence(requests,List.of(zsl.get(0),zsl.get(0))));
  rejects(()->new VivoNiceCaptureSequence(List.of(b,a),zsl));
  System.out.println("PASS: "+checks+" NICE request/result/RAW checks, including reordering, missing frames, stale series, invalid metadata and HAL failure");
 }
}'''
with tempfile.TemporaryDirectory() as d:
 p=Path(d)
 for name,content in STUBS.items():
  if name=='Check.java':content=CHECK
  target=p/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(content)
 sources=[ROOT/'app/src/main/java'/BASE/x for x in ['processing/ImageFrame.java','capture/VivoNiceCaptureSequence.java']]
 subprocess.run(['java','-m','jdk.compiler/com.sun.tools.javac.Main','-d',d,*map(str,p.rglob('*.java')),*map(str,sources)],check=True)
 subprocess.run(['java','-cp',d,'Check'],check=True)

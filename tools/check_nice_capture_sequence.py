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
  var tail=List.of(request(3,0,ImageFrame.CaptureRole.LONG),request(3,1,ImageFrame.CaptureRole.SHORT),request(3,2,ImageFrame.CaptureRole.EXTRA_SHORT));
  var realZsl=VivoNiceCaptureSequence.stockZsl(tail,p4,70);
  for(int i=2;i>=0;i--)realZsl.completed(tail.get(i),result(tail.get(i),81+i));
  var joined=new ArrayList<ImageFrame>(p4);joined.add(raw(83));joined.add(raw(81));joined.add(raw(82));
  realZsl.bindAndValidate(joined);
  check(realZsl.futureCount==3 && joined.get(4).getCaptureRole()==ImageFrame.CaptureRole.EXTRA_SHORT);
  rejects(()->VivoNiceCaptureSequence.stockZsl(tail,p4,65));
  rejects(()->VivoNiceCaptureSequence.stockZsl(tail,p4,0));
  rejects(()->VivoNiceCaptureSequence.stockZsl(tail,p4.subList(0,3),70));
  rejects(()->VivoNiceCaptureSequence.stockZsl(q3,p4,70));
  var wrongTail=List.of(request(3,0,ImageFrame.CaptureRole.NORMAL),tail.get(1),tail.get(2));
  rejects(()->VivoNiceCaptureSequence.stockZsl(wrongTail,p4,70));
  var latePreview=VivoNiceCaptureSequence.stockZsl(tail,p4,70);
  latePreview.completed(tail.get(0),result(tail.get(0),69));
  latePreview.completed(tail.get(1),result(tail.get(1),82));
  latePreview.completed(tail.get(2),result(tail.get(2),83));
  rejects(latePreview::requireCompleteMetadata);
  // LMC hybrid: post-shutter frames are optional extras (lost buffer / HAL failure / missing RAW drop that frame only)
  var hq=List.of(request(4,0,ImageFrame.CaptureRole.EXTRA_SHORT),request(4,1,ImageFrame.CaptureRole.EXTRA_SHORT),request(4,2,ImageFrame.CaptureRole.LONG));
  var hz=List.of(past(91),past(92),past(93));
  var hyb=VivoNiceCaptureSequence.hybridZsl(hq,hz,95);
  hyb.lost(hq.get(0),"RAW buffer lost frame=34");hyb.completed(hq.get(0),result(hq.get(0),100));
  hyb.completed(hq.get(2),result(hq.get(2),102));hyb.requireCompleteMetadata();
  var hframes=new ArrayList<ImageFrame>(hz);hframes.add(raw(102));hframes.add(raw(100));
  var unmatched=hyb.bindAndValidate(hframes);
  check(unmatched.size()==1&&unmatched.get(0).timestamp==100&&hyb.boundFutureCount()==1&&hyb.droppedCount()==1);
  check(hframes.get(3).getCaptureRole()==ImageFrame.CaptureRole.LONG);
  var hyb2=VivoNiceCaptureSequence.hybridZsl(hq,hz,95);hyb2.completed(hq.get(1),result(hq.get(1),101));
  var hf2=new ArrayList<ImageFrame>(hz);check(hyb2.bindAndValidate(hf2).isEmpty()&&hyb2.boundFutureCount()==0&&hyb2.droppedCount()==1);
  var hyb3=VivoNiceCaptureSequence.hybridZsl(hq,hz,95);hyb3.lost(hq.get(2),"HAL capture failure=0");
  rejects(()->hyb3.bindAndValidate(List.of(hz.get(0),hz.get(1)))); // a buffered N frame is still required
  var hyb4=VivoNiceCaptureSequence.hybridZsl(hq,hz,95);hyb4.completed(hq.get(0),result(hq.get(1),100));rejects(hyb4::requireCompleteMetadata);
  var strict=VivoNiceCaptureSequence.stockZsl(tail,p4,70);strict.lost(tail.get(0),"RAW buffer lost frame=7");rejects(strict::requireCompleteMetadata);
  System.out.println("PASS: "+checks+" NICE request/result/RAW checks, including reordering, missing frames, stale series, invalid metadata and HAL failure; hybrid drops lost post-shutter frames");
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

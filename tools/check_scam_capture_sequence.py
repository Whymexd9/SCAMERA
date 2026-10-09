#!/usr/bin/env python3
from pathlib import Path
import subprocess
import tempfile
from check_scam_reference_metadata import STUBS, BASE, ROOT

CHECK = r'''import java.nio.ByteBuffer;
import java.util.*;
import android.hardware.camera2.*;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.capture.ScamCaptureSequence;
public class Check {
 static int checks;
 static void check(boolean b){checks++;if(!b)throw new AssertionError();}
 static void rejects(Runnable f){try{f.run();}catch(IllegalStateException|IllegalArgumentException e){checks++;return;}throw new AssertionError("accepted invalid series");}
 static CaptureRequest request(long generation,int index,ImageFrame.CaptureRole role){
  return new CaptureRequest(new ImageFrame.ScamCaptureTag(generation,index,role));
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
  var seq=new ScamCaptureSequence(requests,zsl);
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
  var old=new ScamCaptureSequence(requests,zsl);
  var stale=request(0,0,ImageFrame.CaptureRole.LONG);old.completed(stale,result(stale,40));rejects(old::requireCompleteMetadata);
  var duplicate=new ScamCaptureSequence(requests,zsl);
  duplicate.completed(a,result(a,40));duplicate.completed(a,result(a,40));duplicate.completed(b,result(b,50));rejects(duplicate::requireCompleteMetadata);
  var duplicateTime=new ScamCaptureSequence(requests,zsl);
  duplicateTime.completed(a,result(a,40));duplicateTime.completed(b,result(b,40));rejects(duplicateTime::requireCompleteMetadata);
  var mismatch=new ScamCaptureSequence(requests,zsl);mismatch.completed(a,result(b,40));rejects(mismatch::requireCompleteMetadata);
  var failed=new ScamCaptureSequence(requests,zsl);failed.completed(a,result(a,40));failed.completed(b,result(b,50));failed.failed("buffer lost");rejects(failed::requireCompleteMetadata);
  var invalid=new ScamCaptureSequence(requests,zsl);var r=result(a,40);r.put(CaptureResult.SENSOR_EXPOSURE_TIME,0L);invalid.completed(a,r);rejects(invalid::requireCompleteMetadata);
  var q3=List.of(request(2,0,ImageFrame.CaptureRole.SHORT),request(2,1,ImageFrame.CaptureRole.SHORT),request(2,2,ImageFrame.CaptureRole.LONG));
  var p4=List.of(past(63),past(64),past(65),past(66));
  var stockShape=new ScamCaptureSequence(q3,p4);
  for(int i=2;i>=0;--i)stockShape.completed(q3.get(i),result(q3.get(i),83+i));
  var all7=new ArrayList<ImageFrame>(p4);all7.add(raw(85));all7.add(raw(83));all7.add(raw(84));
  stockShape.bindAndValidate(all7);
  check(stockShape.futureCount==3&&stockShape.frameCount==7);
  check(all7.get(4).getCaptureRole()==ImageFrame.CaptureRole.LONG);
  check(all7.get(5).getCaptureRole()==ImageFrame.CaptureRole.SHORT);
  var onlyPast=new ScamCaptureSequence(List.of(),zsl);onlyPast.bindAndValidate(zsl);check(onlyPast.futureCount==0);
  var onlyFuture=new ScamCaptureSequence(requests,List.of());onlyFuture.completed(a,result(a,40));onlyFuture.completed(b,result(b,50));onlyFuture.bindAndValidate(List.of(raw(50),raw(40)));
  rejects(()->new ScamCaptureSequence(List.of(),List.of()));
  rejects(()->new ScamCaptureSequence(requests,List.of(zsl.get(0),zsl.get(0))));
  rejects(()->new ScamCaptureSequence(List.of(b,a),zsl));
  var tail=List.of(request(3,0,ImageFrame.CaptureRole.LONG),request(3,1,ImageFrame.CaptureRole.SHORT),request(3,2,ImageFrame.CaptureRole.EXTRA_SHORT));
  var realZsl=ScamCaptureSequence.stockZsl(tail,p4,70);
  for(int i=2;i>=0;i--)realZsl.completed(tail.get(i),result(tail.get(i),81+i));
  var joined=new ArrayList<ImageFrame>(p4);joined.add(raw(83));joined.add(raw(81));joined.add(raw(82));
  realZsl.bindAndValidate(joined);
  check(realZsl.futureCount==3 && joined.get(4).getCaptureRole()==ImageFrame.CaptureRole.EXTRA_SHORT);
  rejects(()->ScamCaptureSequence.stockZsl(tail,p4,65));
  rejects(()->ScamCaptureSequence.stockZsl(tail,p4,0));
  rejects(()->ScamCaptureSequence.stockZsl(tail,p4.subList(0,3),70));
  rejects(()->ScamCaptureSequence.stockZsl(q3,p4,70));
  var wrongTail=List.of(request(3,0,ImageFrame.CaptureRole.NORMAL),tail.get(1),tail.get(2));
  rejects(()->ScamCaptureSequence.stockZsl(wrongTail,p4,70));
  // P27: every capture series is tolerant. A result from before the shutter (a preview frame) is never bound to a RAW: that
  // request is dropped, the shot goes on with the RAWs that belong to it.
  var latePreview=ScamCaptureSequence.stockZsl(tail,p4,70);
  latePreview.completed(tail.get(0),result(tail.get(0),69));
  latePreview.completed(tail.get(1),result(tail.get(1),82));
  latePreview.completed(tail.get(2),result(tail.get(2),83));
  latePreview.requireCompleteMetadata();
  var lateFrames=new ArrayList<ImageFrame>(p4);lateFrames.add(raw(69));lateFrames.add(raw(82));lateFrames.add(raw(83));
  var lateDiscard=latePreview.bindAndValidate(lateFrames);
  check(lateDiscard.size()==1&&lateDiscard.get(0).timestamp==69&&latePreview.boundFutureCount()==2&&latePreview.droppedCount()==1);
  check(lateFrames.get(4).getCaptureRole()==null&&lateFrames.get(5).getCaptureRole()==ImageFrame.CaptureRole.SHORT);
  check(latePreview.droppedSummary().contains("LONG"));
  // The strict binding rules stay for a series built with the plain constructor (no shutter cutoff there: a foreign result).
  var strictLate=new ScamCaptureSequence(tail,p4);
  strictLate.completed(tail.get(0),result(tail.get(1),82));rejects(strictLate::requireCompleteMetadata);
  // SCAM Hybrid: post-shutter frames are optional extras (lost buffer / HAL failure / missing RAW drop that frame only)
  var hq=List.of(request(4,0,ImageFrame.CaptureRole.EXTRA_SHORT),request(4,1,ImageFrame.CaptureRole.EXTRA_SHORT),request(4,2,ImageFrame.CaptureRole.LONG));
  var hz=List.of(past(91),past(92),past(93));
  var hyb=ScamCaptureSequence.hybridZsl(hq,hz,95);
  hyb.lost(hq.get(0),"RAW buffer lost frame=34");hyb.completed(hq.get(0),result(hq.get(0),100));
  hyb.completed(hq.get(2),result(hq.get(2),102));hyb.requireCompleteMetadata();
  var hframes=new ArrayList<ImageFrame>(hz);hframes.add(raw(102));hframes.add(raw(100));
  var unmatched=hyb.bindAndValidate(hframes);
  check(unmatched.size()==1&&unmatched.get(0).timestamp==100&&hyb.boundFutureCount()==1&&hyb.droppedCount()==1);
  check(hframes.get(3).getCaptureRole()==ImageFrame.CaptureRole.LONG);
  var hyb2=ScamCaptureSequence.hybridZsl(hq,hz,95);hyb2.completed(hq.get(1),result(hq.get(1),101));
  var hf2=new ArrayList<ImageFrame>(hz);check(hyb2.bindAndValidate(hf2).isEmpty()&&hyb2.boundFutureCount()==0&&hyb2.droppedCount()==1);
  // P27: a buffered N frame that went missing (copy failed) is dropped, not fatal; the series needs at least one RAW.
  var hyb3=ScamCaptureSequence.hybridZsl(hq,hz,95);hyb3.lost(hq.get(2),"HAL capture failure=0");
  check(hyb3.bindAndValidate(List.of(hz.get(0),hz.get(1))).isEmpty()&&hyb3.presentCount()==2&&hyb3.boundFutureCount()==0&&hyb3.droppedCount()==1);
  check(hyb3.droppedSummary().contains("1 buffered N RAWs missing"));
  var nothing=ScamCaptureSequence.hybridZsl(hq,hz,95);for(var q:hq)nothing.lost(q,"RAW buffer lost");
  rejects(()->nothing.bindAndValidate(List.of()));
  var strictZsl=new ScamCaptureSequence(hq,hz);
  rejects(()->strictZsl.bindAndValidate(List.of(hz.get(0),hz.get(1)))); // strict: every buffered N is required
  // A result that belongs to another request of the series is never bound (tolerant: ignored; strict: fails).
  var hyb4=ScamCaptureSequence.hybridZsl(hq,hz,95);hyb4.completed(hq.get(0),result(hq.get(1),100));hyb4.requireCompleteMetadata();
  var h4=new ArrayList<ImageFrame>(hz);h4.add(raw(100));
  var h4Discard=hyb4.bindAndValidate(h4);check(h4Discard.size()==1&&h4Discard.get(0).timestamp==100&&hyb4.boundFutureCount()==0);
  var strictHyb4=new ScamCaptureSequence(hq,hz);strictHyb4.completed(hq.get(0),result(hq.get(1),100));rejects(strictHyb4::requireCompleteMetadata);
  // SCAM HDR series are tolerant as well (P27): a lost L leaves the others; the merge falls back to the Hybrid if its graph
  // cannot be built. The strict constructor still fails on it.
  var scamLost=ScamCaptureSequence.stockZsl(tail,p4,70);scamLost.lost(tail.get(0),"RAW buffer lost frame=7");
  scamLost.completed(tail.get(1),result(tail.get(1),82));scamLost.completed(tail.get(2),result(tail.get(2),83));scamLost.requireCompleteMetadata();
  var scamFrames=new ArrayList<ImageFrame>(p4);scamFrames.add(raw(82));scamFrames.add(raw(83));
  check(scamLost.bindAndValidate(scamFrames).isEmpty()&&scamLost.boundFutureCount()==2&&scamLost.droppedCount()==1&&scamLost.presentCount()==6);
  var strict=new ScamCaptureSequence(tail,p4);strict.lost(tail.get(0),"RAW buffer lost frame=7");rejects(strict::requireCompleteMetadata);
  // P27 tolerant series: foreign, duplicate and invalid results, null and duplicate RAWs are dropped, never fatal.
  var tq=List.of(request(5,0,ImageFrame.CaptureRole.NORMAL),request(5,1,ImageFrame.CaptureRole.LONG),request(5,2,ImageFrame.CaptureRole.EXTRA_SHORT));
  var tol=ScamCaptureSequence.hybridZsl(tq,hz,95);
  tol.completed(new CaptureRequest(null),result(new CaptureRequest(null),110));       // no identity
  tol.completed(request(9,0,ImageFrame.CaptureRole.NORMAL),result(tq.get(0),111));      // another series
  tol.completed(tq.get(0),result(tq.get(0),112));tol.completed(tq.get(0),result(tq.get(0),113)); // second result of #0
  var noTs=result(tq.get(1),114);noTs.put(CaptureResult.SENSOR_TIMESTAMP,null);tol.completed(tq.get(1),noTs); // invalid
  tol.completed(tq.get(2),result(tq.get(2),115));
  tol.requireCompleteMetadata();
  var tf=new ArrayList<ImageFrame>(hz);tf.add(null);tf.add(raw(112));tf.add(raw(112));tf.add(raw(114));tf.add(raw(115));
  var tolDiscard=tol.bindAndValidate(tf);
  check(tol.boundFutureCount()==2&&tol.presentCount()==5&&tol.droppedCount()==1&&tolDiscard.size()==3);
  check(tolDiscard.contains(null)&&tolDiscard.get(1).timestamp==112&&tolDiscard.get(2).timestamp==114);
  check(tol.firstBoundResult(ImageFrame.CaptureRole.NORMAL).get(CaptureResult.SENSOR_TIMESTAMP)==112L);
  // X300 Ultra 2026-10-06 shape (log 265-325): Hybrid normal-back, N#0 buffer lost, N#1-3 failed, L/S/ES arrived -> merged.
  var nb=new ArrayList<CaptureRequest>();
  for(int i=0;i<4;i++)nb.add(request(6,i,ImageFrame.CaptureRole.NORMAL));
  nb.add(request(6,4,ImageFrame.CaptureRole.LONG));nb.add(request(6,5,ImageFrame.CaptureRole.EXTRA_SHORT));nb.add(request(6,6,ImageFrame.CaptureRole.EXTRA_SHORT));
  var x300=ScamCaptureSequence.hybridFuture(nb,120);
  x300.lost(nb.get(0),"RAW buffer lost frame=27");for(int i=1;i<4;i++)x300.lost(nb.get(i),"HAL capture failure=0");
  for(int i=4;i<7;i++)x300.completed(nb.get(i),result(nb.get(i),130+i));
  x300.requireCompleteMetadata();
  var x300Frames=new ArrayList<ImageFrame>();for(int i=4;i<7;i++)x300Frames.add(raw(130+i));
  check(x300.bindAndValidate(x300Frames).isEmpty()&&x300.boundFutureCount()==3&&x300.droppedCount()==4);
  check(x300.firstBoundResult(ImageFrame.CaptureRole.NORMAL)==null&&x300.firstBoundResult(null)!=null);
  var allLost=ScamCaptureSequence.hybridFuture(nb,120);for(var q:nb)allLost.lost(q,"HAL capture failure=0");
  rejects(()->allLost.bindAndValidate(List.of()));
  rejects(()->ScamCaptureSequence.hybridFuture(List.of(),120));
  rejects(()->ScamCaptureSequence.hybridFuture(nb,0));
  // P27: a frame delivered at another exposure keeps the role its measured exposure gives (owner's 'LONG: ISO 320/640').
  var rq=List.of(request(7,0,ImageFrame.CaptureRole.LONG),request(7,1,ImageFrame.CaptureRole.LONG));
  var rc=ScamCaptureSequence.hybridZsl(rq,hz,95);
  rc.reclassify(rq.get(0),ImageFrame.CaptureRole.NORMAL);
  rc.completed(rq.get(0),result(rq.get(0),140));rc.completed(rq.get(1),result(rq.get(1),141));
  var rf=new ArrayList<ImageFrame>(hz);rf.add(raw(140));rf.add(raw(141));
  check(rc.bindAndValidate(rf).isEmpty()&&rc.boundFutureCount()==2);
  check(rf.get(3).getCaptureRole()==ImageFrame.CaptureRole.NORMAL&&rf.get(4).getCaptureRole()==ImageFrame.CaptureRole.LONG);
  check(rc.firstBoundResult(ImageFrame.CaptureRole.NORMAL).get(CaptureResult.SENSOR_TIMESTAMP)==140L&&rc.droppedSummary().contains("used as NORMAL"));
  rc.reclassify(request(8,0,ImageFrame.CaptureRole.LONG),ImageFrame.CaptureRole.NORMAL);rc.requireCompleteMetadata(); // foreign: noted
  var strictRc=new ScamCaptureSequence(rq,hz);strictRc.reclassify(request(8,0,ImageFrame.CaptureRole.LONG),ImageFrame.CaptureRole.NORMAL);
  rejects(strictRc::requireCompleteMetadata);
  // SCAM HDR normal-back: seven requests, or six without L (L built from the N frames).
  var six=new ArrayList<CaptureRequest>();
  for(int i=0;i<4;i++)six.add(request(10,i,ImageFrame.CaptureRole.NORMAL));
  six.add(request(10,4,ImageFrame.CaptureRole.SHORT));six.add(request(10,5,ImageFrame.CaptureRole.EXTRA_SHORT));
  check(ScamCaptureSequence.stockNormalBack(six,150).futureCount==6);
  var badSix=new ArrayList<CaptureRequest>(six);badSix.set(4,request(10,4,ImageFrame.CaptureRole.LONG));
  rejects(()->ScamCaptureSequence.stockNormalBack(badSix,150));
  System.out.println("PASS: "+checks+" SCAM request/result/RAW checks, including reordering, missing frames, stale series, invalid metadata and HAL failure; tolerant series drop lost, foreign, duplicate and invalid frames, reclassify by measured exposure, and fail only when no RAW arrived");
 }
}'''
with tempfile.TemporaryDirectory() as d:
 p=Path(d)
 for name,content in STUBS.items():
  if name=='Check.java':content=CHECK
  target=p/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(content)
 sources=[ROOT/'app/src/main/java'/BASE/x for x in ['processing/ImageFrame.java','capture/ScamCaptureSequence.java']]
 subprocess.run(['java','-m','jdk.compiler/com.sun.tools.javac.Main','-d',d,*map(str,p.rglob('*.java')),*map(str,sources)],check=True)
 subprocess.run(['java','-cp',d,'Check'],check=True)

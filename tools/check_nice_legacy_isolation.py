from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
BASE = 'com/particlesdevs/photoncamera/'
STUBS = {
'android/util/Range.java': 'package android.util; public class Range<T> { T lo,hi; public Range(T a,T b){lo=a;hi=b;} public T getLower(){return lo;} public T getUpper(){return hi;} }',
'android/util/SizeF.java': 'package android.util; public class SizeF { public float getWidth(){return 10;} }',
'android/graphics/Rect.java': 'package android.graphics; public class Rect { public int width(){return 4096;} }',
'android/os/Build.java': 'package android.os; public class Build { public static class VERSION {public static int SDK_INT=35;} public static class VERSION_CODES {public static int R=30;} }',
'android/hardware/camera2/CameraMetadata.java': 'package android.hardware.camera2; public class CameraMetadata {public static final int LENS_OPTICAL_STABILIZATION_MODE_ON=1;}',
'android/hardware/camera2/params/TonemapCurve.java': 'package android.hardware.camera2.params; public class TonemapCurve { public TonemapCurve(float[] a,float[] b,float[] c){} }',
BASE+'util/Log.java': 'package com.particlesdevs.photoncamera.util; public class Log { public static void i(String a,String b){} public static void v(String a,String b){} public static void d(String a,String b){} public static void w(String a,String b){} }',
BASE+'api/CameraMode.java': 'package com.particlesdevs.photoncamera.api; public enum CameraMode {PHOTO,NIGHT,MOTION,RAWVIDEO}',
BASE+'capture/CaptureController.java': '''package com.particlesdevs.photoncamera.capture; import android.hardware.camera2.*;
public class CaptureController { public static CameraCharacteristics mCameraCharacteristics=new CameraCharacteristics(); public static CaptureResult mPreviewCaptureResult=new CaptureResult(); public long mPreviewExposureTime=8333326; public int mPreviewIso=547,oisMode=0,exposureBalanceIsoLimit=100; public float exposureBalanceMultiplier=99,exposureBalanceShutterLimit=.001f;
public Param getParamController(){return new Param();} public static class Param {public double getCurrentExposureValue(){return 0;} public double getCurrentISOValue(){return 0;}} }''',
BASE+'app/PhotonCamera.java': '''package com.particlesdevs.photoncamera.app; import com.particlesdevs.photoncamera.api.CameraMode; import com.particlesdevs.photoncamera.capture.CaptureController;
public class PhotonCamera {public static Settings getSettings(){return new Settings();} public static Gyro getGyro(){return new Gyro();} public static CaptureController getCaptureController(){return new CaptureController();} public static class Settings {public float exposureCompensation=4;public CameraMode selectedMode=CameraMode.NIGHT;public boolean eisPhoto=false;} public static class Gyro {public boolean getTripod(){return false;}public int getFilteredShakiness(){return 400;}}}''',
BASE+'settings/PreferenceKeys.java': '''package com.particlesdevs.photoncamera.settings; public class PreferenceKeys {
public static boolean nice=true,poison=true;public static int ev=4;
public static boolean isVivoNiceEnabled(){return nice;}
static void legacy(){if(poison)throw new AssertionError("NICE read legacy capture tuning");}
public static float getMaxHdrRatio(){legacy();return 9.8f;}public static boolean isTetModelEnabled(){legacy();return false;}public static float getLongFrameShutterCapPeriods(){legacy();return 2;}
public static int getShortExposureEvValue(){return ev;} public static int getLongExposureEvValue(){return ev;} public static int getLongFrameCountValue(){return 1;}public static int getShortFrameCountValue(){return 1;}public static int getHighlightSuppressionValue(){legacy();return 100;}public static int getBracketingMode(){legacy();return 2;}public static int getAntibandingHz(){legacy();return 60;}
}''',
BASE+'processing/parameters/ExposureIndex.java': 'package com.particlesdevs.photoncamera.processing.parameters; public class ExposureIndex {public static final long sec=1000000000; public static double time2sec(long x){return x/1e9;} public static long sec2time(double x){return (long)(x*1e9);}public static String sec2string(double x){return ""+x;}}',
'Check.java': '''import com.particlesdevs.photoncamera.processing.parameters.*;import com.particlesdevs.photoncamera.capture.*;import com.particlesdevs.photoncamera.settings.*;import android.hardware.camera2.*;
public class Check {
static void check(boolean x){if(!x)throw new AssertionError();}
static double product(CaptureRequest.Builder b){return (double)b.get(CaptureRequest.SENSOR_EXPOSURE_TIME)*b.get(CaptureRequest.SENSOR_SENSITIVITY);}
public static void main(String[] a){
var cc=new CaptureController();var b=new CaptureRequest.Builder(); double ref=(double)cc.mPreviewExposureTime*cc.mPreviewIso;
IsoExpoSelector.HDR=true;
for(int step=0;step<4;step++){var p=IsoExpoSelector.GenerateExpoPair(step,cc);check(p.iso==547 && p.exposure==8333326);}
for(int ev=1;ev<=8;ev++) {PreferenceKeys.ev=ev;IsoExpoSelector.setHdrPlusExpo(b,0,cc);check(product(b)==ref);IsoExpoSelector.setLongExpo(b,cc);check(Math.abs(product(b)/ref/Math.scalb(1.,ev)-1)<.002);check(b.get(CaptureRequest.SENSOR_EXPOSURE_TIME)<=1000000000L);check(IsoExpoSelector.setUltraShortExpo(b,cc));check(Math.abs(product(b)/ref*Math.scalb(1.,ev)-1)<.02);}
PreferenceKeys.ev=4;IsoExpoSelector.setMeasuredBracketBase(10000000,100,cc);IsoExpoSelector.setLongExpo(b,cc);check(product(b)==16000000000.);
PreferenceKeys.nice=false;PreferenceKeys.poison=false;IsoExpoSelector.setMeasuredBracketBase(8333326,547,cc);IsoExpoSelector.setLongExpo(b,cc);check(Math.abs(product(b)/ref-Math.sqrt(9.8))<.001);IsoExpoSelector.setUltraShortExpo(b,cc);check(Math.abs(product(b)/ref-1/Math.sqrt(9.8))<.001);
System.out.println("PASS: production IsoExpoSelector, NICE EV 1..8, poisoned legacy settings, measured base and legacy ratio regression");}}
'''
}
keys = {
'CaptureRequest': {'Integer':['TONEMAP_MODE','EDGE_MODE','NOISE_REDUCTION_MODE','CONTROL_AE_MODE','SENSOR_SENSITIVITY'], 'Long':['SENSOR_EXPOSURE_TIME'], 'android.hardware.camera2.params.TonemapCurve':['TONEMAP_CURVE']},
'CaptureResult': {'Float':['CONTROL_ZOOM_RATIO'],'android.graphics.Rect':['SCALER_CROP_REGION']},
'CameraCharacteristics': {'android.util.Range<Integer>':['SENSOR_INFO_SENSITIVITY_RANGE'],'android.util.Range<Long>':['SENSOR_INFO_EXPOSURE_TIME_RANGE'],'Integer':['SENSOR_MAX_ANALOG_SENSITIVITY'],'float[]':['LENS_INFO_AVAILABLE_FOCAL_LENGTHS'],'int[]':['LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION'],'android.util.SizeF':['SENSOR_INFO_PHYSICAL_SIZE'],'android.graphics.Rect':['SENSOR_INFO_ACTIVE_ARRAY_SIZE']}}
for cls,types in keys.items():
 text='package android.hardware.camera2; public class '+cls+' { public static class Key<T>{} '
 for typ,names in types.items():
  for name in names:text+=f'public static final Key<{typ}> {name}=new Key<>();'
 text+='public <T>T get(Key<T> k){'
 if cls=='CameraCharacteristics':text+='if(k==SENSOR_INFO_SENSITIVITY_RANGE)return (T)new android.util.Range<Integer>(50,12800);if(k==SENSOR_INFO_EXPOSURE_TIME_RANGE)return (T)new android.util.Range<Long>(1000L,1000000000L);if(k==SENSOR_MAX_ANALOG_SENSITIVITY)return (T)Integer.valueOf(6400);'
 text+='return null;}'
 if cls=='CaptureRequest':text+='public static final int TONEMAP_MODE_CONTRAST_CURVE=0,EDGE_MODE_OFF=0,NOISE_REDUCTION_MODE_OFF=0,CONTROL_AE_MODE_OFF=0; public static class Builder {java.util.Map<Key<?>,Object> m=new java.util.HashMap<>(); public <T>void set(Key<T> k,T v){m.put(k,v);}public <T>T get(Key<T> k){return (T)m.get(k);}}'
 STUBS['android/hardware/camera2/'+cls+'.java']=text+'}'
with tempfile.TemporaryDirectory() as d:
 p=Path(d)
 for name,content in STUBS.items():
  target=p/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(content)
 sources=[ROOT/'app/src/main/java'/BASE/'processing/parameters'/n for n in ['IsoExpoSelector.java','HdrBracketFactors.java','TetModel.java']]
 subprocess.run(['java','-m','jdk.compiler/com.sun.tools.javac.Main','-d',d,*map(str,p.rglob('*.java')),*map(str,sources)],check=True)
 subprocess.run(['java','-cp',d,'Check'],check=True)

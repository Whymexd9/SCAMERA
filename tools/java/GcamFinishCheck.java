import com.particlesdevs.photoncamera.processing.parameters.GcamFinishMath;
import com.particlesdevs.photoncamera.capture.SceneDistributionMeter;
import java.nio.*;
public final class GcamFinishCheck {
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    public static void main(String[] args){
        for(double g:new double[]{.25,.5,1,1.1,1.25,1.5,1.75,2,4,16}){
            double[] p=GcamFinishMath.splitGain(g);
            check(Math.abs(p[0]*p[1]-g)<1e-12,"Gain product");
            check(p[0]>=1 && p[0]<=1.5,"Rolloff bounds");
        }
        check(GcamFinishMath.varianceScale(1,1)==1,"Single-frame noise identity");
        check(GcamFinishMath.varianceScale(4,4)==1.0/16,"Averaging four frames and four pixels");
        check(GcamFinishMath.effectiveSamples(4,4)==4,"Equal weights");
        check(GcamFinishMath.effectiveSamples(1.5,1.25)==1.8,"Unequal weights");
        check(GcamFinishMath.effectiveSamples(Double.NaN,0)==1,"Invalid weights");
        check(SceneDistributionMeter.nextSteps(.01,.1,0,0,1.0/3,-6,6)==1,"Dark scene");
        check(SceneDistributionMeter.nextSteps(.01,1,0,0,1.0/3,-6,6)==0,"Clipped high protects exposure");
        check(SceneDistributionMeter.nextSteps(.5,.9,0,0,1.0/3,-6,6)==-1,"Bright scene");
        check(SceneDistributionMeter.nextSteps(.001,.1,6,0,1.0/3,-6,6)==6,"EV bounds");
        for(int block:new int[]{1,2,4}){
            ByteBuffer raw=ByteBuffer.allocate(128*96*2).order(ByteOrder.LITTLE_ENDIAN);
            while(raw.hasRemaining())raw.putShort((short)1200);raw.rewind();
            double[] p=SceneDistributionMeter.measure(raw,128,96,256,block,200,4200);
            check(p!=null && Math.abs(p[0]-.25)<.002 && Math.abs(p[1]-.25)<.002,"RAW histogram/CFA");
            check(raw.position()==0,"Buffer ownership");
        }
        System.out.println("Finish gain split, noise rescale, histogram AE and limits PASS");
    }
}

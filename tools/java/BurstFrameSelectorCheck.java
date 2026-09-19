import com.particlesdevs.photoncamera.capture.BurstFrameSelector;
import com.particlesdevs.photoncamera.capture.BurstFrameSelector.Sample;
import com.particlesdevs.photoncamera.control.GyroBlurEstimate;
import com.particlesdevs.photoncamera.control.GyroBurst;
public final class BurstFrameSelectorCheck {
    static Sample s(double sharp,double blur,double focus,boolean moving,boolean bracket) {
        return new Sample(sharp,blur,focus,10000000,400,bracket,moving);
    }
    static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
    public static void main(String[] args) {
        Sample[] a={s(1,1,2,false,false),s(1.1,30,2,false,false),s(99,0,2,false,true)};
        check(BurstFrameSelector.reference(a)==0,"Blur must influence reference; bracket cannot win");
        a[0]=s(1,Double.NaN,2,false,false);
        check(BurstFrameSelector.reference(a)==1,"Unknown gyro must not look stable");
        a=new Sample[]{s(10,0,0,false,false),s(1,0,2,false,false),s(2,0,2,true,false)};
        check(BurstFrameSelector.reference(a)==1,"Latest settled focus plane");
        a=new Sample[]{s(1,0,2,false,false),s(.9,0,2,false,false),s(.8,0,2,false,false),s(.1,0,2,false,false),s(0,0,2,false,true)};
        boolean[] keep=BurstFrameSelector.keep(a,0);
        check(!keep[3] && keep[4],"Reject blurred donor but preserve bracket");
        a=new Sample[]{s(1,0,2,false,false),s(.1,0,2,false,false),s(.1,0,2,false,false)};
        keep=BurstFrameSelector.keep(a,0);
        check(keep[0]&&keep[1]&&keep[2],"Optional filter rollback");
        check(BurstFrameSelector.reference(new Sample[0])==-1,"Empty burst");
        GyroBurst g=new GyroBurst(4);g.samples=2;
        g.movementss[0][0]=.001f;g.movementss[0][1]=-.001f;
        double blur=GyroBlurEstimate.pixels(g,2000,2000,4000,3000);
        check(Math.abs(blur-2)<1e-5,"Return motion must retain blur extent");
        g.movementss[0][1]=.001f;
        check(Math.abs(GyroBlurEstimate.pixels(g,2000,2000,4000,3000)-4)<1e-5,"Twice exposure path");
        g.samples=0;check(Double.isNaN(GyroBlurEstimate.pixels(g,2000,2000,4000,3000)),"Missing gyro");
        System.out.println("Burst selection / focus / exposure-integrated gyro extent PASS");
    }
}

import com.particlesdevs.photoncamera.control.GyroBurst;
import com.particlesdevs.photoncamera.control.GyroExposureWindow;

public final class GyroExposureWindowCheck {
    static void near(float a, float b) {
        if (Math.abs(a-b)>1e-5f) throw new AssertionError(a+" != "+b);
    }
    static GyroBurst get(long[] starts, GyroBurst ring, int next, long t, long duration) {
        return GyroExposureWindow.extract(starts,ring,next,t,duration,true);
    }
    public static void main(String[] args) {
        long ms=1_000_000L;
        GyroBurst ring=new GyroBurst(4);
        // Wrapped ring: slots 2,3,0,1 hold intervals [10,20] ... [40,50].
        long[] starts={30*ms,40*ms,10*ms,20*ms};
        for(int i=0;i<4;i++) {ring.timestampss[i]=starts[i]+10*ms;ring.movementss[0][i]=1;}
        GyroBurst b=get(starts,ring,2,25*ms,20*ms);
        if(b.samples!=3 || b.timestampss[0]!=30*ms || b.timestampss[2]!=45*ms)
            throw new AssertionError("Wrong exposure window/order");
        near(b.movementss[0][0],.5f);near(b.movementss[0][2],.5f);
        near(b.integrated[0],-2);near(b.shakiness,4);
        // Duration doubles => squared path quadruples, with the same angular rate.
        near(get(starts,ring,2,25*ms,10*ms).shakiness,1);
        if(get(starts,ring,2,5*ms,20*ms).samples!=0) throw new AssertionError("Missing start accepted");
        if(get(starts,ring,2,45*ms,20*ms).samples!=0) throw new AssertionError("Missing end accepted");
        if(GyroExposureWindow.extract(starts,ring,2,25*ms,20*ms,false).samples!=0)
            throw new AssertionError("Unmatched clocks accepted");
        if(get(starts,ring,2,Long.MAX_VALUE-5,10).samples!=0) throw new AssertionError("Overflow accepted");
        if(get(starts,ring,2,25*ms,0).samples!=0) throw new AssertionError("Zero exposure accepted");
        // Return motion remains blur even if the signed integral cancels.
        ring.movementss[0][0]=-1;
        b=get(starts,ring,2,20*ms,20*ms);near(b.integrated[0],0);near(b.shakiness,4);
        GyroBurst clone=b.clone();b.movementss[0][0]=99;b.timestampss[0]=99;b.integrated[0]=99;
        near(clone.movementss[0][0],1);near(clone.integrated[0],0);
        if(clone.timestampss[0]!=30*ms) throw new AssertionError("Clone alias");
        clone.recalculateShakiness();near(clone.shakiness,4);
        ring.movementss[0][0]=Float.NaN;
        if(get(starts,ring,2,25*ms,20*ms).samples!=0) throw new AssertionError("NaN accepted");
        System.out.println("Gyro PASS: exposure start, fractional edges, ring wrap, squared path, missing samples/clocks, reversal, deep clone");
    }
}

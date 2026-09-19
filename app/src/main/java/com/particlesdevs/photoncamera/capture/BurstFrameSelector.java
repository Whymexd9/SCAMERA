package com.particlesdevs.photoncamera.capture;

/** Required validity, optional focus/blur filters and sharpness donor rejection.
 * Thresholds are SCAMERA policy; not recovered GCam tuning. */
public final class BurstFrameSelector {
    private BurstFrameSelector() {}
    public static final class Sample {
        public final double sharpness, blur, focus, exposure;
        public final int iso;
        public final boolean bracket, moving;
        public Sample(double s, double b, double f, double e, int i, boolean bracket, boolean moving) {
            sharpness=s; blur=b; focus=f; exposure=e; iso=i; this.bracket=bracket; this.moving=moving;
        }
        boolean valid() { return exposure > 0 && Double.isFinite(exposure) && iso > 0; }
    }
    private static double score(Sample s, boolean gyro) {
        double q = Double.isFinite(s.sharpness) && s.sharpness >= 0 ? s.sharpness : 0;
        return q / (gyro ? 1 + Math.min(s.blur, 100) * .05 : 1);
    }
    public static int reference(Sample[] frames) {
        boolean settled = false, gyro = true;
        for (Sample s:frames) if (s.valid() && !s.bracket) {
            settled |= !s.moving; gyro &= Double.isFinite(s.blur) && s.blur >= 0;
        }
        double targetFocus=Double.NaN;
        for(Sample s:frames) if(s.valid() && !s.bracket && !s.moving && Double.isFinite(s.focus))
            targetFocus=s.focus;
        int best = -1; double max = -1;
        for (int i=0;i<frames.length;i++) {
            Sample s=frames[i];
            if (!s.valid() || s.bracket || (settled && s.moving)) continue;
            if(Double.isFinite(targetFocus) && Double.isFinite(s.focus)
                    && Math.abs(s.focus-targetFocus)>Math.max(.15,Math.abs(targetFocus)*.1)) continue;
            double q=score(s,gyro);
            // Equal quality favours the latest scene state.
            if (q >= max) { max=q; best=i; }
        }
        if (best < 0) for (int i=0;i<frames.length;i++) if (frames[i].valid()) return i;
        return best;
    }
    public static boolean[] keep(Sample[] frames, int ref) {
        boolean[] keep=new boolean[frames.length];
        if (ref<0 || ref>=frames.length) return keep;
        Sample base=frames[ref]; int count=0;
        for (int i=0;i<frames.length;i++) {
            Sample s=frames[i]; boolean accept=s.valid();
            if (accept && !s.bracket && i!=ref) {
                if (Double.isFinite(s.focus) && Double.isFinite(base.focus))
                    accept = Math.abs(s.focus-base.focus) <= Math.max(.15, Math.abs(base.focus)*.1);
                if (s.moving && !base.moving) accept=false;
                boolean comparable=s.iso==base.iso && Math.abs(s.exposure/base.exposure-1)<.02;
                if (comparable && Double.isFinite(s.sharpness) && base.sharpness>0)
                    accept &= s.sharpness >= .35*base.sharpness;
            }
            keep[i]=accept; if(accept)count++;
        }
        // Optional rejection cannot collapse a viable burst below three frames.
        if(count<Math.min(3,frames.length))
            for(int i=0;i<frames.length;i++) keep[i]=frames[i].valid();
        keep[ref]=true;
        return keep;
    }
}

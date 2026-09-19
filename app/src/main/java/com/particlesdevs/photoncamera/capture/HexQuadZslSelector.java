package com.particlesdevs.photoncamera.capture;

import java.util.List;

/** Pure selection policy: six chronological, distinct, measured pre-shutter RAWs. */
public final class HexQuadZslSelector {
    private HexQuadZslSelector() {}
    public static final class Sample {
        public final long timestamp, exposure;
        public final int iso;
        public final double quality;
        public Sample(long t,long e,int i){this(t,e,i,Double.NaN);}
        public Sample(long t,long e,int i,double q){timestamp=t;exposure=e;iso=i;quality=q;}
        boolean valid(){return timestamp>0&&exposure>0&&iso>=50&&iso<=12800;}
    }
    public static int[] select(List<Sample> frames) {
        return select(frames,6);
    }
    public static int[] select(List<Sample> frames,int count) {
        return select(frames,count,false);
    }
    public static int[] select(List<Sample> frames,int count,boolean preferSharp) {
        if(count<3 || count>40)return new int[0];
        int end=frames.size()-1;
        // A just-delivered Image can precede its result callback. Use the last
        // fully paired frame, never invent metadata for that unfinished pair.
        while(end>=0&&!frames.get(end).valid())--end;
        if(end<count-1)return new int[0];
        Sample ref=frames.get(end);int[] indices=new int[count];
        final long maxAge=Math.max(Math.max(1_000_000_000L,count*100_000_000L),ref.exposure*(count+2L));
        for(int k=0;k<count;++k){
            int i=end-count+1+k;Sample s=frames.get(i);
            if(!s.valid()||s.iso!=ref.iso||s.exposure!=ref.exposure||
                    ref.timestamp-s.timestamp>maxAge||
                    (k>0&&s.timestamp<=frames.get(i-1).timestamp))return new int[0];
            indices[k]=i;
        }
        if (!preferSharp) return indices;
        // Preserve a contiguous chronological burst and its CFA subpixel motion.
        // Consider at most 250 ms of earlier windows, never cross an AE change.
        double best = windowQuality(frames,end-count+1,end);
        int bestEnd = end;
        if (!Double.isFinite(best)) return indices;
        for (int candidateEnd=end-1; candidateEnd>=count-1; --candidateEnd) {
            long delay=ref.timestamp-frames.get(candidateEnd).timestamp;
            if (delay>250_000_000L) break;
            int first=candidateEnd-count+1;
            Sample s=frames.get(first), next=frames.get(first+1);
            if (!s.valid() || s.iso!=ref.iso || s.exposure!=ref.exposure
                    || s.timestamp>=next.timestamp || ref.timestamp-s.timestamp>maxAge) break;
            double score=windowQuality(frames,first,candidateEnd);
            // Require a material improvement and favour shutter-time content.
            score/=1.0+0.25*delay/250_000_000.0;
            if (Double.isFinite(score) && score>best*1.10+1e-6) {
                best=score;bestEnd=candidateEnd;
            }
        }
        for(int k=0;k<count;k++)indices[k]=bestEnd-count+1+k;
        return indices;
    }
    private static double windowQuality(List<Sample> frames,int first,int last) {
        double sum=0,min=Double.POSITIVE_INFINITY;
        for(int i=first;i<=last;i++) {
            double q=frames.get(i).quality;
            if(!Double.isFinite(q)||q<0)return Double.NaN;
            sum+=q;min=Math.min(min,q);
        }
        return 0.5*min+0.5*sum/(last-first+1);
    }
}

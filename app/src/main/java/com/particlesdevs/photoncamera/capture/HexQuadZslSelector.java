package com.particlesdevs.photoncamera.capture;

import java.util.List;

/** Pure selection policy: six chronological, distinct, measured pre-shutter RAWs. */
public final class HexQuadZslSelector {
    private HexQuadZslSelector() {}
    public static final class Sample {
        public final long timestamp, exposure;
        public final int iso;
        public Sample(long t,long e,int i){timestamp=t;exposure=e;iso=i;}
        boolean valid(){return timestamp>0&&exposure>0&&iso>=50&&iso<=12800;}
    }
    public static int[] select(List<Sample> frames) {
        int end=frames.size()-1;
        // A just-delivered Image can precede its result callback. Use the last
        // fully paired frame, never invent metadata for that unfinished pair.
        while(end>=0&&!frames.get(end).valid())--end;
        if(end<5)return new int[0];
        Sample ref=frames.get(end);int[] indices=new int[6];
        final long maxAge=Math.max(1_000_000_000L,ref.exposure*8);
        for(int k=0;k<6;++k){
            int i=end-5+k;Sample s=frames.get(i);
            if(!s.valid()||s.iso!=ref.iso||s.exposure!=ref.exposure||
                    ref.timestamp-s.timestamp>maxAge||
                    (k>0&&s.timestamp<=frames.get(i-1).timestamp))return new int[0];
            indices[k]=i;
        }
        return indices;
    }
}

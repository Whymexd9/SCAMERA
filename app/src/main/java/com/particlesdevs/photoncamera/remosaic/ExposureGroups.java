package com.particlesdevs.photoncamera.remosaic;

import java.util.*;

/** Native calls must never mix roles or exposure/ISO. Normal group is always first. */
public final class ExposureGroups {
    public static final class Sample {
        public final long timestamp, exposure;
        public final int iso, role;
        public Sample(long t,long e,int i,int r){timestamp=t;exposure=e;iso=i;role=r;}
    }
    public static List<List<Integer>> split(List<Sample> frames) {
        Map<String,List<Integer>> groups=new LinkedHashMap<>();Set<Long> timestamps=new HashSet<>();
        String normalKey=null;
        for(int i=0;i<frames.size();i++) {
            Sample f=frames.get(i);
            if(f.timestamp<=0 || f.exposure<=0 || f.iso<=0 || f.role<0 || f.role>2 || !timestamps.add(f.timestamp))
                throw new IllegalArgumentException("MFSR: неполные метаданные брекетинга");
            String key=f.role+":"+f.iso+":"+f.exposure;
            if(f.role==0) {
                if(normalKey!=null && !normalKey.equals(key))throw new IllegalArgumentException("MFSR: основная серия должна иметь равную экспозицию");
                normalKey=key;
            }
            groups.computeIfAbsent(key,k->new ArrayList<>()).add(i);
        }
        if(normalKey==null || groups.get(normalKey).size()<3)
            throw new IllegalArgumentException("MFSR: нужно минимум три основных кадра");
        List<List<Integer>> out=new ArrayList<>();out.add(groups.remove(normalKey));out.addAll(groups.values());return out;
    }
}

package com.particlesdevs.photoncamera.capture;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Pairs camera-start metadata and images in either arrival order. Owns pending images until delivery. */
public final class TimestampFrameRouter<T> {
    private final LinkedHashMap<Long,Boolean> requests=new LinkedHashMap<>();
    private final LinkedHashMap<Long,T> pending=new LinkedHashMap<>();
    private final BiConsumer<T,Boolean> deliver;
    private final Consumer<T> release;
    public TimestampFrameRouter(BiConsumer<T,Boolean> deliver,Consumer<T> release){this.deliver=deliver;this.release=release;}
    public synchronized void request(long timestamp,boolean still){
        T image=pending.remove(timestamp);
        if(image!=null){deliver.accept(image,still);return;}
        requests.put(timestamp,still);
        while(requests.size()>64)requests.remove(requests.keySet().iterator().next());
    }
    public synchronized void image(long timestamp,T image){
        Boolean still=requests.remove(timestamp);
        if(still!=null){deliver.accept(image,still);return;}
        T duplicate=pending.put(timestamp,image);if(duplicate!=null)release.accept(duplicate);
        // Leave spare slots in a six-image reader so missing metadata cannot stall the camera.
        while(pending.size()>3){Map.Entry<Long,T> old=pending.entrySet().iterator().next();pending.remove(old.getKey());release.accept(old.getValue());}
    }
    public synchronized void clear(){for(T image:pending.values())release.accept(image);pending.clear();requests.clear();}
}

package com.particlesdevs.photoncamera.capture;

import java.util.LinkedHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Owns at most two preview images while waiting for their exact CaptureResult. */
public final class PreviewFrameMatcher<T, R> {
    private final LinkedHashMap<Long,T> images = new LinkedHashMap<>();
    private final LinkedHashMap<Long,R> results = new LinkedHashMap<>();
    private final BiConsumer<T,R> deliver;
    private final Consumer<T> release;
    public PreviewFrameMatcher(BiConsumer<T,R> deliver, Consumer<T> release) {
        this.deliver=deliver; this.release=release;
    }
    public synchronized void image(long timestamp, T image) {
        R result=results.remove(timestamp);
        if(result!=null) { deliver.accept(image,result); return; }
        T old=images.put(timestamp,image);
        if(old!=null) release.accept(old);
        while(images.size()>2) release.accept(images.remove(images.keySet().iterator().next()));
    }
    public synchronized void result(long timestamp,R result) {
        T image=images.remove(timestamp);
        if(image!=null) { deliver.accept(image,result); return; }
        results.put(timestamp,result);
        while(results.size()>16) results.remove(results.keySet().iterator().next());
    }
    public synchronized void clear() {
        for(T image:images.values()) release.accept(image);
        images.clear(); results.clear();
    }
}

package com.particlesdevs.photoncamera.processing;

/**
 * P220: a progress event of the shot being processed for the processing ring (ProcessingEventsListener.onProcessingChanged;
 * a Float there stays a milestone of the pipeline): the shot is large (above 16 MP: a long merge follows, the ring does not run
 * ahead on the last 12 MP shot's time), or the merge worker's measured progress.
 */
public final class ProcessingProgress {
    /** True: the large-shot hint; false: {@link #merge} = the merge's share done (0..1). */
    public final boolean large;
    public final float merge;

    private ProcessingProgress(boolean large, float merge) {
        this.large = large;
        this.merge = merge;
    }

    public static ProcessingProgress large() {
        return new ProcessingProgress(true, 0f);
    }

    public static ProcessingProgress merge(float share) {
        return new ProcessingProgress(false, Math.max(0f, Math.min(1f, share)));
    }
}

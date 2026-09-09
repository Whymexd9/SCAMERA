package com.particlesdevs.photoncamera.processing;

/**
 * The colour and tone transform of the last processed shot, kept so the
 * viewfinder can apply the same look to the live stream.
 *
 * <p>This is the cheap half of the live viewfinder. Running the whole pipeline
 * per frame is not possible at viewfinder rates, but the part the user actually
 * wants to preview - tonemapping, shadow and highlight placement, white balance
 * - is a small, per-pixel transform once it has been derived. So it is derived
 * once per shot at full quality and then replayed on the preview stream for
 * almost nothing.
 *
 * <p>This is the same trade Gharbi et al. make in "Deep Bilateral Learning for
 * Real-Time Image Enhancement" and Chen et al. in "Bilateral Guided Upsampling":
 * fit the operator on a small image, apply it cheaply at full resolution.
 * GCam does it too - its own timing dump shows ProcessLowFrequency followed by
 * GuidedUpsample.
 *
 * <p>What this cannot show: anything that needs a burst. Merged noise reduction
 * and MFSR detail are absent from the viewfinder by construction, so the saved
 * photo stays cleaner and sharper than the preview. The look will match; the
 * detail will not.
 *
 * <p>Held statically because the producer (the processing pipeline) and the
 * consumer (the GL preview renderer) have no other connection. Access is
 * synchronized: the pipeline writes on a background thread, the renderer reads
 * on the GL thread.
 */
public final class PreviewLook {

    /** Samples in the tone curve; matches AutoExposureCurve's CURVE_SIZE. */
    public static final int CURVE_SIZE = 1024;

    private static final Object LOCK = new Object();

    private static float[] toneCurve;
    /** Bumped whenever toneCurve changes, so the renderer knows to re-upload. */
    private static int version = 0;

    private PreviewLook() {}

    /**
     * Publish the tone curve of the shot just processed.
     *
     * @param curve display-encoded response over [0,1]; copied, so the caller
     *              may reuse its array
     */
    public static void setToneCurve(float[] curve) {
        if (curve == null || curve.length == 0) return;
        synchronized (LOCK) {
            toneCurve = curve.clone();
            version++;
        }
    }

    /** The current curve, or null if no shot has been processed yet. */
    public static float[] getToneCurve() {
        synchronized (LOCK) {
            return toneCurve == null ? null : toneCurve.clone();
        }
    }

    /** Changes when the curve changes; cheap to poll from the GL thread. */
    public static int getVersion() {
        synchronized (LOCK) {
            return version;
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            toneCurve = null;
            version++;
        }
    }
}

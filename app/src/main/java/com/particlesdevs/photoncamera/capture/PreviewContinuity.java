package com.particlesdevs.photoncamera.capture;

/**
 * P44: what the shot does to the running preview around the HAL queue flush (research/viewfinder-freeze/FREEZE_ANALYSIS.md).
 * Plain Java decisions, the camera calls stay in CaptureController.
 *
 * The flush before the series drops the preview frames in flight and the HAL restarts its pipeline on the first request it
 * gets; today that is the first L / S / ES request (RAW only). nice_dev.txt "preview_lead N" (0..{@link #MAX_LEAD}) queues N
 * frames of the normal preview request right after the flush, ahead of the series: the viewfinder gets a fresh frame after
 * the restart and the pipeline restarts on a preview request (what the P38 re-arm flush did after the series, so the re-arm
 * then only re-sends the repeating request and drops nothing). Each lead frame starts the series one preview frame later
 * (~33 ms), so it stays off by default until the phone shows the trade (PREVIEW_GAP lines: gap vs "first series frame").
 * Without a flush the preview keeps running into the series and a lead frame would only delay it: none then.
 */
final class PreviewContinuity {
    static final int MAX_LEAD = 3;

    private PreviewContinuity() {}

    /** Lead preview frames for this shot: the nice_dev value (rounded, 0..{@link #MAX_LEAD}) when the queue was flushed. */
    static int leadFrames(float devValue, boolean flushed) {
        if (!flushed || Float.isNaN(devValue)) return 0;
        return Math.max(0, Math.min(MAX_LEAD, Math.round(devValue)));
    }

    /**
     * The P38 re-arm mode used without a nice_dev "stab_rearm" line: on a vivo HAL 3 (flush again once the series is done,
     * so the pipeline restarts on a preview request) after a flushed shot whose series restarted the pipeline, 1 (re-send
     * the repeating request, nothing dropped) when lead preview frames already restarted it on a preview request or the
     * queue was not flushed; elsewhere 0.
     */
    static int defaultRearm(boolean vivoHal, boolean shotFlushed, int leadFrames) {
        if (!vivoHal) return 0;
        return shotFlushed && leadFrames <= 0 ? 3 : 1;
    }
}

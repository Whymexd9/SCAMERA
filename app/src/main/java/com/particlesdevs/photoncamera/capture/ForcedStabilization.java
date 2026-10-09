package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CaptureRequest;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;

/**
 * P54b (owner, 2026-10-09: «зафорсить постоянную стабилизацию для Vivo X300 Ultra»). The X300 Ultra (V2562) still lost
 * its preview stabilisation after a shot with the vivo vendor keys gone (log 2026-10-09: OIS reported on in every request
 * and result, yet no stabilisation after the series). On that phone the stabilisation is held on for the whole session:
 * <ul>
 *   <li>OIS ON in every request we send (repeating preview, lead / AE-restore frames, the series), whatever the smart OIS
 *       mode decides (tripod), unless the user chose «OIS off»;</li>
 *   <li>the stock Photo mode's preview EIS key ({@link ScamPreview#EIS_CONFIG} = 5) in the session parameters and in every
 *       request, the series included (the stock app sets it in every request's global parameters) - only this key, not
 *       the X200 Ultra's stock profile;</li>
 *   <li>no HAL queue flush around the shot (the stock app never flushes; the flush is the first suspect since P38): the
 *       preview keeps running into the series, so the HAL never restarts its pipeline. The series starts ~0.1-0.2 s later.</li>
 *   <li>P54c (owner, 2026-10-09: ArkCam v73 keeps the X300U stabilisation after shots; fix it without touching the merge or
 *       the result): ArkCam never sends a manual (AE OFF) request to the viewfinder stream - its post-shutter manual frames
 *       target the RAW stream only, the repeating preview stays AE ON - and it sends no vivo key at all. Here the AE restore
 *       frame behind the series (same exposure / ISO, so the AE hand-back and the next shot's ZSL ring are unchanged) goes to
 *       the RAW stream only, and the vivo EIS key is opt-in. The series requests are not changed (RAW only already).</li>
 * </ul>
 * scam_dev.txt: "force_stab 0/1" (off / on any phone), "force_stab_noflush 0", "force_stab_rawonly 0" switch the parts
 * off; "force_stab_eis 1" sends the vivo EIS key (P54b default, off since P54c).
 */
public final class ForcedStabilization {
    /** Build.DEVICE / MODEL of the vivo X300 Ultra (owner's logs 2026-10-08 / 09: manufacturer=vivo model=V2562). */
    static final String X300_ULTRA = "V2562";

    private ForcedStabilization() {}

    /** The phone the forced stabilisation is for by default. */
    static boolean phone(String manufacturer, String device, String model) {
        if (!"vivo".equalsIgnoreCase(manufacturer)) return false;
        return X300_ULTRA.equalsIgnoreCase(device) || X300_ULTRA.equalsIgnoreCase(model);
    }

    /** Forced stabilisation for this session (the default phone or the scam_dev override). */
    public static boolean active() {
        return PreferenceKeys.scamDevSwitch("force_stab",
                phone(android.os.Build.MANUFACTURER, android.os.Build.DEVICE, android.os.Build.MODEL));
    }

    /** The shot does not flush the HAL queue. */
    public static boolean noFlush() {
        return active() && PreferenceKeys.scamDevSwitch("force_stab_noflush", true);
    }

    /** The vivo preview EIS key is sent (a vivo HAL only: other HALs do not define it). P54c: opt-in, ArkCam sends none. */
    public static boolean eisKey() {
        return active() && ScamPreview.supported() && PreferenceKeys.scamDevSwitch("force_stab_eis", false);
    }

    /** P54c: the manual frames we add around the series (the AE restore frame) skip the viewfinder stream. */
    public static boolean rawOnlySwitch() {
        return active() && PreferenceKeys.scamDevSwitch("force_stab_rawonly", true);
    }

    /**
     * P54c: whether a manual preview-side frame drops the viewfinder target: only under the forced stabilisation, with the
     * switch on, when the repeating request carries the RAW stream (the frame keeps a target and still delivers its RAW to
     * the ring as before) and not while recording.
     */
    static boolean dropViewfinder(boolean forced, boolean rawOnlySwitch, boolean rawInRepeating, boolean recording) {
        return forced && rawOnlySwitch && rawInRepeating && !recording;
    }

    /** The flush decision of a shot: a wanted flush is skipped under the forced stabilisation. */
    static boolean flush(boolean wanted, boolean noFlush) {
        return wanted && !noFlush;
    }

    /**
     * Sets the forced stabilisation keys on one request: OIS ON when the camera has OIS and the user did not turn it off,
     * the EIS key when {@code eis}. A HAL without the EIS tag skips it. Returns a short description for the log.
     */
    public static String apply(CaptureRequest.Builder builder, boolean ois, boolean eis) {
        StringBuilder what = new StringBuilder();
        if (ois) {
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
            what.append("ois=1");
        }
        if (eis) {
            boolean set;
            try {
                builder.set(new CaptureRequest.Key<>(ScamPreview.EIS_CONFIG, Integer.class), ScamPreview.STOCK_PHOTO_EIS);
                set = true;
            } catch (RuntimeException unsupported) {
                set = false;
            }
            if (what.length() > 0) what.append(' ');
            what.append(set ? "eis=" + ScamPreview.STOCK_PHOTO_EIS : "eis=unsupported");
        }
        return what.toString();
    }
}

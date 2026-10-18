package com.particlesdevs.photoncamera.circularbarlib.api;

import android.app.Activity;
import android.hardware.camera2.CameraCharacteristics;

import com.particlesdevs.photoncamera.circularbarlib.control.ManualParamModel;

import java.util.Observer;

public interface ManualModeConsole {

    void init(Activity activity, CameraCharacteristics cameraCharacteristics);

    void onResume();

    void onPause();

    void onDestroy();

    void addParamObserver(Observer observer);

    ManualParamModel getManualParamModel();

    void removeParamObservers();

    void setPanelVisibility(boolean visible);

    void resetAllValues();

    boolean isManualMode();

    boolean isPanelVisible();

    void retractAllKnobs();

    boolean isFocusParameterSelected();

    boolean isManualFocusModeActive();

    /**
     * The camera's metered values from the latest preview result, shown by the chips of parameters in auto and used as
     * the ruler's start: ISO, exposure time (ns), focus distance (diopters, NaN while unknown) and the AWB colour
     * temperature (K, 0 while unknown). Call on the main thread.
     */
    void setMeteredValues(int iso, long exposureNs, float focusDiopters, int awbKelvin);

    /** Where the panel's toasts go («ISO: авто», the EV lock); a plain Toast without one. */
    void setMessageSink(java.util.function.Consumer<CharSequence> sink);

    /** «Всё на авто»: every parameter back to auto. */
    void resetAllToAuto();

    /** SIMPLE_UI §1, the viewfinder's sun: the Pro EV's compensation (0 in auto). */
    default float evCompensation() {
        return 0f;
    }

    /** The EV compensation's range {min, max} (0, 0 without one). */
    default float[] evRange() {
        return new float[]{0f, 0f};
    }

    /** EV is locked: ISO and shutter are both manual (the sun hides). */
    default boolean evLocked() {
        return false;
    }

    /** Sets the Pro EV to the nearest of its steps (0: auto); the chips follow. */
    default void setEvCompensation(float ev) {
    }
}

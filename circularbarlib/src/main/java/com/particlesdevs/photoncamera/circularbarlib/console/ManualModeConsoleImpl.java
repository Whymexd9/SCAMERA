package com.particlesdevs.photoncamera.circularbarlib.console;

import android.app.Activity;
import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Vibrator;
import android.view.View;

import com.particlesdevs.photoncamera.circularbarlib.api.ManualModeConsole;
import com.particlesdevs.photoncamera.circularbarlib.camera.CameraProperties;
import com.particlesdevs.photoncamera.circularbarlib.control.ManualParamModel;
import com.particlesdevs.photoncamera.circularbarlib.control.models.EvModel;
import com.particlesdevs.photoncamera.circularbarlib.control.models.FocusModel;
import com.particlesdevs.photoncamera.circularbarlib.control.models.IsoModel;
import com.particlesdevs.photoncamera.circularbarlib.control.models.ManualModel;
import com.particlesdevs.photoncamera.circularbarlib.control.models.ShutterModel;
import com.particlesdevs.photoncamera.circularbarlib.model.KnobModel;
import com.particlesdevs.photoncamera.circularbarlib.model.ManualModeModel;
import com.particlesdevs.photoncamera.circularbarlib.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.ManualPanelState;
import com.particlesdevs.photoncamera.circularbarlib.ui.ViewObserver;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.knobview.KnobView;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.knobview.KnobItemInfo;

import java.util.Observer;

/**
 * Responsible for initialising and updating {@link KnobModel} and
 * {@link ManualModeModel}
 * <p>
 * This class also manages the attaching/detaching of {@link ManualModel}
 * subclasses to {@link KnobView}
 * and setting listeners to models
 * <p>
 * Authors - Vibhor, KillerInk
 */
public class ManualModeConsoleImpl implements ManualModeConsole {
    private static final String TAG = "ManualModeConsole";
    private static ManualModeConsole sInstance;
    private final ManualModeModel manualModeModel;
    private final KnobModel knobModel;
    private final ManualParamModel manualParamModel = new ManualParamModel();
    private ManualModel<?> mfModel, isoModel, expoTimeModel, evModel, wbModel, selectedModel;
    private ViewObserver viewObserver;
    private Context context;
    private float evStep = 1f / 3;
    private java.util.function.Consumer<CharSequence> messageSink;
    // The latest metered values, kept across a re-init (a lens switch builds a new ViewObserver).
    private int meteredIso;
    private long meteredExposure;
    private float meteredFocus = Float.NaN;
    private int meteredKelvin;

    private ManualModeConsoleImpl() {
        this.manualModeModel = new ManualModeModel();
        this.knobModel = new KnobModel();
    }

    public static ManualModeConsole getInstance() {
        if (sInstance == null) {
            sInstance = newInstance();
        }
        return sInstance;
    }

    public static ManualModeConsole newInstance() {
        return new ManualModeConsoleImpl();
    }

    public ManualModeModel getManualModeModel() {
        return manualModeModel;
    }

    @Override
    public void addParamObserver(Observer observer) {
        manualParamModel.addObserver(observer);
    }

    @Override
    public void removeParamObservers() {
        manualParamModel.deleteObservers();
    }

    @Override
    public ManualParamModel getManualParamModel() {
        return manualParamModel;
    }

    public KnobModel getKnobModel() {
        return knobModel;
    }

    @Override
    public void init(Activity activity, CameraCharacteristics cameraCharacteristics) {
        context = activity;
        viewObserver = new ViewObserver(activity);
        addObserver();
        addKnobs(activity, cameraCharacteristics);
        setupOnClickListeners();
        setAutoText();
        for(ManualModel<?> model:new ManualModel<?>[]{mfModel,evModel,isoModel,expoTimeModel,wbModel})if(model!=null)model.restoreModuleValue();
        viewObserver.bind(models(), manualParamModel, evStep, actions);
        viewObserver.setMetered(meteredIso, meteredExposure, meteredFocus, meteredKelvin);
    }

    /** The models in the strip's order: ISO, shutter, EV, focus, white balance. */
    ManualModel<?>[] models() {
        return new ManualModel<?>[]{isoModel, expoTimeModel, evModel, mfModel, wbModel};
    }

    ManualModel<?> model(int param) {
        return models()[param];
    }

    /** What the panel's chips and ruler ask for. */
    private final ViewObserver.Actions actions = new ViewObserver.Actions() {
        @Override
        public void onChipTap(int param, View chip) {
            ManualModeConsoleImpl.this.onChipTap(param, chip);
        }

        @Override
        public boolean onChipLongPress(int param) {
            return ManualModeConsoleImpl.this.onChipLongPress(param);
        }

        @Override
        public void onResetAll() {
            resetAllToAuto();
        }

        @Override
        public void onCollapse() {
            closeKnob();
        }

        @Override
        public void onStateChanged() {
            ManualModeConsoleImpl.this.onStateChanged();
        }
    };

    /** The panel's state from the models (which parameters are manual, the EV lock). */
    public ManualPanelState state() {
        ManualModel<?>[] m = models();
        return new ManualPanelState(ViewObserver.isManual(m[0], 0), ViewObserver.isManual(m[1], 1), ViewObserver.isManual(m[2], 2),
                ViewObserver.isManual(m[3], 3), ViewObserver.isManual(m[4], 4));
    }

    private boolean available(int param) {
        ManualModel<?> m = model(param);
        if (m == null || m.getKnobInfoList().size() <= 1) return false;
        return param != ManualPanelState.WB || whiteBalanceSupported;
    }

    private boolean whiteBalanceSupported = true;

    /**
     * A tap: the chip's ruler opens (a tap on the selected chip closes it). A locked EV says why instead; a parameter the
     * camera lacks does nothing (white balance says why).
     */
    public void onChipTap(int param, View chip) {
        if (param == ManualPanelState.EV && state().evLocked()) {
            message(string(R.string.manual_ev_locked));
            return;
        }
        if (!available(param)) {
            if (param == ManualPanelState.WB) message(string(R.string.manual_wb_unavailable));
            return;
        }
        setModelToKnob(chip.getId(), model(param));
    }

    /** A long press: the parameter back to auto (resetModel), with a toast «<name>: авто». */
    public boolean onChipLongPress(int param) {
        if (param == ManualPanelState.EV && state().evLocked()) {
            message(string(R.string.manual_ev_locked));
            return true;
        }
        ManualModel<?> m = model(param);
        if (!available(param)) return true;
        if (selectedModel == m) knobModel.setKnobResetCalled(true);
        m.resetModel();
        message(context == null ? "" : context.getString(R.string.manual_toast_auto, viewObserver.name(param)));
        return true;
    }

    @Override
    public void resetAllToAuto() {
        for (int param = 0; param < ManualPanelState.COUNT; param++) {
            ManualModel<?> m = model(param);
            if (m != null && ViewObserver.isManual(m, param)) m.resetModel();
        }
        if (selectedModel != null) knobModel.setKnobResetCalled(true);
        message(string(R.string.manual_toast_all_auto));
    }

    /** The EV lock sets in while its ruler is open: the ruler closes. */
    void onStateChanged() {
        if (selectedModel != null && selectedModel == evModel && state().evLocked()) closeKnob();
    }

    /** Closes the ruler and forgets the selected parameter (a collapse, the EV lock). */
    private void closeKnob() {
        if (selectedModel == null) return;
        selectedModel = null; // first: the notifications below refresh the panel, which asks onStateChanged again
        knobModel.setManualModel(null);
        knobModel.setKnobVisible(false);
        manualModeModel.setCheckedTextViewId(-1);
    }

    private String string(int id) {
        return context == null ? "" : context.getString(id);
    }

    private void message(CharSequence text) {
        if (text == null || text.length() == 0) return;
        if (messageSink != null) messageSink.accept(text);
        else if (context != null) android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    public void setMessageSink(java.util.function.Consumer<CharSequence> sink) {
        messageSink = sink;
    }

    @Override
    public void setMeteredValues(int iso, long exposureNs, float focusDiopters, int awbKelvin) {
        if (iso == meteredIso && exposureNs == meteredExposure && awbKelvin == meteredKelvin
                && Float.compare(focusDiopters, meteredFocus) == 0) return;
        meteredIso = iso;
        meteredExposure = exposureNs;
        meteredFocus = focusDiopters;
        meteredKelvin = awbKelvin;
        if (viewObserver != null) viewObserver.setMetered(iso, exposureNs, focusDiopters, awbKelvin);
    }

    /** The view binder (tests). */
    public ViewObserver getViewObserver() {
        return viewObserver;
    }

    /** The parameter whose ruler is open, or -1. */
    public int selectedParam() {
        ManualModel<?>[] m = models();
        for (int i = 0; i < m.length; i++) if (m[i] != null && m[i] == selectedModel) return i;
        return -1;
    }

    @Override
    public void onResume() {
        if (viewObserver != null) {
            viewObserver.enableOrientationListener();
        }
        addObserver();
    }

    @Override
    public void onPause() {
        if (viewObserver != null) {
            viewObserver.disableOrientationListener();
        }
        removeObservers();
    }

    @Override
    public void onDestroy() {
        sInstance = null;
    }

    private void addObserver() {
        if (viewObserver != null) {
            removeObservers();
            knobModel.addObserver(viewObserver);
            manualModeModel.addObserver(viewObserver);
        }
    }

    private void removeObservers() {
        knobModel.deleteObservers();
        manualModeModel.deleteObservers();
    }

    private void addKnobs(Context context, CameraCharacteristics cameraCharacteristics) {
        CameraProperties cameraProperties = new CameraProperties(cameraCharacteristics);
        manualParamModel.reset();
        Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        mfModel = new FocusModel(context, cameraCharacteristics, cameraProperties.focusRange, manualParamModel,
                manualModeModel::setFocusText, v);
        evModel = new EvModel(context, cameraCharacteristics, cameraProperties.evRange, manualParamModel,
                manualModeModel::setEvText, v);
        evStep = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP).floatValue();
        ((EvModel) evModel).setEvStep(evStep);
        isoModel = new IsoModel(context, cameraCharacteristics, cameraProperties.isoRange, manualParamModel,
                manualModeModel::setIsoText, v);
        expoTimeModel = new ShutterModel(context, cameraCharacteristics, cameraProperties.expRange, manualParamModel,
                manualModeModel::setExposureText, v);
        wbModel = new com.particlesdevs.photoncamera.circularbarlib.control.models.WhiteBalanceModel(context, cameraCharacteristics,
                manualParamModel, manualModeModel::setWbText, v);
        whiteBalanceSupported = wbModel.getKnobInfoList().size() > 1;
        viewObserver.setWhiteBalanceSupported(whiteBalanceSupported);
        knobModel.setKnobVisible(false);
        manualModeModel.setCheckedTextViewId(-1);
    }

    @Override
    public void setPanelVisibility(boolean visible) {
        manualModeModel.setManualPanelVisible(visible);
        if (!visible) {
            manualParamModel.reset();
        }
    }

    @Override
    public boolean isManualMode() {
        return manualParamModel.isManualMode();
    }

    @Override
    public void resetAllValues() {
        manualParamModel.reset();
    }

    @Override
    public boolean isPanelVisible() {
        return manualModeModel.isManualPanelVisible();
    }

    private void setupOnClickListeners() {
        // The chips' taps and long presses go through ViewObserver.Actions (bound in init); the model keeps the same
        // tap handlers for anything that observes it.
        manualModeModel.setFocusTextClicked(v -> onChipTap(ManualPanelState.FOCUS, v));
        manualModeModel.setEvTextClicked(v -> onChipTap(ManualPanelState.EV, v));
        manualModeModel.setExposureTextClicked(v -> onChipTap(ManualPanelState.SHUTTER, v));
        manualModeModel.setIsoTextClicked(v -> onChipTap(ManualPanelState.ISO, v));
        manualModeModel.setWbTextClicked(v -> onChipTap(ManualPanelState.WB, v));
    }

    private void setAutoText() {
        if (evModel != null)
            evModel.setAutoTxt();
        if (mfModel != null)
            mfModel.setAutoTxt();
        if (expoTimeModel != null)
            expoTimeModel.setAutoTxt();
        if (isoModel != null)
            isoModel.setAutoTxt();
        if (wbModel != null) wbModel.setAutoTxt();
    }

    @Override
    public void retractAllKnobs() {
        knobModel.setKnobVisible(false);
        knobModel.setKnobResetCalled(true);
        selectedModel = null;
        if (mfModel != null)
            mfModel.resetModel();
        if (expoTimeModel != null)
            expoTimeModel.resetModel();
        if (isoModel != null)
            isoModel.resetModel();
        if (evModel != null)
            evModel.resetModel();
        if (wbModel != null) wbModel.resetModel();
        manualModeModel.setCheckedTextViewId(-1);
    }

    @Override
    public boolean isFocusParameterSelected() {
        return selectedModel instanceof FocusModel;
    }

    @Override
    public boolean isManualFocusModeActive() {
        if (mfModel == null) {
            return false;
        }
        KnobItemInfo currentInfo = mfModel
                .getCurrentInfo();
        return currentInfo != null && currentInfo.value != ManualParamModel.FOCUS_AUTO;
    }

    private void setModelToKnob(int viewId, ManualModel<?> modelToKnob) {
        if (modelToKnob == selectedModel) {
            knobModel.setManualModel(null);
            knobModel.setKnobVisible(false);
            manualModeModel.setCheckedTextViewId(-1);
            selectedModel = null;
        } else {
            if (modelToKnob.getKnobInfoList().size() > 1) {
                knobModel.setManualModel(modelToKnob);
                knobModel.setKnobVisible(true);
                manualModeModel.setCheckedTextViewId(viewId);
                selectedModel = modelToKnob;
            }
        }
    }
}

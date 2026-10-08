package com.particlesdevs.photoncamera.circularbarlib.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.particlesdevs.photoncamera.circularbarlib.R;
import com.particlesdevs.photoncamera.circularbarlib.control.ManualParamModel;
import com.particlesdevs.photoncamera.circularbarlib.control.models.ManualModel;
import com.particlesdevs.photoncamera.circularbarlib.model.KnobModel;
import com.particlesdevs.photoncamera.circularbarlib.model.ManualModeModel;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.ManualChipView;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.knobview.KnobItemInfo;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.knobview.KnobView;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.scaleview.LinearScaleView;

import java.util.List;
import java.util.Observable;
import java.util.Observer;

/**
 * Binds the five manual models to the card-style panel (MANUAL_TASK.md): the chips (value, state, selection, the EV lock),
 * the toggle's dot, the ruler card (header pill, «Всё на авто», the scale) and the top-bar summary when the camera screen
 * has one ({@code R.id.manual_summary}).
 * <p>
 * Created by vibhorSrv; card style by the manual-controls task.
 */
public class ViewObserver implements Observer {
    /** What the chips and the ruler ask of the console. */
    public interface Actions {
        void onChipTap(int param, View chip);

        boolean onChipLongPress(int param);

        void onResetAll();

        void onCollapse();

        /** The models changed (the EV lock may have set in). */
        void onStateChanged();
    }

    private final Activity activity;
    private final ExpandingManualPanel manualMode;
    private final KnobView knobView;
    private final LinearScaleView linearScaleView;
    /** True while the linear scale replaces the wheel. */
    private final boolean useLinearScale;
    private final ManualChipView[] chips = new ManualChipView[ManualPanelState.COUNT];
    private final String[] names = new String[ManualPanelState.COUNT];
    private final View knobContainer;
    private final ImageView rulerIcon;
    private final TextView rulerValue, allAuto;
    private final ManualSummaryView summary;
    private final OrientationEventListener orientationEventListener;
    private final ManualFormat.Units units;
    private int rotation = 0;
    private int accent;
    /** Model backing whichever parameter is on screen, shared by both controls. */
    private ManualModel<?> currentModel;
    /** Last item the scale reported, so the model receives a real previous value. */
    private KnobItemInfo lastScaleItem;

    private ManualParamModel params;
    /** Models in the strip's order (ISO, shutter, EV, focus, WB); entries may be null before init. */
    private ManualModel<?>[] models = new ManualModel<?>[ManualPanelState.COUNT];
    /** Camera2 AE compensation step: the applied EV is index × step. */
    private float evStep = 1f / 3;
    private Actions actions;
    private boolean whiteBalanceSupported = true;
    // The camera's metered values (capture results); NaN / 0 while unknown.
    private int meteredIso;
    private long meteredExposure;
    private float meteredFocus = Float.NaN;
    private int meteredKelvin;

    public static final int[] ICONS = {R.drawable.manual_ic_iso, R.drawable.manual_ic_shutter, R.drawable.manual_ic_ev,
            R.drawable.manual_ic_focus, R.drawable.manual_ic_wb};
    private static final int[] CHIP_IDS = {R.id.iso_option_tv, R.id.exposure_option_tv, R.id.ev_option_tv, R.id.focus_option_tv,
            R.id.wb_option_tv};

    public ViewObserver(Activity activity) {
        this.activity = activity;
        manualMode = findViewById(R.id.manual_mode);
        knobView = findViewById(R.id.knobView);
        knobContainer = findViewById(R.id.knobViewContainer);
        linearScaleView = findViewById(R.id.linearScaleView);
        rulerIcon = findViewById(R.id.manual_ruler_icon);
        rulerValue = findViewById(R.id.manual_ruler_value);
        allAuto = findViewById(R.id.manual_all_auto);
        summary = findViewById(R.id.manual_summary);
        units = ManualFormat.Units.of(activity);
        accent = UiTokens.cameraAccent(activity);
        useLinearScale = android.preference.PreferenceManager
                .getDefaultSharedPreferences(activity.getApplicationContext())
                .getBoolean("pref_linear_manual_scale_key", true);
        names[ManualPanelState.ISO] = activity.getString(R.string.manual_tab_iso);
        names[ManualPanelState.SHUTTER] = activity.getString(R.string.manual_tab_shutter);
        names[ManualPanelState.EV] = activity.getString(R.string.manual_tab_ev);
        names[ManualPanelState.FOCUS] = activity.getString(R.string.manual_tab_focus);
        names[ManualPanelState.WB] = activity.getString(R.string.manual_tab_wb);
        for (int i = 0; i < chips.length; i++) {
            chips[i] = findViewById(CHIP_IDS[i]);
            if (chips[i] == null) continue;
            chips[i].setIcon(ICONS[i]);
            chips[i].setAccent(accent);
            chips[i].setTag(names[i]);
            chips[i].setContentDescription(names[i]);
        }
        if (manualMode != null) {
            manualMode.setAccent(accent);
            manualMode.setOnCollapseListener(() -> { if (actions != null) actions.onCollapse(); });
        }
        if (rulerIcon != null) rulerIcon.setImageTintList(ColorStateList.valueOf(accent));
        if (allAuto != null) {
            allAuto.setTextColor(accent);
            allAuto.setOnClickListener(v -> { if (actions != null) actions.onResetAll(); });
        }
        if (summary != null) summary.setAccent(accent);
        if (linearScaleView != null) {
            linearScaleView.setAccent(accent);
            linearScaleView.setAutoLabel(activity.getString(R.string.manual_value_auto));
            linearScaleView.setListener(new LinearScaleView.OnValueChangedListener() {
                @Override
                public void onValueChanged(KnobItemInfo item, boolean fromUser) {
                    // Reuse the wheel's own callback so both controls drive the model through one path. Pass the
                    // previous item as the first argument: the model returns immediately when both are the same object.
                    if (currentModel != null && item != null && fromUser && item != lastScaleItem) {
                        currentModel.onSelectedKnobItemChanged(knobView, lastScaleItem, item);
                        lastScaleItem = item;
                    }
                }

                @Override
                public void onAutoRequested() {
                    // The model decides what auto means for its parameter.
                    if (currentModel != null) {
                        currentModel.resetModel();
                        lastScaleItem = currentModel.getCurrentInfo();
                        syncScale();
                    }
                }

                @Override
                public void onDragStateChanged(boolean dragging) {
                    if (currentModel != null) {
                        currentModel.onRotationStateChanged(knobView,
                                dragging ? KnobView.RotationState.ROTATING : KnobView.RotationState.IDLE);
                    }
                }
            });
        }

        orientationEventListener = new OrientationEventListener(activity.getBaseContext()) {
            private static final int ROT_DUR = 350;
            private int prevOrientation = OrientationEventListener.ORIENTATION_UNKNOWN;

            @Override
            public void onOrientationChanged(int orientation) {
                if (Settings.System.getInt(activity.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 0)
                    return; // auto-rotate disabled
                int currentOrientation = OrientationEventListener.ORIENTATION_UNKNOWN;
                if (orientation >= 340 || orientation < 20 && rotation != 0) {
                    currentOrientation = Surface.ROTATION_0;
                    rotation = 0;
                } else if (orientation >= 70 && orientation < 110 && rotation != 90) {
                    currentOrientation = Surface.ROTATION_270;
                    rotation = -90;
                } else if (orientation >= 160 && orientation < 200 && rotation != 180) {
                    currentOrientation = Surface.ROTATION_180;
                    rotation = 180;
                } else if (orientation >= 250 && orientation < 290 && rotation != 270) {
                    currentOrientation = Surface.ROTATION_90;
                    rotation = 90;
                }
                if (prevOrientation != currentOrientation && orientation != OrientationEventListener.ORIENTATION_UNKNOWN) {
                    prevOrientation = currentOrientation;
                    if (currentOrientation != OrientationEventListener.ORIENTATION_UNKNOWN) {
                        Binding.rotateKnobView(knobView, rotation);
                        // The chips keep their portrait layout; only their icons turn (as the lens strip's labels).
                        for (ManualChipView chip : chips)
                            if (chip != null) chip.getIcon().animate().rotation(rotation).setDuration(ROT_DUR).start();
                    }
                }
            }
        };
    }

    /** The models in the strip's order, the parameter model, the AE compensation step and the console's actions. */
    public void bind(ManualModel<?>[] models, ManualParamModel params, float evStep, Actions actions) {
        this.models = models.clone();
        this.params = params;
        if (evStep > 0) this.evStep = evStep;
        this.actions = actions;
        for (int i = 0; i < chips.length; i++) {
            final int param = i;
            ManualChipView chip = chips[i];
            if (chip == null) continue;
            chip.setOnClickListener(v -> { if (this.actions != null) this.actions.onChipTap(param, v); });
            chip.setOnLongClickListener(v -> this.actions != null && this.actions.onChipLongPress(param));
        }
        refresh();
    }

    /** The camera's metered values from the latest capture result (0 / NaN while unknown). */
    public void setMetered(int iso, long exposureNs, float focusDiopters, int kelvin) {
        meteredIso = iso;
        meteredExposure = exposureNs;
        meteredFocus = focusDiopters;
        meteredKelvin = kelvin;
        refresh();
    }

    private void syncScale() {
        if (currentModel != null && linearScaleView != null) {
            lastScaleItem = currentModel.getCurrentInfo();
            linearScaleView.setSelectedItem(lastScaleItem);
        }
    }

    public void setWhiteBalanceSupported(boolean supported) {
        whiteBalanceSupported = supported;
        refresh();
    }

    public void enableOrientationListener() {
        if (orientationEventListener != null && orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable();
        }
    }

    public void disableOrientationListener() {
        if (orientationEventListener != null) {
            orientationEventListener.disable();
        }
    }

    private <T extends View> T findViewById(int id) {
        return activity.findViewById(id);
    }

    // ───── state

    private static final double[] AUTO_VALUES = {ManualParamModel.ISO_AUTO, ManualParamModel.EXPOSURE_AUTO,
            ManualParamModel.EV_AUTO, ManualParamModel.FOCUS_AUTO, 0};

    /** Whether the parameter's model holds a value other than its auto item. */
    public static boolean isManual(ManualModel<?> model, int param) {
        if (model == null || model.getCurrentInfo() == null) return false;
        return model.getCurrentInfo().value != AUTO_VALUES[param];
    }

    /** Whether the camera offers the parameter at all (a fixed-focus lens, no manual white balance). */
    private boolean available(int param) {
        ManualModel<?> m = models[param];
        if (m == null || m.getKnobInfoList().size() <= 1) return false;
        return param != ManualPanelState.WB || whiteBalanceSupported;
    }

    public ManualPanelState state() {
        boolean[] manual = new boolean[ManualPanelState.COUNT];
        for (int i = 0; i < manual.length; i++) manual[i] = isManual(models[i], i);
        return new ManualPanelState(manual[0], manual[1], manual[2], manual[3], manual[4]);
    }

    /** The value a chip, the pill and the summary show for a parameter set by the user. */
    public String manualText(int param) {
        ManualModel<?> m = models[param];
        KnobItemInfo item = m == null ? null : m.getCurrentInfo();
        if (item == null) return "";
        switch (param) {
            case ManualPanelState.ISO:
                return ManualFormat.iso(item.value);
            case ManualPanelState.SHUTTER:
                return ManualFormat.shutter((long) item.value, units);
            case ManualPanelState.EV:
                // What the request gets: the compensation index times the camera's step.
                double index = params != null ? params.getCurrentEvValue() : Math.round(item.value / evStep);
                return ManualFormat.ev(index * evStep);
            case ManualPanelState.FOCUS:
                return ManualFormat.focus(item.value, units);
            default:
                return ManualFormat.wb((int) Math.round(item.value));
        }
    }

    /** The camera's current value of a parameter in auto: metered ISO / shutter / focus / AWB, else «Авто». */
    public String meteredText(int param) {
        String auto = activity.getString(R.string.manual_value_auto);
        switch (param) {
            case ManualPanelState.ISO:
                return meteredIso > 0 ? ManualFormat.iso(meteredIso) : auto;
            case ManualPanelState.SHUTTER:
                return meteredExposure > 0 ? ManualFormat.shutter(meteredExposure, units) : auto;
            case ManualPanelState.EV:
                return ManualFormat.ev(0);
            case ManualPanelState.FOCUS:
                return Float.isNaN(meteredFocus) || meteredFocus < 0 ? auto : ManualFormat.focus(meteredFocus, units);
            default:
                return meteredKelvin > 0 ? ManualFormat.wb(meteredKelvin) : auto;
        }
    }

    /** The metered value as a model value (for the scale's start in auto), NaN when unknown. */
    private double meteredValue(int param) {
        switch (param) {
            case ManualPanelState.ISO: return meteredIso > 0 ? meteredIso : Double.NaN;
            case ManualPanelState.SHUTTER: return meteredExposure > 0 ? meteredExposure : Double.NaN;
            case ManualPanelState.EV: return 0;
            case ManualPanelState.FOCUS: return Float.isNaN(meteredFocus) ? Double.NaN : meteredFocus;
            default: return meteredKelvin > 0 ? meteredKelvin : Double.NaN;
        }
    }

    private int paramOf(ManualModel<?> model) {
        for (int i = 0; i < models.length; i++) if (models[i] != null && models[i] == model) return i;
        return -1;
    }

    /** Chips, toggle dot, ruler header and summary from the models; closes the EV ruler when the lock sets in. */
    public void refresh() {
        ManualPanelState state = state();
        boolean locked = state.evLocked();
        for (int i = 0; i < chips.length; i++) {
            ManualChipView chip = chips[i];
            if (chip == null) continue;
            String a11y;
            if (!available(i)) {
                chip.bind("—", ManualChipView.UNAVAILABLE);
                a11y = i == ManualPanelState.WB ? activity.getString(R.string.manual_wb_unavailable)
                        : activity.getString(R.string.manual_a11y_unavailable, names[i]);
            } else if (i == ManualPanelState.EV && locked) {
                chip.bind("—", ManualChipView.LOCKED);
                a11y = activity.getString(R.string.manual_a11y_locked, names[i]);
            } else if (state.isManual(i)) {
                String v = manualText(i);
                chip.bind(v, ManualChipView.MANUAL);
                a11y = activity.getString(R.string.manual_a11y_manual, names[i], v);
            } else {
                String v = meteredText(i);
                chip.bind(v, ManualChipView.AUTO);
                a11y = activity.getString(R.string.manual_a11y_auto, names[i], v);
            }
            chip.setContentDescription(a11y);
        }
        if (manualMode != null) manualMode.setManualDot(state.anyApplied());
        if (allAuto != null) allAuto.setVisibility(state.anyManual() ? View.VISIBLE : View.GONE);
        bindHeader(state);
        if (summary != null) bindSummary(state);
        if (linearScaleView != null && currentModel != null) {
            int p = paramOf(currentModel);
            if (p >= 0) linearScaleView.setMeteredValue(meteredValue(p));
        }
        if (actions != null) actions.onStateChanged();
    }

    /** The ruler header: the parameter icon, its value in an accent pill (a muted pill with the metered value in auto). */
    private void bindHeader(ManualPanelState state) {
        if (rulerValue == null || currentModel == null) return;
        int p = paramOf(currentModel);
        if (p < 0) return;
        if (rulerIcon != null) rulerIcon.setImageResource(ICONS[p]);
        boolean manual = state.isManual(p);
        String v = manual ? manualText(p) : meteredText(p);
        rulerValue.setText(v);
        rulerValue.setTextColor(manual ? accent : UiTokens.MUTED);
        rulerValue.setTypeface(Typeface.DEFAULT, manual ? Typeface.BOLD : Typeface.NORMAL);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(UiTokens.dp(activity, 999));
        pill.setColor(manual ? UiTokens.chipBg(accent) : UiTokens.MUTED_PILL);
        rulerValue.setBackground(pill);
        rulerValue.setContentDescription(activity.getString(manual ? R.string.manual_a11y_manual : R.string.manual_a11y_auto, names[p], v));
    }

    /** The top-bar summary: one item per applied manual parameter in the strip's order; gone without any. */
    private void bindSummary(ManualPanelState state) {
        List<Integer> applied = state.summary();
        if (applied.isEmpty()) {
            summary.setItems(new int[0], new String[0], "");
            summary.setVisibility(View.GONE);
            return;
        }
        int[] icons = new int[applied.size()];
        String[] values = new String[applied.size()];
        StringBuilder a11y = new StringBuilder();
        for (int k = 0; k < applied.size(); k++) {
            int p = applied.get(k);
            icons[k] = ICONS[p];
            values[k] = manualText(p);
            if (k > 0) a11y.append(", ");
            a11y.append(names[p]).append(' ').append(values[k]);
        }
        summary.setItems(icons, values, activity.getString(R.string.manual_summary, a11y));
        summary.setVisibility(View.VISIBLE);
    }

    public ManualChipView chip(int param) {
        return chips[param];
    }

    public String name(int param) {
        return names[param];
    }

    @Override
    public void update(Observable o, Object arg) {
        if (o == null || arg == null) return;
        if (o instanceof KnobModel) {
            KnobModel knobModel = (KnobModel) o;
            switch ((KnobModel.KnobModelFields) arg) {
                case RESET:
                    Binding.resetKnob(knobView, knobModel.isKnobResetCalled());
                    break;
                case VISIBILITY:
                    knobContainer.setVisibility(knobModel.isKnobVisible() ? View.VISIBLE : View.GONE);
                    // Only one control is ever visible. The wheel keeps its own visibility logic; the scale mirrors it.
                    Binding.setKnobVisibility(knobView, !useLinearScale && knobModel.isKnobVisible());
                    if (linearScaleView != null) {
                        linearScaleView.setVisibility(useLinearScale && knobModel.isKnobVisible() ? View.VISIBLE : View.GONE);
                    }
                    break;
                case MANUAL_MODEL:
                    currentModel = knobModel.getManualModel();
                    Binding.setModelToKnob(knobView, currentModel);
                    if (linearScaleView != null && currentModel != null) {
                        int p = paramOf(currentModel);
                        List<KnobItemInfo> items = currentModel.getKnobInfoList();
                        linearScaleView.setMode(LinearScaleView.modeFor(p), units);
                        linearScaleView.setItems(items, currentModel.getCurrentInfo(), autoItemOf(currentModel, p));
                        // New parameter, new baseline: otherwise the first drag on the next parameter is compared against
                        // the previous parameter's item and discarded.
                        lastScaleItem = currentModel.getCurrentInfo();
                    }
                    refresh();
                    break;
            }
        }
        if (o instanceof ManualModeModel) {
            ManualModeModel manualModeModel = (ManualModeModel) o;
            switch ((ManualModeModel.ManualModelFields) arg) {
                case EV_TEXT:
                case EXP_TEXT:
                case ISO_TEXT:
                case FOCUS_TEXT:
                case WB_TEXT:
                    syncScale();
                    refresh();
                    break;
                case SELECTED_TV:
                    int selected = manualModeModel.getSelectedTextViewId();
                    for (ManualChipView chip : chips) if (chip != null) chip.setChosen(chip.getId() == selected && selected != -1);
                    break;
                case PANEL_VISIBILITY:
                    Binding.togglePanelVisibility(manualMode, manualModeModel.isManualPanelVisible());
                    break;
                default:
                    break;
            }
        }
    }

    /** The model's auto item: the list entry with the parameter's auto value. */
    private static KnobItemInfo autoItemOf(ManualModel<?> model, int param) {
        if (param < 0) return null;
        for (KnobItemInfo item : model.getKnobInfoList()) if (item.value == AUTO_VALUES[param]) return item;
        return null;
    }
}

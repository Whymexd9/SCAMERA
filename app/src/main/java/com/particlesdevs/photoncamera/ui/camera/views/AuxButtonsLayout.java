/*
 *
 *  PhotonCamera
 *  AuxButtonsLayout.java
 *  Copyright (C) 2020 - 2021  Vibhor
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 * /
 */

package com.particlesdevs.photoncamera.ui.camera.views;

import android.content.Context;
import com.particlesdevs.photoncamera.settings.ModuleRegistry;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.ui.camera.binding.CustomBinding;
import com.particlesdevs.photoncamera.ui.camera.data.CameraLensData;
import com.particlesdevs.photoncamera.ui.camera.model.AuxButtonsModel;
import com.particlesdevs.photoncamera.ui.settings.DialogStyle;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Container for multi-camera buttons.
 * <p>
 * SIMPLE_UI look (the concept's .lenses): one capsule in the card colour with a LINE stroke between «Про» and «Ещё», the
 * active lens filled with the camera accent (INK text, «1×», the live zoom «2,3×»), the others MUTED without the sign
 * («0,4», «2,4», «5»), 2dp apart, decimal commas. The gestures are unchanged (owner's answer 2): tap selects a lens, a horizontal drag zooms.
 * The buttons are {@link LensButton}s: in landscape only their labels turn (P32).
 * <p>
 * This layout's functionality is dependent on {@link AuxButtonsModel} which is provided
 * through DataBinding {@link CustomBinding#setAuxButtonModel(AuxButtonsLayout, AuxButtonsModel)}.
 */
public class AuxButtonsLayout extends LinearLayout {

    /**
     * this map stores dynamically generated view-ids and corresponding camera-ids attached to that view(or button)
     * for functional purpose
     */
    private final HashMap<Integer, String> auxButtonsMap = new HashMap<>();
    /** P72: zoom preset buttons (view id -> zoom) of a module (their slot in {@link #auxButtonsMap}): 17 Ultra tele 4.3x / 6.45x / 8.6x. */
    private final HashMap<Integer, Float> presetMap = new HashMap<>();
    /** Zooms the active module to a preset (CameraFragment.zoomTo). */
    private java.util.function.Consumer<Float> presetZoom;
    public void setPresetZoom(java.util.function.Consumer<Float> presetZoom) { this.presetZoom = presetZoom; }

    private final LinearLayout.LayoutParams buttonParams;
    /** P181: the gap on each side of a tile at fit 1 (px). */
    private final int baseGap;

    private AuxButtonListener auxButtonListener;
    private AuxButtonsModel auxButtonsModel;
    private long lastSwitch = -500;
    private int labelRotation;
    private final List<String> displayedSlots = new ArrayList<>();
    private final List<String> displayedLabels = new ArrayList<>();

public AuxButtonsLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        // P157: the strip in proportion to the screen's width (BottomChrome.scale)
        float density = context.getResources().getDisplayMetrics().density * BottomChrome.scale(context);
        // SIMPLE_UI: the concept's strip - the lenses 2dp apart, no dots, centred on one line (all of one height)
        buttonParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonParams.gravity = android.view.Gravity.CENTER_VERTICAL;
        baseGap = Math.round(1 * density);
        buttonParams.setMargins(baseGap, 0, baseGap, 0);

        // The layout editor runs this constructor but not the data-binding adapters,
        // so populate a few sample buttons so the host preview shows the aux palette.
        if (isInEditMode()) {
            addNewButton("0", "1x");
            addNewButton("1", "2x");
            addNewButton("2", "5x");
            setListenerAndSelected("0");
            updateVisibility();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        fitTiles(widthMeasureSpec);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /**
     * P181 (owner, 2026-10-16: «у нас модули выходят за границы экрана»): when the tiles at their own size (P161) and their
     * gaps are wider than the strip may be, all of them shrink together - least width, padding, text and gaps - so every
     * lens stays on the screen; they keep their own size whenever they fit. (The long press's module list stays for a strip
     * that would need more than {@link LensButton#MIN_FIT}.)
     */
    private void fitTiles(int widthMeasureSpec) {
        final int available = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED || available <= 0) return;
        float need = 0f;
        int tiles = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == View.GONE || !(child instanceof LensButton)) continue;
            need += ((LensButton) child).naturalWidth() + 2 * baseGap;
            tiles++;
        }
        if (tiles == 0) return;
        // the rounded sizes must add up to no more than the strip: a step down until they do (a squeezed last tile otherwise)
        float f = fit(need, available);
        while (f > LensButton.MIN_FIT && roundedWidth(f) > available) f = Math.max(LensButton.MIN_FIT, f - 0.005f);
        final int gap = Math.round(baseGap * f);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child instanceof LensButton)) continue;
            ((LensButton) child).setFit(f);
            if (child.getLayoutParams() instanceof MarginLayoutParams) {
                MarginLayoutParams margins = (MarginLayoutParams) child.getLayoutParams();
                if (margins.leftMargin != gap || margins.rightMargin != gap) margins.setMargins(gap, 0, gap, 0);
            }
        }
    }

    /** P181: the tiles' total width at fit {@code f} as they will be measured (rounded sizes and gaps). */
    private int roundedWidth(float f) {
        final int gap = Math.round(baseGap * f);
        int total = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == View.GONE || !(child instanceof LensButton)) continue;
            total += ((LensButton) child).widthAt(f) + 2 * gap;
        }
        return total;
    }

    /** P181: the tiles' total width at their own size (tests: a strip they fit only shrunk). */
    float naturalNeed() {
        float need = 0f;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != View.GONE && child instanceof LensButton) need += ((LensButton) child).naturalWidth() + 2 * baseGap;
        }
        return need;
    }

    /** P181: the share of their own size the tiles take: 1 when {@code need} fits {@code available}, at least MIN_FIT. */
    static float fit(float need, float available) {
        if (!(need > available) || available <= 0f) return 1f;
        // a pixel of slack per tile edge: the rounded sizes must not add up to more than the strip
        return Math.max(LensButton.MIN_FIT, (available - 2f) / need);
    }

    private static String getAuxButtonName(float zoomFactor) {
        return String.format(Locale.US, "%.1fx", (zoomFactor - 0.049)).replace(".0", "");
    }

    public void setAuxButtonsModel(AuxButtonsModel auxButtonsModel) {
        this.auxButtonsModel = auxButtonsModel;
        auxButtonListener = auxButtonsModel.getAuxButtonListener();
    }

    public void setActiveId(String activeId) {
        refresh(activeId);
    }

    private void refresh(String cameraId) {
        if (auxButtonsModel == null) return;
        List<CameraLensData> front = auxButtonsModel.getFrontCameras();
        List<CameraLensData> back = auxButtonsModel.getBackCameras();
        if (front == null || back == null) return;
        if (!isFront(cameraId, front))
            this.setAuxButtons(back, cameraId);
        else
            this.setAuxButtons(front, cameraId);
    }

    private boolean isFront(String cameraId, List<CameraLensData> frontCameras) {
        return frontCameras.stream().anyMatch(cameraLensData -> cameraLensData.getCameraId().equals(cameraId));
    }

    private void setAuxButtons(List<CameraLensData> cameraLensDataList, String activeId) {
        SettingsManager manager = ScameraApp.getSettingsManagerStatic();
        ModuleRegistry.initialize("front", auxButtonsModel.getFrontCameras());
        ModuleRegistry.initialize("back", auxButtonsModel.getBackCameras());
        // P100: a main camera with in-sensor zoom (CROPPED_RAW, Galaxy S24 Ultra) gets its «2×» button once
        com.particlesdevs.photoncamera.settings.IszModule.ensure(manager.getContext());
        // P108: the Pixel 10 Pro / Pro XL's in-sensor crops of the back logical camera (2x main, ~9x tele) as modules, once
        com.particlesdevs.photoncamera.capture.PixelPro10.ensureModules(manager.getContext());
        // P109: the vivo X500 Pro Max's ISZ modules (main 2x, ultra-wide 2x, tele 2x / 4x), once
        // P122: the vivo X300 Ultra's tele 2x ISZ (forceSensorMode of camera_config.xml), once
        com.particlesdevs.photoncamera.capture.VivoQcomIsz.ensureModules(manager.getContext());
        com.particlesdevs.photoncamera.capture.VivoQcomModes.ensureModules(manager.getContext());
        com.particlesdevs.photoncamera.capture.VivoPd2454Isz.ensureModules(manager.getContext());
        // A config imported before the slots existed (fresh install) maps its module profiles now.
        com.particlesdevs.photoncamera.settings.BackupRestoreUtil.applyPending(manager.getContext());
        String side = cameraLensDataList == auxButtonsModel.getFrontCameras() ? "front" : "back";
        List<String> slots = ModuleRegistry.initialize(side, cameraLensDataList);
        slots.sort(Comparator.comparingDouble(ModuleRegistry::zoom)); // zoom order, like the dial
        String currentSlot=ModuleRegistry.active();
        if(!ModuleRegistry.switching(activeId)&&(!slots.contains(currentSlot)||!ModuleRegistry.camera(currentSlot).equals(activeId))) {
            // The camera was changed from outside: the module of that camera nearest to the current zoom, not the first one.
            String best=null;float zoom=com.particlesdevs.photoncamera.control.ZoomController.zoom();
            for(String slot:slots)if(ModuleRegistry.visible(slot)&&ModuleRegistry.camera(slot).equals(activeId)
                    &&(best==null||Math.abs(ModuleRegistry.zoom(slot)-zoom)<Math.abs(ModuleRegistry.zoom(best)-zoom)))best=slot;
            if(best!=null){
                ModuleRegistry.select(best);
                // P37: the zoom follows the module chosen here (the ruler started at the old zoom, e.g. 1x on a tele).
                com.particlesdevs.photoncamera.control.ZoomController.syncToActive();
            }
        }
        List<String> shown = new ArrayList<>();
        for (String slot : slots) if (ModuleRegistry.visible(slot)) shown.add(slot);
        // P343: one button per stop (an ISZ module before a lens's own before a digital copy)
        shown = ZoomStops.unique(shown, ModuleRegistry::label, s -> ModuleRegistry.sensorMode(s) != 0 || ModuleRegistry.sensorCrop(s) ? 2
                : ModuleRegistry.duplicateOf(s) == null && Math.abs(ModuleRegistry.crop(s) - 1f) < 1e-4f ? 1 : 0, ModuleRegistry.active());
        logStrip(side, slots, shown);
        List<String> visible = new ArrayList<>(), labels = new ArrayList<>();
        for (String slot : shown) {
            visible.add(slot);labels.add(ModuleRegistry.label(slot));
            for (float p : presets(slot)) labels.add(com.particlesdevs.photoncamera.capture.XiaomiTeleZoom.presetLabel(p));
        }
        if (!visible.equals(displayedSlots) || !labels.equals(displayedLabels)) {
            removeAllViews();auxButtonsMap.clear();presetMap.clear();
            displayedSlots.clear();displayedSlots.addAll(visible);
            displayedLabels.clear();displayedLabels.addAll(labels);
            for (String slot : visible) {
                addNewButton(slot, ModuleRegistry.label(slot));
                for (float p : presets(slot)) presetMap.put(addNewButton(slot, com.particlesdevs.photoncamera.capture.XiaomiTeleZoom.presetLabel(p)), p);
            }
        }
        setListenerAndSelected(activeId);
        updateVisibility();
    }

    private static String loggedStrip = "";

    /** P343: the strip once per change - every visible module: slot, camera, zoom, sensor mode / crop, and which ones are shown. */
    private static void logStrip(String side, List<String> slots, List<String> shown) {
        StringBuilder b = new StringBuilder("strip ").append(side).append(':');
        for (String slot : slots) {
            if (!ModuleRegistry.visible(slot)) continue;
            b.append(' ').append(slot).append("=cam").append(ModuleRegistry.camera(slot)).append(String.format(Locale.US, "@%.3f", ModuleRegistry.zoom(slot)));
            if (ModuleRegistry.sensorMode(slot) != 0) b.append(" mode").append(ModuleRegistry.sensorMode(slot));
            if (ModuleRegistry.sensorCrop(slot)) b.append(" sensorcrop");
            if (Math.abs(ModuleRegistry.crop(slot) - 1f) > 1e-4f) b.append(" crop").append(ModuleRegistry.crop(slot));
            if (!shown.contains(slot)) b.append(" (same stop, hidden)");
            b.append(',');
        }
        String line = b.toString();
        if (line.equals(loggedStrip)) return;
        loggedStrip = line;
        com.particlesdevs.photoncamera.util.Log.i("ZOOM_STRIP", line);
    }

    private void setListenerAndSelected(String activeId) {
        View.OnClickListener auxButtonListener = this::onAuxButtonClick;
        final float zoom = com.particlesdevs.photoncamera.control.ZoomController.zoom();
        for (int i = 0; i < getChildCount(); i++) {
            View button = getChildAt(i);
            button.setOnClickListener(auxButtonListener);
            String slot = auxButtonsMap.get(button.getId());
            boolean module = ModuleRegistry.active().equals(slot) ||
                    (!ModuleRegistry.slots().contains(ModuleRegistry.active()) && activeId.equals(ModuleRegistry.camera(slot)));
            button.setSelected(module && selectedHere(button.getId(), slot, zoom));
        }
        styleSelection();
    }

    /** P72: the 17 Ultra tele's zoom presets after its own button; empty elsewhere. */
    private static float[] presets(String slot) {
        return com.particlesdevs.photoncamera.capture.XiaomiTeleZoom.presets(ModuleRegistry.zoom(slot));
    }

    /** Within the active module: its preset button at that zoom, else the module's own button. */
    private boolean selectedHere(int id, String slot, float zoom) {
        Float preset = presetMap.get(id);
        if (preset != null) return Math.abs(zoom - preset) < 0.03f;
        for (java.util.Map.Entry<Integer, Float> e : presetMap.entrySet())
            if (slot != null && slot.equals(auxButtonsMap.get(e.getKey())) && Math.abs(zoom - e.getValue()) < 0.03f) return false;
        return true;
    }

    private void updateVisibility() {
        setVisibility(getChildCount() <= 1 ? View.INVISIBLE : View.VISIBLE);
    }

    /**
     * «0.6×» -> «0,6×»: the decimal comma on the strip in the Russian UI (owner's answer 11), the point in English; labels are
     * stored as they are.
     */
    static String display(String label) {
        if (label == null) return "";
        return Lang.ru() ? label.replaceAll("(\\d)\\.(\\d)", "$1,$2") : label;
    }

    /** The active lens in bold; the colours follow the selected state (P157: LensButton styles its own state). */
    private void styleSelection() {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v instanceof Button && !(v instanceof LensButton))
                ((Button) v).setTypeface(null, v.isSelected() ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private void onAuxButtonClick(View view) {
        if (view.isSelected()) return;
        if (auxButtonsModel != null && auxButtonsModel.isEnabled() && android.os.SystemClock.elapsedRealtime()-lastSwitch>=500) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            // Property animators are independent of rotation and layout. Final scale remains 1.
            android.animation.PropertyValuesHolder x=android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, .90f, 1.06f, 1f);
            android.animation.PropertyValuesHolder y=android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, .90f, 1.06f, 1f);
            android.animation.ObjectAnimator pulse=android.animation.ObjectAnimator.ofPropertyValuesHolder(view,x,y);
            pulse.setDuration(220);pulse.setInterpolator(new android.view.animation.DecelerateInterpolator());pulse.start();
            lastSwitch=android.os.SystemClock.elapsedRealtime();
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.setSelected(view.equals(child));
            }
            styleSelection();
            Float preset = presetMap.get(view.getId());
            if (preset != null && auxButtonListener != null) {
                // P72: a zoom preset of a module: the zoom only on the active module, else that module at this zoom
                String slot = auxButtonsMap.get(view.getId());
                if (slot.equals(ModuleRegistry.active())) {
                    if (presetZoom != null) presetZoom.accept(preset);
                } else {
                    ModuleRegistry.select(slot);
                    com.particlesdevs.photoncamera.control.ZoomController.onPreset(slot, preset);
                    if (getContext() instanceof android.app.Activity) touchDial();
                    auxButtonListener.onAuxButtonClicked(ModuleRegistry.camera(slot));
                }
                return;
            }
            if (auxButtonListener != null)
            {
                String slot=auxButtonsMap.get(view.getId());
                ModuleRegistry.select(slot);
                com.particlesdevs.photoncamera.control.ZoomController.onButton(slot);
                if(getContext() instanceof android.app.Activity)touchDial();
                auxButtonListener.onAuxButtonClicked(ModuleRegistry.camera(slot));
            }
        }
    }

    int addNewButton(String cameraId, String buttonText) {
        LensButton b = new LensButton(getContext());
        b.setLayoutParams(buttonParams);
        b.setText(display(buttonText));
        b.setLabelRotation(labelRotation);
        b.setContentDescription(Lang.t(getContext(), "Объектив ", "Lens ") + display(buttonText));
        b.setOnLongClickListener(view -> showAllModules());
        int buttonId = View.generateViewId();
        b.setId(buttonId);
        this.auxButtonsMap.put(buttonId, cameraId);
        addView(b);
        return buttonId;
    }

    private boolean modulesOverflow() {
        if (getWidth() <= 0) return false;
        int total = getPaddingLeft() + getPaddingRight();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            total += child.getMeasuredWidth();
            if (child.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
                android.view.ViewGroup.MarginLayoutParams margins =
                        (android.view.ViewGroup.MarginLayoutParams) child.getLayoutParams();
                total += margins.leftMargin + margins.rightMargin;
            }
            if (child.getLeft() < getPaddingLeft() || child.getRight() > getWidth() - getPaddingRight()) return true;
            // P181: a tile measured narrower than its (fitted) least width was squeezed: it does not fit either
            if (child instanceof LensButton && child.getMeasuredWidth() < ((LensButton) child).getMinWidth()) return true;
        }
        return total > getWidth();
    }

    private boolean showAllModules() {
        if (!modulesOverflow() || auxButtonsModel == null || !auxButtonsModel.isEnabled()) return false;
        List<View> buttons = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < getChildCount(); i++) {
            View button = getChildAt(i);
            String slot = auxButtonsMap.get(button.getId());
            if (slot == null || presetMap.containsKey(button.getId()) || !ModuleRegistry.visible(slot)) continue;
            buttons.add(button);
            labels.add(display(ModuleRegistry.label(slot)) + Lang.t(getContext(), " · камера ", " · camera ")
                    + ModuleRegistry.camera(slot));
        }
        if (buttons.size() <= 1) return false;
        DialogStyle.builder(getContext()).setTitle(Lang.t(getContext(), "Модули", "Modules"))
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    View button = buttons.get(which);
                    String slot = auxButtonsMap.get(button.getId());
                    if (button.getParent() == this && slot != null && ModuleRegistry.visible(slot)) onAuxButtonClick(button);
                }).show();
        return true;
    }

    /** The selected button shows the live zoom ("2.3×") while it differs from the module's own ratio. */
    public void setZoomLabel(float zoom) {
        String active = ModuleRegistry.active();
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            String slot = auxButtonsMap.get(v.getId());
            if (!(v instanceof Button) || slot == null) continue;
            boolean selected = slot.equals(active) && selectedHere(v.getId(), slot, zoom);
            v.setSelected(selected);
            Float preset = presetMap.get(v.getId());
            if (preset != null) {
                ((Button) v).setText(display(com.particlesdevs.photoncamera.capture.XiaomiTeleZoom.presetLabel(preset)));
                continue;
            }
            ((Button) v).setText(selected && Math.abs(zoom - ModuleRegistry.zoom(slot)) >= 0.05f
                    ? display(String.format(Locale.US, "%.1f×", zoom).replace(".0×", "×")) : display(ModuleRegistry.label(slot)));
        }
        styleSelection();
    }

    private Runnable dialRefresh;
    /** Lets the owner refresh and show the zoom ruler after a button tap. */
    public void setDialRefresh(Runnable dialRefresh){this.dialRefresh=dialRefresh;}
    private void touchDial(){if(dialRefresh!=null)dialRefresh.run();}

    /**
     * P32: only the labels turn upright with the phone; the buttons and the selected pill keep their portrait shape (a
     * turned button made the pill a tall capsule clipped flat by the strip).
     */
    public void rotateLabels(int orientation, long duration) {
        labelRotation=orientation;
        for(int i=0;i<getChildCount();i++){
            View child=getChildAt(i);
            if(child instanceof LensButton)((LensButton)child).turnLabel(orientation,duration);
        }
    }

    /** Horizontal drag on the strip: continuous zoom (pixels since the previous event). */
    public interface ZoomDrag { void onDrag(float dx); }
    private ZoomDrag zoomDrag;
    public void setZoomDrag(ZoomDrag zoomDrag){this.zoomDrag=zoomDrag;}
    private float lastDragX;
    private float touchX,touchY;
    @Override public boolean onInterceptTouchEvent(android.view.MotionEvent e){
        if(e.getActionMasked()==android.view.MotionEvent.ACTION_DOWN){touchX=e.getX();touchY=e.getY();}
        if(e.getActionMasked()==android.view.MotionEvent.ACTION_MOVE&&Math.abs(e.getX()-touchX)>android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop()&&Math.abs(e.getX()-touchX)>Math.abs(e.getY()-touchY)){
            // P37: the drag continues from here. A drag that starts on a lens button reaches onTouchEvent without its
            // DOWN, so lastDragX was stale (0 or the previous gesture's end): the first step jumped by the whole
            // distance, e.g. from a tele module straight back to 1x.
            lastDragX=e.getX();
            return true;
        }
        return super.onInterceptTouchEvent(e);
    }
    @Override public boolean onTouchEvent(android.view.MotionEvent e){
        if(zoomDrag!=null){
            if(e.getActionMasked()==android.view.MotionEvent.ACTION_DOWN)lastDragX=e.getX();
            else if(e.getActionMasked()==android.view.MotionEvent.ACTION_MOVE){zoomDrag.onDrag(e.getX()-lastDragX);lastDragX=e.getX();}
        }
        // SIMPLE_UI §1: the old swipe-to-the-next-lens (unreachable with a drag zoom) is gone
        return true;
    }
    public interface AuxButtonListener {
        void onAuxButtonClicked(String cameraId);
    }
}

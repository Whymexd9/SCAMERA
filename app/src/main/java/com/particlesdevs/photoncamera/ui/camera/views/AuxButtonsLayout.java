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
import com.particlesdevs.photoncamera.app.PhotonCamera;
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
 * P25 look: one pill-shaped CARD with a LINE stroke (aux_container_background) under the bottom bar's zoom ruler, the
 * active lens filled with the camera accent (INK text), the others MUTED, three dots between them, decimal commas
 * («0,6×», «2,5×»). The gestures are unchanged (owner's answer 2): tap selects a lens, a horizontal drag zooms.
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

    private final LinearLayout.LayoutParams buttonParams;
    private AuxButtonListener auxButtonListener;
    private AuxButtonsModel auxButtonsModel;
    private long lastSwitch = -500;
    private int labelRotation;
    private final List<String> displayedSlots = new ArrayList<>();
    private final List<String> displayedLabels = new ArrayList<>();

public AuxButtonsLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);

        float density = context.getResources().getDisplayMetrics().density;
        // 8dp between two lenses: room for the three dots.
        buttonParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Math.round(40 * density));
        int gap = Math.round(4 * density);
        buttonParams.setMargins(gap, 0, gap, 0);

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
        SettingsManager manager = PhotonCamera.getSettingsManagerStatic();
        ModuleRegistry.initialize("front", auxButtonsModel.getFrontCameras());
        ModuleRegistry.initialize("back", auxButtonsModel.getBackCameras());
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
            if(best!=null)ModuleRegistry.select(best);
        }
        List<String> visible = new ArrayList<>(), labels = new ArrayList<>();
        for (String slot : slots) if (ModuleRegistry.visible(slot)) {visible.add(slot);labels.add(ModuleRegistry.label(slot));}
        if (!visible.equals(displayedSlots) || !labels.equals(displayedLabels)) {
            removeAllViews();auxButtonsMap.clear();
            displayedSlots.clear();displayedSlots.addAll(visible);
            displayedLabels.clear();displayedLabels.addAll(labels);
            for(int i=0;i<visible.size();i++)addNewButton(visible.get(i),labels.get(i));
        }
        setListenerAndSelected(activeId);
        updateVisibility();
    }

    private void setListenerAndSelected(String activeId) {
        View.OnClickListener auxButtonListener = this::onAuxButtonClick;
        for (int i = 0; i < getChildCount(); i++) {
            View button = getChildAt(i);
            button.setOnClickListener(auxButtonListener);
            button.setSelected(ModuleRegistry.active().equals(auxButtonsMap.get(button.getId())) ||
                    (!ModuleRegistry.slots().contains(ModuleRegistry.active()) && activeId.equals(ModuleRegistry.camera(auxButtonsMap.get(button.getId())))));
        }
        styleSelection();
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

    /** The active lens in bold; the colours follow the selected state. */
    private void styleSelection() {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v instanceof Button) ((Button) v).setTypeface(null, v.isSelected() ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
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

    private void addNewButton(String cameraId, String buttonText) {
        Button b = new Button(getContext());
        b.setLayoutParams(buttonParams);
        float density = getResources().getDisplayMetrics().density;
        b.setMinimumWidth(Math.round(48 * density));
        b.setMinWidth(Math.round(48 * density));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        int padding = Math.round(density * 9f);
        b.setPadding(padding, 0, padding, 0);
        b.setGravity(android.view.Gravity.CENTER);
        b.setIncludeFontPadding(false);
        b.setMaxLines(1);
        b.setHorizontallyScrolling(false);
        b.setText(display(buttonText));
        b.setRotation(labelRotation);
        b.setContentDescription(Lang.t(getContext(), "Объектив ", "Lens ") + display(buttonText));
        b.setTextSize(14);
        int accent = com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette.camera(getContext());
        b.setTextColor(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_selected},{}},
                new int[]{com.particlesdevs.photoncamera.ui.settings.SettingsStyle.INK, com.particlesdevs.photoncamera.ui.settings.SettingsStyle.MUTED}));
        android.graphics.drawable.GradientDrawable selected = new android.graphics.drawable.GradientDrawable();
        selected.setColor(accent);
        selected.setCornerRadius(100 * density);
        android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_selected},selected);
        states.addState(new int[]{},new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        b.setBackground(states);
        b.setStateListAnimator(null);
        b.setBackgroundTintList(null);
        b.setTransformationMethod(null);
        int buttonId = View.generateViewId();
        b.setId(buttonId);
        this.auxButtonsMap.put(buttonId, cameraId);
        addView(b);
    }

    /** The selected button shows the live zoom ("2.3×") while it differs from the module's own ratio. */
    public void setZoomLabel(float zoom) {
        String active = ModuleRegistry.active();
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            String slot = auxButtonsMap.get(v.getId());
            if (!(v instanceof Button) || slot == null) continue;
            boolean selected = slot.equals(active);
            v.setSelected(selected);
            ((Button) v).setText(selected && Math.abs(zoom - ModuleRegistry.zoom(slot)) >= 0.05f
                    ? display(String.format(Locale.US, "%.1f×", zoom).replace(".0×", "×")) : display(ModuleRegistry.label(slot)));
        }
        styleSelection();
    }

    private Runnable dialRefresh;
    /** Lets the owner refresh and show the zoom ruler after a button tap. */
    public void setDialRefresh(Runnable dialRefresh){this.dialRefresh=dialRefresh;}
    private void touchDial(){if(dialRefresh!=null)dialRefresh.run();}

    public void rotateLabels(int orientation, long duration) {
        labelRotation=orientation;
        for(int i=0;i<getChildCount();i++){
            View child=getChildAt(i);
            float start=child.getRotation(),delta=((orientation-start+540)%360)-180;
            child.animate().rotation(start+delta).setDuration(duration).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
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
        if(e.getActionMasked()==android.view.MotionEvent.ACTION_MOVE&&Math.abs(e.getX()-touchX)>android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop()&&Math.abs(e.getX()-touchX)>Math.abs(e.getY()-touchY))return true;
        return super.onInterceptTouchEvent(e);
    }
    @Override public boolean onTouchEvent(android.view.MotionEvent e){
        if(zoomDrag!=null){
            if(e.getActionMasked()==android.view.MotionEvent.ACTION_DOWN)lastDragX=e.getX();
            else if(e.getActionMasked()==android.view.MotionEvent.ACTION_MOVE){zoomDrag.onDrag(e.getX()-lastDragX);lastDragX=e.getX();}
            return true;
        }
        if(e.getActionMasked()==android.view.MotionEvent.ACTION_UP){
            float dx=e.getX()-touchX;
            if(Math.abs(dx)>getResources().getDisplayMetrics().density*24)for(int i=0;i<getChildCount();i++)if(getChildAt(i).isSelected()){
                int next=i+(dx<0?1:-1);if(next>=0&&next<getChildCount())onAuxButtonClick(getChildAt(next));break;
            }
        }
        return true;
    }
    /** Three dots between two lenses (MUTED at 60 %). */
    @Override protected void dispatchDraw(android.graphics.Canvas canvas) {
        super.dispatchDraw(canvas);
        android.graphics.Paint p=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor((com.particlesdevs.photoncamera.ui.settings.SettingsStyle.MUTED&0x00FFFFFF)|0x99000000);
        float d=getResources().getDisplayMetrics().density;
        for(int i=1;i<getChildCount();i++) {
            float x=(getChildAt(i-1).getRight()+getChildAt(i).getLeft())/2f;
            for(int t=-1;t<=1;t++)canvas.drawCircle(x+t*2.5f*d,getHeight()/2f,.9f*d,p);
        }
    }
    public interface AuxButtonListener {
        void onAuxButtonClicked(String cameraId);
    }
}

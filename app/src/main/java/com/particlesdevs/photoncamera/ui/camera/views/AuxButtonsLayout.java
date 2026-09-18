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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Container for multi-camera buttons.
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
    private boolean hiddenBySettings;
    private long lastSwitch = -500;

public AuxButtonsLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);

        int margin = (int) context.getResources().getDimension(R.dimen.aux_button_internal_margin);
        int size = (int) context.getResources().getDimension(R.dimen.vf_lens_height);
        buttonParams = new LinearLayout.LayoutParams(0, size, 1f);
        buttonParams.setMargins(margin, margin, margin, margin);

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
        removeAllViews();
        auxButtonsMap.clear();
        SettingsManager manager = PhotonCamera.getSettingsManagerStatic();
        List<CameraLensData> ordered = new ArrayList<>(cameraLensDataList);
        ordered.sort(Comparator.comparingInt(data -> lensOrder(manager, data.getCameraId())));
        ModuleRegistry.initialize("front", auxButtonsModel.getFrontCameras());
        ModuleRegistry.initialize("back", auxButtonsModel.getBackCameras());
        String side = cameraLensDataList == auxButtonsModel.getFrontCameras() ? "front" : "back";
        List<String> slots = ModuleRegistry.initialize(side, ordered);
        slots.sort(Comparator.comparingInt(ModuleRegistry::order));
        String currentSlot=ModuleRegistry.active();
        if(!slots.contains(currentSlot)||!ModuleRegistry.camera(currentSlot).equals(activeId))
            for(String slot:slots)if(ModuleRegistry.visible(slot)&&ModuleRegistry.camera(slot).equals(activeId)){ModuleRegistry.select(slot);break;}
        for (String slot : slots) if (ModuleRegistry.visible(slot)) addNewButton(slot, ModuleRegistry.label(slot));
        setListenerAndSelected(activeId);
        updateVisibility();
    }

    private static int lensOrder(SettingsManager manager, String cameraId) {
        if (manager == null) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(manager.getString(
                    "default_scope", "lens_order_" + cameraId, String.valueOf(Integer.MAX_VALUE)));
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private void setListenerAndSelected(String activeId) {
        View.OnClickListener auxButtonListener = this::onAuxButtonClick;
        for (int i = 0; i < getChildCount(); i++) {
            View button = getChildAt(i);
            button.setOnClickListener(auxButtonListener);
            if (ModuleRegistry.active().equals(auxButtonsMap.get(button.getId())) ||
                    (!ModuleRegistry.slots().contains(ModuleRegistry.active()) && activeId.equals(ModuleRegistry.camera(auxButtonsMap.get(button.getId())))))
                button.setSelected(true);
        }
    }

    private void updateVisibility() {
        setVisibility(hiddenBySettings || getChildCount() <= 1 ? View.INVISIBLE : View.VISIBLE);
    }

    public void setAuxButtonsHidden(boolean hidden) {
        hiddenBySettings = hidden;
        if (hidden) {
            animate().setDuration(200).alpha(0).scaleX(0).scaleY(0)
                    .withEndAction(() -> setVisibility(View.INVISIBLE)).start();
        } else {
            updateVisibility();
            animate().setDuration(200).alpha(1).scaleX(1).scaleY(1).start();
        }
    }

    private void onAuxButtonClick(View view) {
        if (auxButtonsModel.isEnabled() && android.os.SystemClock.elapsedRealtime()-lastSwitch>=500) {
            lastSwitch=android.os.SystemClock.elapsedRealtime();
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.setSelected(view.equals(child));
            }
            if (auxButtonListener != null)
            {
                String slot=auxButtonsMap.get(view.getId());
                ModuleRegistry.select(slot);
                auxButtonListener.onAuxButtonClicked(ModuleRegistry.camera(slot));
            }
        }
    }

    private void addNewButton(String cameraId, String buttonText) {
        Button b = new Button(getContext());
        b.setLayoutParams(buttonParams);
        b.setMinimumWidth(0);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        int padding = Math.round(getResources().getDisplayMetrics().density * 6f);
        b.setPadding(padding, 0, padding, 0);
        b.setGravity(android.view.Gravity.CENTER);
        b.setIncludeFontPadding(false);
        b.setMaxLines(1);
        b.setHorizontallyScrolling(false);
        b.setText(buttonText);
        b.setTextSize(13);
        b.setTextColor(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_selected},{}},new int[]{com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette.camera(getContext()),0xFFFFFFFF}));
        android.graphics.drawable.Drawable selected = new android.graphics.drawable.Drawable() {
            final android.graphics.Paint p=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(android.graphics.Canvas c){p.setColor(0x99000000);android.graphics.Rect r=getBounds();c.drawCircle(r.exactCenterX(),r.exactCenterY(),Math.min(r.width(),r.height())/2f,p);}
            @Override public void setAlpha(int a){} @Override public void setColorFilter(android.graphics.ColorFilter f){}
            @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
        };
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

    private float touchX,touchY;
    @Override public boolean onInterceptTouchEvent(android.view.MotionEvent e){
        if(e.getActionMasked()==android.view.MotionEvent.ACTION_DOWN){touchX=e.getX();touchY=e.getY();}
        if(e.getActionMasked()==android.view.MotionEvent.ACTION_MOVE&&Math.abs(e.getX()-touchX)>android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop()&&Math.abs(e.getX()-touchX)>Math.abs(e.getY()-touchY))return true;
        return super.onInterceptTouchEvent(e);
    }
    @Override public boolean onTouchEvent(android.view.MotionEvent e){
        if(e.getActionMasked()==android.view.MotionEvent.ACTION_UP){
            float dx=e.getX()-touchX;
            if(Math.abs(dx)>getResources().getDisplayMetrics().density*24)for(int i=0;i<getChildCount();i++)if(getChildAt(i).isSelected()){
                int next=i+(dx<0?1:-1);if(next>=0&&next<getChildCount())onAuxButtonClick(getChildAt(next));break;
            }
        }
        return true;
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        int screen=getResources().getDisplayMetrics().widthPixels;
        int height=Math.round(screen * .088f);
        for(int i=0;i<getChildCount();i++){
            View v=getChildAt(i);LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)v.getLayoutParams();
            lp.height=Math.round(screen*.077f);v.setLayoutParams(lp);
            if(v instanceof Button)((Button)v).setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,screen*.028f);
        }
        super.onMeasure(widthSpec,MeasureSpec.makeMeasureSpec(height,MeasureSpec.EXACTLY));
    }

    @Override protected void dispatchDraw(android.graphics.Canvas canvas) {
        super.dispatchDraw(canvas);
        android.graphics.Paint p=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);p.setColor(0x99FFFFFF);
        float d=getResources().getDisplayMetrics().density;
        for(int i=1;i<getChildCount();i++) {float x=getChildAt(i).getLeft();for(int t=-1;t<=1;t++)canvas.drawCircle(x+t*3*d,getHeight()/2f,.6f*d,p);}
    }
    public interface AuxButtonListener {
        void onAuxButtonClicked(String cameraId);
    }
}

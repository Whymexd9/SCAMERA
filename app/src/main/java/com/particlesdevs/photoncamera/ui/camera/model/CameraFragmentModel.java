package com.particlesdevs.photoncamera.ui.camera.model;

import android.graphics.Bitmap;

import androidx.databinding.BaseObservable;
import androidx.databinding.Bindable;

import com.particlesdevs.photoncamera.BR;

/**
 * Class that holds the ui state, for now the orientation
 */
public class CameraFragmentModel extends BaseObservable {
    /** Settings sheet below the edge; only its handle is on screen. */
    public static final int SHEET_HIDDEN = 0;
    /** Settings sheet shows the handle, the quick buttons and the group cells. */
    public static final int SHEET_PEEK = 1;
    /** Settings sheet shows the full settings list. */
    public static final int SHEET_FULL = 2;

    private int orientation;
    private int duration;
    private Bitmap bitmap;
    private int sheetLevel = SHEET_HIDDEN;
    private float screenAspectRatio = 9f / 16;
    private String dummyAspectRatio = "16:9";

    @Bindable
    public float getScreenAspectRatio() {
        return screenAspectRatio;
    }

    public void setScreenAspectRatio(float screenAspectRatio) {
        this.screenAspectRatio = screenAspectRatio;
        notifyPropertyChanged(BR.screenAspectRatio);
    }

    @Bindable
    public Bitmap getBitmap() {
        return bitmap;
    }

    public void setBitmap(Bitmap bitmap) {
        this.bitmap = bitmap;
        notifyChange();
    }

    @Bindable
    public int getOrientation() {
        return orientation;
    }

    /**
     * set the orientation and note the binded views about the change
     *
     * @param orientation
     */
    public void setOrientation(int orientation) {
        this.orientation = orientation;
        notifyChange();
    }

    public int getDuration() {
        return duration;
    }

    public void setDuration(int duration) {
        this.duration = duration;
    }
    /** Settings sheet level: {@link #SHEET_HIDDEN}, {@link #SHEET_PEEK} or {@link #SHEET_FULL}. */
    @Bindable
    public int getSheetLevel() {
        return sheetLevel;
    }

    /**
     * Sets the settings sheet level, clamped to HIDDEN..FULL. Idempotent, and notifies only the
     * bindings of this property, so the sheet is not asked to move again for the same level.
     */
    public void setSheetLevel(int sheetLevel) {
        sheetLevel = Math.max(SHEET_HIDDEN, Math.min(SHEET_FULL, sheetLevel));
        if (this.sheetLevel == sheetLevel) return;
        this.sheetLevel = sheetLevel;
        notifyPropertyChanged(BR.sheetLevel);
    }
    
    @Bindable
    public String getDummyAspectRatio() {
        return dummyAspectRatio;
    }
    
    public void setDummyAspectRatio(String dummyAspectRatio) {
        this.dummyAspectRatio = dummyAspectRatio;
        notifyPropertyChanged(BR.dummyAspectRatio);
    }
}

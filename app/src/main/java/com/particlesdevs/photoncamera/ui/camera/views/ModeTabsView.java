package com.particlesdevs.photoncamera.ui.camera.views;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.Gravity;
import com.particlesdevs.photoncamera.R;

/** Two stable targets: switching modes never slides the other mode off screen. */
public class ModeTabsView extends LinearLayout {
    public interface OnItemSelectedListener { void onItemSelected(int index); }
    private int selected;
    private OnItemSelectedListener listener;
    public ModeTabsView(Context context, AttributeSet attrs) { super(context,attrs); setOrientation(HORIZONTAL); }
    public void setValues(String[] values) {
        removeAllViews();
        for(int i=0;i<values.length;i++) {
            final int index=i;
            TextView tab=new TextView(getContext());
            tab.setText(values[i]); tab.setTextSize(13); tab.setGravity(Gravity.CENTER);
            tab.setIncludeFontPadding(false); tab.setBackgroundResource(R.drawable.manual_tab_background);
            tab.setTextColor(getResources().getColorStateList(R.color.manual_text_color,getContext().getTheme()));
            tab.setFocusable(true); tab.setOnClickListener(v->{
                if(isEnabled() && selected!=index) { setSelectedItem(index); if(listener!=null)listener.onItemSelected(index); }
            });
            addView(tab,new LayoutParams(0,LayoutParams.MATCH_PARENT,1));
        }
        setSelectedItem(selected);
    }
    public void setSelectedItem(int index) {
        selected=Math.max(0,Math.min(index,getChildCount()-1));
        for(int i=0;i<getChildCount();i++)getChildAt(i).setSelected(i==selected);
    }
    public int getSelectedItem(){ return selected; }
    public void setOnItemSelectedListener(OnItemSelectedListener listener){this.listener=listener;}
    @Override public void setEnabled(boolean enabled){super.setEnabled(enabled);for(int i=0;i<getChildCount();i++)getChildAt(i).setEnabled(enabled);}
}

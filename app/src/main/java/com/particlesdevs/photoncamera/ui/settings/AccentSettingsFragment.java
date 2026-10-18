package com.particlesdevs.photoncamera.ui.settings;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette;
import com.particlesdevs.photoncamera.util.Lang;

/**
 * «Цвет оформления» (SIMPLE_UI §4, the concept's accent page): one card with the accents as a grid of three - each a 30dp
 * colour dot over its name on the second card colour, the chosen one with an accent border. Shared by the interface and
 * every module; a tap applies it at once.
 */
public class AccentSettingsFragment extends ModuleConceptFragment {
    private static final int CARD2=0xFF232A2E;

    @Override protected void render(){
        page(Lang.t(getContext(),"Цвет оформления","Accent colour"),null);
        LinearLayout card=column();
        card.setBackground(shape(CARD,LINE,18));card.setPadding(dp(14),dp(14),dp(14),dp(4));
        body.addView(card,space(-1,-2,10));
        String selected=AccentPalette.selected(requireContext());
        LinearLayout line=null;
        for(int i=0;i<AccentPalette.VALUES.length;i++){
            if(i%3==0){line=row();line.setGravity(Gravity.TOP);card.addView(line,space(-1,-2,0));((LinearLayout.LayoutParams)line.getLayoutParams()).bottomMargin=dp(10);}
            String value=AccentPalette.VALUES[i];String name=requireContext().getString(AccentPalette.NAMES[i]);
            boolean on=value.equals(selected)||(AccentPalette.SYSTEM.equals(value)&&"default".equals(selected));
            LinearLayout swatch=column();swatch.setGravity(Gravity.CENTER_HORIZONTAL);swatch.setPadding(dp(8),dp(12),dp(8),dp(12));
            GradientDrawable bg=shape(CARD2,0,16);bg.setStroke(dp(on?2:1),on?accent:LINE);swatch.setBackground(bg);
            View dot=new View(requireContext());GradientDrawable d=new GradientDrawable();d.setShape(GradientDrawable.OVAL);d.setColor(AccentPalette.swatch(requireContext(),value));dot.setBackground(d);
            swatch.addView(dot,new LinearLayout.LayoutParams(dp(30),dp(30)));
            TextView label=text(name,12.5f,TEXT);label.setGravity(Gravity.CENTER);label.setMaxLines(2);swatch.addView(label,space(-1,-2,8));
            swatch.setTag("accent_"+value);swatch.setSelected(on);swatch.setContentDescription(name+(on?Lang.t(getContext(),", выбран",", selected"):""));
            swatch.setOnClickListener(v->{AccentPalette.select(requireContext(),value);requireActivity().recreate();});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(i%3>0)lp.leftMargin=dp(10);
            line.addView(swatch,lp);
        }
        // the last line keeps the grid's columns
        if(line!=null)for(int k=line.getChildCount();k<3;k++){View pad=new View(requireContext());LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,1,1);lp.leftMargin=dp(10);line.addView(pad,lp);}
    }
}

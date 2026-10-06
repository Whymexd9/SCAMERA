package com.particlesdevs.photoncamera.ui.settings;

import android.view.Gravity;
import android.widget.*;
import com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette;
import com.particlesdevs.photoncamera.util.Lang;

public class AccentSettingsFragment extends ModuleConceptFragment {
    @Override protected void render(){
        page(Lang.t(getContext(),"Цветовой акцент","Accent colour"),Lang.t(getContext(),"Общий для интерфейса и всех модулей","Shared by the interface and all modules"));
        LinearLayout preview=card();TextView sample=text(Lang.t(getContext(),"Так будут выглядеть выделенные элементы","Highlighted elements will look like this"),15,accent);sample.setPadding(dp(16),dp(20),dp(16),dp(20));preview.addView(sample);
        for(int i=0;i<AccentPalette.VALUES.length;i++){
            String value=AccentPalette.VALUES[i];String name=requireContext().getString(AccentPalette.NAMES[i]);
            LinearLayout c=card(),r=row();r.setPadding(dp(10),dp(4),dp(14),dp(4));
            TextView swatch=text("●",30,AccentPalette.color(value));swatch.setGravity(Gravity.CENTER);r.addView(swatch,new LinearLayout.LayoutParams(dp(48),dp(48)));r.addView(text(name,15,TEXT),new LinearLayout.LayoutParams(0,-2,1));
            String selected=AccentPalette.selected(requireContext());boolean on=value.equals(selected)||("lavender".equals(value)&&"default".equals(selected));
            r.addView(text(on?"✓":"",22,accent));r.setTag("accent_"+value);r.setContentDescription(name+(on?Lang.t(getContext(),", выбран",", selected"):""));
            r.setOnClickListener(v->{AccentPalette.select(requireContext(),value);requireActivity().recreate();});c.addView(r);
        }
    }
}

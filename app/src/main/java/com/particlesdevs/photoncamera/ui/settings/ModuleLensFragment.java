package com.particlesdevs.photoncamera.ui.settings;
import android.os.Bundle;
import android.content.Context;
import android.hardware.camera2.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.particlesdevs.photoncamera.settings.*;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.util.Lang;
import java.util.*;

public class ModuleLensFragment extends ModuleConceptFragment {
    private String page;
    /** P21: the page of one module (button): every module setting is edited here and nowhere else. */
    public static ModuleLensFragment module(String slot){ModuleLensFragment f=new ModuleLensFragment();Bundle b=new Bundle();b.putString("slot",slot);f.setArguments(b);return f;}
    private void rename(String slot){
        EditText input=new EditText(requireContext());input.setSingleLine(true);input.setText(ModuleRegistry.label(slot));
        new AlertDialog.Builder(requireContext()).setTitle(Lang.t(getContext(),"Название модуля","Module name")).setView(input).setPositiveButton(Lang.t(getContext(),"Сохранить","Save"),(d,w)->{
            ScameraApp.getSettingsManagerStatic().getDefaultPreferences().edit().putString("module_name_"+slot,input.getText().toString().trim()).apply();render();
        }).setNegativeButton(Lang.t(getContext(),"Отмена","Cancel"),null).show();
    }
    /** One module: name, Camera ID, zoom and sensor crop, visibility, its sensor settings and vendor requests, duplicates. */
    private void renderModule(String slot){
        page=slot;
        String source=ModuleRegistry.duplicateOf(slot);
        page(ModuleRegistry.label(slot)+" · ID "+ModuleRegistry.camera(slot),source==null?null:Lang.t(getContext(),"Копия модуля «"+ModuleRegistry.label(source)+"»","Copy of “"+ModuleRegistry.label(source)+"”"));
        // P148 (owner, 2026-10-16: simpler for an ordinary user, nothing removed): what most people change first - name, zoom,
        // the viewfinder button -, then the copies of the module (a copy with its own lens crop), then the expert rows
        // (Camera ID, the sensor page with RAW levels, stabilization, session type and vendor tags).
        caption(Lang.t(getContext(),"Основное","Basics"));
        navigation("◇",Lang.t(getContext(),"Название","Name"),ModuleRegistry.label(slot),()->rename(slot));
        navigation("⌕",Lang.t(getContext(),"Зум и кроп на сенсоре","Zoom and sensor crop"),String.format(java.util.Locale.US,Lang.t(getContext(),"Зум %.2f×","Zoom %.2f×"),ModuleRegistry.zoom(slot)).replaceAll("\\.?0+×","×")+(ModuleRegistry.sensorCrop(slot)?Lang.t(getContext()," · кроп на сенсоре"," · sensor crop"):""),()->zoomDialog(slot));
        boolean shown=ModuleRegistry.visible(slot);
        navigation(shown?"●":"○",Lang.t(getContext(),"Кнопка в видоискателе","Viewfinder button"),shown?Lang.t(getContext(),"Показывается · нажмите, чтобы скрыть","Shown · tap to hide"):Lang.t(getContext(),"Скрыта · нажмите, чтобы показать","Hidden · tap to show"),()->{
            ScameraApp.getSettingsManagerStatic().getDefaultPreferences().edit().putBoolean("module_visible_"+slot,!shown).apply();render();});
        // P148: copies of the module, each with its own lens crop (owner: «копирую 1х камеру и я хочу чтобы копия всегда была
        // условно с 1.3х кропом»)
        caption(source==null?Lang.t(getContext(),"Копии модуля","Copies of the module"):Lang.t(getContext(),"Копия модуля","Module copy"));
        if(source!=null)navigation("⌕",Lang.t(getContext(),"Кроп копии","Copy crop"),cropSummary(slot,source),()->cropSheet(source,ModuleRegistry.crop(slot)/ModuleRegistry.crop(source),crop->{
            float base=ModuleRegistry.zoom(source);
            ScameraApp.getSettingsManagerStatic().getDefaultPreferences().edit()
                .putString("module_crop_"+slot,cropText(ModuleRegistry.crop(source)*crop))
                .putString("module_zoom_"+slot,cropText(base*crop)).apply();
            render();
        }));
        navigation("⧉",Lang.t(getContext(),"Сделать копию с кропом","Make a copy with a crop"),Lang.t(getContext(),"Ещё одна кнопка на этом объективе: свой кроп (например, 1.3×), свои реквесты и профиль","Another button on this lens: its own crop (e.g. 1.3×), requests and profile"),()->
            cropSheet(slot,1f,crop->copyWithCrop(slot,crop)));
        if(source!=null)navigation("✕",Lang.t(getContext(),"Удалить копию","Delete copy"),Lang.t(getContext(),"Кнопка, её реквесты и настройки сенсора удаляются; исходный модуль остаётся","The button, its requests and sensor settings are deleted; the original module stays"),()->
            new AlertDialog.Builder(requireContext()).setTitle(Lang.t(getContext(),"Удалить «"+ModuleRegistry.label(slot)+"»?","Delete “"+ModuleRegistry.label(slot)+"”?")).setPositiveButton(Lang.t(getContext(),"Удалить","Delete"),(d,w)->{ModuleRegistry.remove(slot);back();}).setNegativeButton(Lang.t(getContext(),"Отмена","Cancel"),null).show());
        else note(Lang.t(getContext(),"Исходный модуль объектива удалить нельзя — его можно скрыть.","The original module of a lens can't be deleted, only hidden."));
        caption(Lang.t(getContext(),"Для опытных","Advanced"));
        navigation("▣","Camera ID",ModuleRegistry.camera(slot)+(ScameraApp.getSettingsManagerStatic().getDefaultPreferences().getString("module_id_"+slot,"").isEmpty()?Lang.t(getContext()," · авто"," · auto"):Lang.t(getContext()," · вручную"," · manual")),this::choose);
        int mode=ModuleRegistry.sensorMode(slot);
        // P316 (settings audit): a row picked in «Режим сенсора (SensorModeTable)» runs too (the OPPO way), not only the vivo vendor key
        int row=com.particlesdevs.photoncamera.capture.SensorModePick.picked(slot);
        navigation("⚙︎",Lang.t(getContext(),"Сенсор и вендорные ключи","Sensor and vendor keys"),(row>=0?Lang.t(getContext(),"Режим сенсора #"+row+" · ","Sensor mode #"+row+" · "):"")
            +(mode!=0?Lang.t(getContext(),"Сенсормод "+mode+" · ","Sensor mode "+mode+" · "):"")+Lang.t(getContext(),"Уровни RAW, стабилизация, тип сессии, режим сенсора и vendor tags этого модуля","RAW levels, stabilization, session type, sensor mode and vendor tags of this module"),()->
            // straight to this module's sensor page (it no longer has a module list row to pick it in)
            getParentFragmentManager().beginTransaction().replace(com.particlesdevs.photoncamera.R.id.settings_container,SettingsActivity.SettingsFragment.sensorPage(slot)).addToBackStack("sensor").commit());
        if(slot.equals(ModuleRegistry.active())){
            int block=com.particlesdevs.photoncamera.processing.MosaicStream.block();
            note(Lang.t(getContext(),"Цветовой блок потока: ","Stream colour block: ")+(block==0?Lang.t(getContext(),"ещё не измерен (откройте видоискатель)","not measured yet (open the viewfinder)"):block==1?Lang.t(getContext(),"обычный Bayer","plain Bayer"):block==2?Lang.t(getContext(),"Quad 2×2 (сенсормод без ремозаика)","Quad 2×2 (sensor mode without remosaic)"):Lang.t(getContext(),"Tetra 4×4 (сенсормод без ремозаика)","Tetra 4×4 (sensor mode without remosaic)")));
        }
    }
    private String cropSummary(String slot,String source){
        float c=ModuleRegistry.crop(slot)/ModuleRegistry.crop(source);
        return c<=1.0001f?Lang.t(getContext(),"Без кропа · как «"+ModuleRegistry.label(source)+"»","No crop · like “"+ModuleRegistry.label(source)+"”")
            :Lang.t(getContext(),cropText(c)+"× от «"+ModuleRegistry.label(source)+"» · кнопка "+cropText(ModuleRegistry.zoom(slot))+"×",cropText(c)+"× of “"+ModuleRegistry.label(source)+"” · button "+cropText(ModuleRegistry.zoom(slot))+"×");
    }
    @Override protected void render(){
        renderModule(getArguments()==null?ModuleRegistry.active():getArguments().getString("slot",ModuleRegistry.active()));
    }
    /** Zoom ratio of the module button and whether its frame is already cropped on the sensor. */
    private void zoomDialog(String slot){
        LinearLayout box=new LinearLayout(requireContext());box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(8),dp(20),0);
        EditText ratio=new EditText(requireContext());ratio.setSingleLine(true);ratio.setHint(Lang.t(getContext(),"Например: 0.6, 1, 3, 6","For example: 0.6, 1, 3, 6"));
        ratio.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        ratio.setText(String.format(java.util.Locale.US,"%.2f",ModuleRegistry.zoom(slot)).replaceAll("\\.?0+$",""));box.addView(ratio);
        CheckBox crop=new CheckBox(requireContext());crop.setText(Lang.t(getContext(),"Кроп на сенсоре (кадр уже обрезан режимом сенсора)","Sensor crop (the sensor mode already crops the frame)"));crop.setChecked(ModuleRegistry.sensorCrop(slot));box.addView(crop);
        new AlertDialog.Builder(requireContext()).setTitle(Lang.t(getContext(),"Зум · ","Zoom · ")+ModuleRegistry.label(slot)+" · ID "+ModuleRegistry.camera(slot)).setView(box)
            .setPositiveButton(Lang.t(getContext(),"Сохранить","Save"),(d,w)->{
                var e=ScameraApp.getSettingsManagerStatic().getDefaultPreferences().edit();
                String text=ratio.getText().toString().trim().replace(',','.');
                if(text.isEmpty())e.remove("module_zoom_"+slot);else try{Float.parseFloat(text);e.putString("module_zoom_"+slot,text);}catch(NumberFormatException bad){}
                e.putBoolean("module_sensorcrop_"+slot,crop.isChecked()).apply();render();
            }).setNeutralButton(Lang.t(getContext(),"Авто","Auto"),(d,w)->{ScameraApp.getSettingsManagerStatic().getDefaultPreferences().edit().remove("module_zoom_"+slot).remove("module_sensorcrop_"+slot).apply();render();})
            .setNegativeButton(Lang.t(getContext(),"Отмена","Cancel"),null).show();
    }
    private void choose(){
        List<String> values=new ArrayList<>(),labels=new ArrayList<>();values.add("");labels.add(Lang.t(getContext(),"Авто","Auto"));values.add("manual");labels.add(Lang.t(getContext(),"Ввести Camera ID вручную","Enter Camera ID manually"));
        try{CameraManager cm=(CameraManager)requireContext().getSystemService(Context.CAMERA_SERVICE);Set<String> ids=new LinkedHashSet<>(Arrays.asList(cm.getCameraIdList()));
            if(android.os.Build.VERSION.SDK_INT>=28)for(String id:new ArrayList<>(ids))for(String physical:cm.getCameraCharacteristics(id).getPhysicalCameraIds())if(!physical.equals(id))ids.add(id+"-"+physical);
            for(String key:ids){String desc="ID "+key;try{CameraCharacteristics c=cm.getCameraCharacteristics(key.contains("-")?key.substring(key.indexOf("-")+1):key);desc+=Lang.t(getContext(),"\nФокусное: ","\nFocal length: ")+Arrays.toString(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS))+Lang.t(getContext()," мм"," mm");desc+="\nISO: "+c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);if(android.os.Build.VERSION.SDK_INT>=28)desc+=c.getPhysicalCameraIds().isEmpty()?Lang.t(getContext()," · физическая/самостоятельная"," · physical/standalone"):Lang.t(getContext()," · логическая "," · logical ")+c.getPhysicalCameraIds();}catch(Exception ignored){}values.add(key);labels.add(desc);}
        }catch(Exception ignored){}
        new AlertDialog.Builder(requireContext()).setTitle("Camera ID · "+ModuleRegistry.label(page)).setItems(labels.toArray(new String[0]),(d,i)->{
            if(i==1){EditText input=new EditText(requireContext());input.setSingleLine(true);input.setHint(Lang.t(getContext(),"Например: 3","For example: 3"));new AlertDialog.Builder(requireContext()).setTitle(Lang.t(getContext(),"Ручной Camera ID","Manual Camera ID")).setView(input).setPositiveButton(Lang.t(getContext(),"Сохранить","Save"),(a,b)->assign(input.getText().toString().trim())).setNegativeButton(Lang.t(getContext(),"Отмена","Cancel"),null).show();}
            else assign(values.get(i));
        }).show();
    }
    private void assign(String id){
        if(!id.isEmpty()&&!id.contains("-")&&android.os.Build.VERSION.SDK_INT>=28)try{
            CameraManager cm=(CameraManager)requireContext().getSystemService(Context.CAMERA_SERVICE);
            if(!Arrays.asList(cm.getCameraIdList()).contains(id))for(String logical:cm.getCameraIdList())if(cm.getCameraCharacteristics(logical).getPhysicalCameraIds().contains(id)){id=logical+"-"+id;break;}
        }catch(Exception ignored){}
        android.content.SharedPreferences prefs=ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
        Set<String> user=new HashSet<>(prefs.getStringSet("user_camera_ids",new HashSet<>()));if(!id.isEmpty())user.add(id);
        Set<String> hidden=new HashSet<>(prefs.getStringSet("hidden_camera_ids",new HashSet<>()));hidden.remove(id);
        prefs.edit().putString("module_id_"+page,id).putStringSet("user_camera_ids",user).putStringSet("hidden_camera_ids",hidden).apply();render();
    }
}

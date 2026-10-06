package com.particlesdevs.photoncamera.ui.settings;
import android.os.Bundle;
import android.content.Context;
import android.hardware.camera2.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.particlesdevs.photoncamera.settings.*;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import java.util.*;

public class ModuleLensFragment extends ModuleConceptFragment {
    private String page;
    public static ModuleLensFragment create(String mode){ModuleLensFragment f=new ModuleLensFragment();Bundle b=new Bundle();b.putString("mode",mode);f.setArguments(b);return f;}
    /** P21: the page of one module (button). */
    public static ModuleLensFragment module(String slot){ModuleLensFragment f=create("module");f.getArguments().putString("slot",slot);return f;}
    private void rename(String slot){
        EditText input=new EditText(requireContext());input.setSingleLine(true);input.setText(ModuleRegistry.label(slot));
        new AlertDialog.Builder(requireContext()).setTitle("Название модуля").setView(input).setPositiveButton("Сохранить",(d,w)->{
            PhotonCamera.getSettingsManagerStatic().getDefaultPreferences().edit().putString("module_name_"+slot,input.getText().toString().trim()).apply();render();
        }).setNegativeButton("Отмена",null).show();
    }
    /** One module: name, Camera ID, zoom and sensor crop, visibility, its sensor settings and vendor requests, duplicates. */
    private void renderModule(String slot){
        page=slot;
        String source=ModuleRegistry.duplicateOf(slot);
        page(ModuleRegistry.label(slot)+" · ID "+ModuleRegistry.camera(slot),source==null?null:"Дубликат модуля «"+ModuleRegistry.label(source)+"»");
        navigation("◇","Название",ModuleRegistry.label(slot),()->rename(slot));
        navigation("▣","Camera ID",ModuleRegistry.camera(slot)+(PhotonCamera.getSettingsManagerStatic().getDefaultPreferences().getString("module_id_"+slot,"").isEmpty()?" · авто":" · вручную"),this::choose);
        navigation("⌕","Зум и кроп на сенсоре",String.format(java.util.Locale.US,"Зум %.2f×",ModuleRegistry.zoom(slot)).replaceAll("\\.?0+×","×")+(ModuleRegistry.sensorCrop(slot)?" · кроп на сенсоре":""),()->zoomDialog(slot));
        boolean shown=ModuleRegistry.visible(slot);
        navigation(shown?"●":"○","Кнопка в видоискателе",shown?"Показывается · нажмите, чтобы скрыть":"Скрыта · нажмите, чтобы показать",()->{
            PhotonCamera.getSettingsManagerStatic().getDefaultPreferences().edit().putBoolean("module_visible_"+slot,!shown).apply();render();});
        int mode=ModuleRegistry.sensorMode(slot);
        navigation("⚙︎","Сенсор и вендорные ключи",(mode!=0?"Сенсормод "+mode+" · ":"")+"Уровни, шум, цвет и реквесты этого модуля (выберите его в списке «Модуль камеры»)",()->{
            androidx.preference.PreferenceFragmentCompat extra=new SettingsActivity.SettingsFragment();Bundle args=new Bundle();
            args.putString(androidx.preference.PreferenceFragmentCompat.ARG_PREFERENCE_ROOT,"camera_settings_screen");extra.setArguments(args);
            getParentFragmentManager().beginTransaction().replace(com.particlesdevs.photoncamera.R.id.settings_container,extra).addToBackStack("sensor").commit();
        });
        if(slot.equals(ModuleRegistry.active())){
            int block=com.particlesdevs.photoncamera.processing.MosaicStream.block();
            note("Цветовой блок потока: "+(block==0?"ещё не измерен (откройте видоискатель)":block==1?"обычный Bayer":block==2?"Quad 2×2 (сенсормод без ремозаика)":"Tetra 4×4 (сенсормод без ремозаика)"));
        }
        caption("Дубликаты");
        navigation("⧉","Дублировать модуль","Вторая кнопка на том же объективе со своими реквестами и профилем — например, 1× с ISZ и без",()->{
            String copy=ModuleRegistry.duplicate(slot);
            if(copy==null){android.widget.Toast.makeText(requireContext(),"Нет свободного слота: скройте или удалите другой модуль",android.widget.Toast.LENGTH_LONG).show();return;}
            android.widget.Toast.makeText(requireContext(),"Создан «"+ModuleRegistry.label(copy)+"»",android.widget.Toast.LENGTH_SHORT).show();
            getParentFragmentManager().beginTransaction().replace(com.particlesdevs.photoncamera.R.id.settings_container,module(copy)).addToBackStack("module").commit();
        });
        if(source!=null)navigation("✕","Удалить дубликат","Кнопка, её реквесты и настройки сенсора удаляются; исходный модуль остаётся",()->
            new AlertDialog.Builder(requireContext()).setTitle("Удалить «"+ModuleRegistry.label(slot)+"»?").setPositiveButton("Удалить",(d,w)->{ModuleRegistry.remove(slot);back();}).setNegativeButton("Отмена",null).show());
        else note("Исходный модуль объектива удалить нельзя — его можно скрыть.");
    }
    @Override protected void render(){
        String mode=getArguments()==null?"id":getArguments().getString("mode","id");
        if(mode.equals("module")){renderModule(getArguments().getString("slot",ModuleRegistry.active()));return;}
        page(mode.equals("names")?"Названия модулей":mode.equals("order")?"Отображение кнопок":mode.equals("zoom")?"Зум-факторы кнопок":"Назначение Camera ID",null);
        List<String> slots=ModuleRegistry.slots();slots.sort(Comparator.comparing((String id)->id.startsWith("front")).thenComparingDouble(ModuleRegistry::zoom));
        String side="";
        for(String slot:slots){
            if(!mode.equals("order")&&!ModuleRegistry.visible(slot))continue; // hidden buttons are only managed on the display page
            String next=slot.startsWith("front")?"Фронтальная камера":"Задние камеры";if(!side.equals(next)){caption(next);side=next;}
            String label=ModuleRegistry.label(slot)+" · ID "+ModuleRegistry.camera(slot);
            if(mode.equals("order")){
                LinearLayout c=card(),r=row();Runnable visible=()->{var prefs=PhotonCamera.getSettingsManagerStatic().getDefaultPreferences();prefs.edit().putBoolean("module_visible_"+slot,!ModuleRegistry.visible(slot)).apply();render();};
                r.addView(mark(ModuleRegistry.visible(slot)?2:0,label,visible),new LinearLayout.LayoutParams(dp(44),dp(52)));r.addView(text(label,14,TEXT),new LinearLayout.LayoutParams(0,-2,1));
c.addView(r);
            }else navigation(mode.equals("names")?"◇":mode.equals("zoom")?"⌕":"▣",label,mode.equals("zoom")?String.format(java.util.Locale.US,"Зум %.2f×",ModuleRegistry.zoom(slot)).replaceAll("\\.?0+×","×")+(ModuleRegistry.sensorCrop(slot)?" · кроп на сенсоре":""):ModuleRegistry.visible(slot)?"Кнопка отображается":"Кнопка скрыта",()->{
                page=slot;
                if(mode.equals("zoom")){zoomDialog(slot);}
                else if(mode.equals("names")){EditText input=new EditText(requireContext());input.setSingleLine(true);input.setText(ModuleRegistry.label(slot));new AlertDialog.Builder(requireContext()).setTitle("Название модуля").setView(input).setPositiveButton("Сохранить",(d,w)->{PhotonCamera.getSettingsManagerStatic().getDefaultPreferences().edit().putString("module_name_"+slot,input.getText().toString().trim()).apply();render();}).setNegativeButton("Отмена",null).show();}
                else choose();
            });
        }
        if(slots.isEmpty())note("Откройте видоискатель, чтобы определить доступные модули камеры.");
    }
    /** Zoom ratio of the module button and whether its frame is already cropped on the sensor. */
    private void zoomDialog(String slot){
        LinearLayout box=new LinearLayout(requireContext());box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(8),dp(20),0);
        EditText ratio=new EditText(requireContext());ratio.setSingleLine(true);ratio.setHint("Например: 0.6, 1, 3, 6");
        ratio.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        ratio.setText(String.format(java.util.Locale.US,"%.2f",ModuleRegistry.zoom(slot)).replaceAll("\\.?0+$",""));box.addView(ratio);
        CheckBox crop=new CheckBox(requireContext());crop.setText("Кроп на сенсоре (кадр уже обрезан режимом сенсора)");crop.setChecked(ModuleRegistry.sensorCrop(slot));box.addView(crop);
        new AlertDialog.Builder(requireContext()).setTitle("Зум · "+ModuleRegistry.label(slot)+" · ID "+ModuleRegistry.camera(slot)).setView(box)
            .setPositiveButton("Сохранить",(d,w)->{
                var e=PhotonCamera.getSettingsManagerStatic().getDefaultPreferences().edit();
                String text=ratio.getText().toString().trim().replace(',','.');
                if(text.isEmpty())e.remove("module_zoom_"+slot);else try{Float.parseFloat(text);e.putString("module_zoom_"+slot,text);}catch(NumberFormatException bad){}
                e.putBoolean("module_sensorcrop_"+slot,crop.isChecked()).apply();render();
            }).setNeutralButton("Авто",(d,w)->{PhotonCamera.getSettingsManagerStatic().getDefaultPreferences().edit().remove("module_zoom_"+slot).remove("module_sensorcrop_"+slot).apply();render();})
            .setNegativeButton("Отмена",null).show();
    }
    private void choose(){
        List<String> values=new ArrayList<>(),labels=new ArrayList<>();values.add("");labels.add("Авто");values.add("manual");labels.add("Ввести Camera ID вручную");
        try{CameraManager cm=(CameraManager)requireContext().getSystemService(Context.CAMERA_SERVICE);Set<String> ids=new LinkedHashSet<>(Arrays.asList(cm.getCameraIdList()));
            if(android.os.Build.VERSION.SDK_INT>=28)for(String id:new ArrayList<>(ids))for(String physical:cm.getCameraCharacteristics(id).getPhysicalCameraIds())if(!physical.equals(id))ids.add(id+"-"+physical);
            for(String key:ids){String desc="ID "+key;try{CameraCharacteristics c=cm.getCameraCharacteristics(key.contains("-")?key.substring(key.indexOf("-")+1):key);desc+="\nФокусное: "+Arrays.toString(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS))+" мм";desc+="\nISO: "+c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);if(android.os.Build.VERSION.SDK_INT>=28)desc+=c.getPhysicalCameraIds().isEmpty()?" · физическая/самостоятельная":" · логическая "+c.getPhysicalCameraIds();}catch(Exception ignored){}values.add(key);labels.add(desc);}
        }catch(Exception ignored){}
        new AlertDialog.Builder(requireContext()).setTitle("Camera ID · "+ModuleRegistry.label(page)).setItems(labels.toArray(new String[0]),(d,i)->{
            if(i==1){EditText input=new EditText(requireContext());input.setSingleLine(true);input.setHint("Например: 3");new AlertDialog.Builder(requireContext()).setTitle("Ручной Camera ID").setView(input).setPositiveButton("Сохранить",(a,b)->assign(input.getText().toString().trim())).setNegativeButton("Отмена",null).show();}
            else assign(values.get(i));
        }).show();
    }
    private void assign(String id){
        if(!id.isEmpty()&&!id.contains("-")&&android.os.Build.VERSION.SDK_INT>=28)try{
            CameraManager cm=(CameraManager)requireContext().getSystemService(Context.CAMERA_SERVICE);
            if(!Arrays.asList(cm.getCameraIdList()).contains(id))for(String logical:cm.getCameraIdList())if(cm.getCameraCharacteristics(logical).getPhysicalCameraIds().contains(id)){id=logical+"-"+id;break;}
        }catch(Exception ignored){}
        android.content.SharedPreferences prefs=PhotonCamera.getSettingsManagerStatic().getDefaultPreferences();
        Set<String> user=new HashSet<>(prefs.getStringSet("user_camera_ids",new HashSet<>()));if(!id.isEmpty())user.add(id);
        Set<String> hidden=new HashSet<>(prefs.getStringSet("hidden_camera_ids",new HashSet<>()));hidden.remove(id);
        prefs.edit().putString("module_id_"+page,id).putStringSet("user_camera_ids",user).putStringSet("hidden_camera_ids",hidden).apply();render();
    }
}

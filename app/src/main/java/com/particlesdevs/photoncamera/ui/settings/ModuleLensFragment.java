package com.particlesdevs.photoncamera.ui.settings;
import android.os.Bundle;
import android.content.Context;
import android.hardware.camera2.*;
import android.widget.EditText;
import androidx.preference.*;
import androidx.appcompat.app.AlertDialog;
import com.particlesdevs.photoncamera.settings.*;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import java.util.*;

/** Independent slots with automatic, discovered and manually entered camera IDs. */
public class ModuleLensFragment extends PreferenceFragmentCompat {
    private String page;
    @Override public void onCreatePreferences(Bundle b,String root){page=getArguments()==null?null:getArguments().getString("slot");render();}
    private void render(){
        setPreferenceScreen(getPreferenceManager().createPreferenceScreen(requireContext()));
        if(page==null){
            for(String slot:ModuleRegistry.slots()){
                Preference p=new Preference(requireContext());p.setTitle(ModuleRegistry.label(slot)+" · "+slot);p.setSummary("ID "+ModuleRegistry.camera(slot)+(ModuleRegistry.visible(slot)?" · отображается":" · скрыт"));
                p.setOnPreferenceClickListener(v->{ModuleLensFragment next=new ModuleLensFragment();Bundle args=new Bundle();args.putString("slot",slot);next.setArguments(args);getParentFragmentManager().beginTransaction().replace(com.particlesdevs.photoncamera.R.id.settings_container,next).addToBackStack(slot).commit();return true;});getPreferenceScreen().addPreference(p);
            }
            return;
        }
        SwitchPreferenceCompat visible=new SwitchPreferenceCompat(requireContext());visible.setKey("module_visible_"+page);visible.setTitle("Показывать кнопку объектива");getPreferenceScreen().addPreference(visible);
        EditTextPreference name=new EditTextPreference(requireContext());name.setKey("module_name_"+page);name.setTitle("Название");name.setSummaryProvider(EditTextPreference.SimpleSummaryProvider.getInstance());getPreferenceScreen().addPreference(name);
        EditTextPreference order=new EditTextPreference(requireContext());order.setKey("module_order_"+page);order.setTitle("Порядок кнопки");order.setDefaultValue(page.substring(page.length()-1));order.setOnBindEditTextListener(e->e.setInputType(2));order.setSummaryProvider(EditTextPreference.SimpleSummaryProvider.getInstance());getPreferenceScreen().addPreference(order);
        Preference id=new Preference(requireContext());id.setTitle("Назначение Camera ID");id.setSummary("Сейчас: "+ModuleRegistry.camera(page));id.setOnPreferenceClickListener(v->{choose();return true;});getPreferenceScreen().addPreference(id);
        Preference note=new Preference(requireContext());note.setSelectable(false);note.setTitle("Профиль привязан к кнопке");note.setSummary("Разные кнопки могут использовать один Camera ID с разными настройками. Для нового ID может потребоваться повторное открытие камеры.");getPreferenceScreen().addPreference(note);
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

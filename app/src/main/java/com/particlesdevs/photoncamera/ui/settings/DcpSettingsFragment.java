package com.particlesdevs.photoncamera.ui.settings;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.processing.color.DcpProfiles;
import com.particlesdevs.photoncamera.settings.ModuleRegistry;
import com.particlesdevs.photoncamera.util.Lang;

public class DcpSettingsFragment extends ModuleConceptFragment {
    private boolean importing;
    private final ActivityResultLauncher<String[]> picker=registerForActivityResult(new ActivityResultContracts.OpenDocument(),uri->{
        if(uri==null)return;
        importing=true;render();
        android.content.Context app=requireContext().getApplicationContext();
        java.util.concurrent.CompletableFuture.supplyAsync(()->{
            try{return new String[]{DcpProfiles.importFile(app,uri),null};}
            catch(Exception ex){return new String[]{null,ex.getMessage()};}
        }).thenAccept(result->new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{
            importing=false;if(!isAdded()||root==null)return;
            if(result[0]!=null)choose(result[0]);
            else {android.widget.Toast.makeText(app,result[1],android.widget.Toast.LENGTH_LONG).show();render();}
        }));
    });
    private void choose(String id){PhotonCamera.getSettingsManagerStatic().getDefaultPreferences().edit().putString(DcpProfiles.KEY,id).apply();render();}
    @Override protected void render(){
        page(Lang.t(getContext(),"Матрицы DCP","DCP matrices"),ModuleRegistry.label(ModuleRegistry.active()));
        String selected=PhotonCamera.getSettingsManagerStatic().getDefaultPreferences().getString(DcpProfiles.KEY,"");
        DcpProfiles.list(requireContext()).forEach((id,label)->navigation("◉",(id.equals(selected)?"✓  ":"")+label,null,()->choose(id)));
        note(Lang.t(getContext(),"Выбирайте профиль для этого сенсора. Используются ColorMatrix, ForwardMatrix и источники освещения. Таблицы оттенков и тональные кривые DCP не применяются.","Pick a profile for this sensor. ColorMatrix, ForwardMatrix and the illuminants are used. DCP hue tables and tone curves are not applied."));
        note(Lang.t(getContext(),"Выбор сохраняется в профиле модуля и доступен для копирования между модулями.","The choice is saved in the module profile and can be copied between modules."));
        primary(importing?Lang.t(getContext(),"Импорт…","Importing…"):Lang.t(getContext(),"Импортировать DCP","Import DCP"),!importing,()->picker.launch(new String[]{"*/*"}));
    }
}

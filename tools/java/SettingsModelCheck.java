import com.particlesdevs.photoncamera.settings.*;
import java.util.*;

public class SettingsModelCheck {
    static void eq(double a,double b){if(Math.abs(a-b)>1e-7)throw new AssertionError(a+" != "+b);}
    static void active(Map<String,Object> p,String key){if(new SettingsAvailability(p).reason(key)!=null)throw new AssertionError("Inactive: "+key+" "+new SettingsAvailability(p).reason(key));}
    static void inactive(Map<String,Object> p,String key){if(new SettingsAvailability(p).reason(key)==null)throw new AssertionError("Unexpectedly active: "+key);}
    public static void main(String[] args){
        eq(PreferenceNumber.read("0.00390625",9),1.0/256); eq(PreferenceNumber.read("0,125",9),.125);
        eq(PreferenceNumber.read(2.5f,9),2.5);eq(PreferenceNumber.read("NaN",7),7);eq(PreferenceNumber.read("Infinity",7),7);
        if(!PreferenceNumber.bool("1.0",false)||PreferenceNumber.bool("0",true))throw new AssertionError("mixed boolean storage");
        if(!PreferenceNumber.floating(float.class))throw new AssertionError("float step=1 still float");
        if(!PreferenceNumber.format(.00390625f,true).equals("0.00390625"))throw new AssertionError("precision lost");
        eq(PreferenceNumber.progress(.30f,.10f,100,100),20);
        if(SettingsNumericRules.error("pref_noise_iso_manual_key","300.5")==null)throw new AssertionError("integer validation");
        if(SettingsNumericRules.error("pref_mfsr_frames_key","2")==null)throw new AssertionError("short burst accepted");
        eq(Double.parseDouble(SettingsNumericRules.normalized("pref_aces_gamut_key","98.25","100")),98.25);
        eq(Double.parseDouble(SettingsNumericRules.normalized("pref_noise_model_coefficient_key","NaN","1.0")),1);
        eq(SettingsNumericRules.value("pref_vivo_hdr_exposure","-1,25",0),-1.25);
        eq(SettingsNumericRules.value("pref_vivo_hdr_exposure","-9",0),-2);
        eq(SettingsNumericRules.value("pref_vivo_hdr_gamma","0",1),.5);
        eq(SettingsNumericRules.value("pref_vivo_hdr_white","9",1),1);
        eq(SettingsNumericRules.value("pref_vivo_hdr_black","9",0),.1);
        eq(SettingsNumericRules.value("pref_vivo_hdr_contrast","NaN",1),1);
        eq(SettingsNumericRules.value("pref_vivo_nice_noise_photon","0",1),.25);
        eq(SettingsNumericRules.value("pref_vivo_nice_noise_readout","NaN",1),1);
        eq(SettingsNumericRules.value("pref_vivo_nice_noise_readout","8",1),4);
        Map<String,Object> p=new HashMap<>();
        inactive(p,"hexquad_luma");active(p,"pref_remosaic_enabled_key");
        p.put("pref_remosaic_enabled_key",true);p.put("pref_remosaic_backend_key","hp9_hexquad");
        active(p,"hexquad_luma"); inactive(p,"pref_frame_count_key");inactive(p,"rt512_luma");
        p.put("hexquad_auto_iso",true);inactive(p,"hexquad_luma");active(p,"hexquad_iso_low_luma");
        p.put("hexquad_model","1");inactive(p,"hexquad_full_resolution");p.put("hexquad_model","2");active(p,"hexquad_full_resolution");
        p.put("hexquad_post_denoise",true);p.put("pref_rt_denoise_backend","rt512");active(p,"rt512_luma");inactive(p,"pref_rt_nr_luma_key");
        p.put("rt512_auto","1");inactive(p,"rt512_chroma");p.put("rt512_auto","0");active(p,"rt512_chroma");
        p.put("pref_remosaic_enabled_key",false);p.put("pref_rt_denoise_backend","legacy");
        p.put("pref_camera_mode_key","4");p.put("pref_night_merge_algorithm_key","hdrplus");inactive(p,"pref_rt_nr_luma_key");
        p.put("pref_camera_mode_key","2");active(p,"pref_rt_nr_luma_key");
        for(String tone:new String[]{"fusion","curve","opendrt","sky","off"}){
            p.put("pref_tunable_postpipeline_tonepipeline",tone);
            if(tone.equals("sky"))active(p,"pref_tunable_headroomrender_outputexposurescale");else inactive(p,"pref_tunable_headroomrender_outputexposurescale");
            if(tone.equals("fusion")||tone.equals("curve"))active(p,"pref_tunable_initial_gammax1");else inactive(p,"pref_tunable_initial_gammax1");
        }
        p.put("pref_tunable_postpipeline_tonepipeline","fusion");p.put("pref_aces_enabled_key",true);
        inactive(p,"pref_tunable_initial_gammax1");active(p,"pref_aces_gamma_curve_key");
        inactive(p,"pref_noise_iso_manual_key");p.put("pref_noise_model_profile_key","hp9");active(p,"pref_noise_iso_manual_key");
        p.put("pref_noise_dynamic_enabled_key",false);inactive(p,"pref_tunable_esd4d_enablenoisestore");
        p.put("pref_sharp_usm_enabled_key",false);inactive(p,"pref_sharp_amount_key");active(p,"pref_sharp_usm_enabled_key");
        p.clear();
        p.put("pref_camera_mode_key", "3");
        p.put("pref_vivo_hdr_enabled", true);p.put("pref_vivo_nice_enabled", true);
        p.put("pref_rt_denoise_backend", "rt512");p.put("rt512_chroma", 15);
        if (new SettingsAvailability(p).usesVcfPhoto()) throw new AssertionError("Default must retain RAW");
        p.put("pref_vivo_nice_route", "vcf2");
        Map<String,Object> saved = new HashMap<>(p);
        for (String key : new String[]{"rt512_chroma", "pref_rt_nr_luma_key",
                "pref_tunable_esd3d2_enable", "pref_tunable_ablc_enable", "pref_ai_denoise_enabled_key",
                "pref_noise_model_profile_key", "pref_aces_enabled_key", "pref_sharp_usm_enabled_key",
                "pref_raisr_enabled_key", "pref_frame_count_key", "pref_zsl_buffer_count_key",
                "pref_vivo_nice_noise_scale", "pref_vivo_hdr_contrast", "pref_save_raw_key",
                "rt_denoise_screen", "vivo_hdr_tone_screen", "sharp_settings_screen"}) inactive(p,key);
        for (String key : new String[]{"pref_vivo_nice_enabled", "pref_vivo_hdr_enabled",
                "pref_expocompensation_seekbar_key", "pref_camera_sounds_key", "scamera_full_debug",
                "vivo_nice_probe", "pref_vivo_nice_route"}) active(p,key);
        if (!saved.equals(p)) throw new AssertionError("Availability changed stored settings");
        p.put("pref_vivo_nice_enabled",false);active(p,"rt512_chroma");
        p.put("pref_vivo_nice_enabled",true);
        for (String mode : new String[]{"2", "3"}) {
            p.put("pref_camera_mode_key",mode);
            if (!new SettingsAvailability(p).usesVcfPhoto()) throw new AssertionError("Photo VCF unreachable");
            active(p,"pref_vivo_nice_route");
            p.put("pref_vivo_nice_route","raw");inactive(p,"rt512_chroma");
            active(p,"pref_vivo_nice_noise_scale");active(p,"pref_vivo_hdr_contrast");
            active(p,"pref_vivo_nice_noise_photon");active(p,"pref_vivo_nice_noise_readout");
            inactive(p,"pref_vivo_hdr_luma");inactive(p,"pref_vivo_hdr_chroma");
            active(p,"pref_sharp_usm_enabled_key");
            p.put("pref_vivo_nice_route","invalid");
            if (new SettingsAvailability(p).usesVcfPhoto()) throw new AssertionError("Unknown route enabled VCF");
            p.put("pref_vivo_nice_route","vcf2");
        }
        for (String mode : new String[]{"0", "1", "4", "5"}) {
            p.put("pref_camera_mode_key",mode);inactive(p,"rt512_chroma");
            if (new SettingsAvailability(p).usesVcfPhoto()) throw new AssertionError("RAW mode gated as VCF");
        }
        p.put("pref_camera_mode_key","3");p.put("pref_raw_mfsr_enabled_key",true);
        if (new SettingsAvailability(p).usesVcfPhoto()) throw new AssertionError("MFSR gate mismatch");
        System.out.println("Settings model PASS: exact precision, legacy types, finite bounds, mode/algorithm availability");
    }
}

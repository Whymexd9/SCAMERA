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
        if(SettingsNumericRules.error("pref_antibanding_hz_key","100.5")==null)throw new AssertionError("integer validation");
        if(SettingsNumericRules.bounds("pref_mfsr_frames_key")!=null)throw new AssertionError("removed RAW MFSR key still bounded");
        eq(Double.parseDouble(SettingsNumericRules.normalized("pref_lmc_hybrid_cdm","0.0625","0.07")),.0625);
        eq(Double.parseDouble(SettingsNumericRules.normalized("pref_lmc_hybrid_cdm","NaN","0.07")),.07);
        // the legacy post-processing keys (ACES, noise-model ISO overrides) are gone and unbounded
        if(SettingsNumericRules.bounds("pref_aces_gamut_key")!=null||SettingsNumericRules.bounds("pref_noise_iso_manual_key")!=null)
            throw new AssertionError("removed legacy post-processing key still bounded");
        eq(SettingsNumericRules.value("pref_vivo_nice_noise_photon","0",1),.25);
        eq(SettingsNumericRules.value("pref_vivo_nice_noise_readout","NaN",1),1);
        eq(SettingsNumericRules.value("pref_vivo_nice_noise_readout","8",1),4);
        Map<String,Object> p=new HashMap<>();
        // HexQuad / Quad network rows: only for SCAM HDR's mosaic «neural» modes (ISZ modules).
        inactive(p,"hexquad_luma");inactive(p,"pref_remosaic_block_key");
        p.put("pref_merge_route","scamhdr");inactive(p,"hexquad_luma");
        p.put("pref_vivo_nice_mosaic","scamera");inactive(p,"hexquad_luma");active(p,"pref_remosaic_block_key");
        p.put("pref_vivo_nice_mosaic","neural");active(p,"hexquad_luma");active(p,"quad2x2_luma");
        p.put("hexquad_auto_iso",true);inactive(p,"hexquad_luma");active(p,"hexquad_iso_low_luma");
        p.put("pref_merge_route","hybrid");inactive(p,"hexquad_iso_low_luma");inactive(p,"pref_remosaic_block_key");
        // (The plain legacy route and its tone / denoise availability are gone: every shot is the hybrid or SCAM HDR.)
        p.clear();
        p.put("pref_camera_mode_key", "3");
        p.put("pref_merge_route", "scamhdr");
        p.put("pref_vivo_nice_route", "vcf2");
        Map<String,Object> saved = new HashMap<>(p);
        // Retired route values from saved/imported configs must not bypass RAW
        // processing or hide sharpening. Exercise every mode, not only Photo.
        for (String mode : new String[]{"0", "1", "2", "3", "4", "5"}) {
            p.put("pref_camera_mode_key", mode);
            for (String route : new String[]{"raw", "vcf2", "invalid"}) {
                p.put("pref_vivo_nice_route", route);
                active(p,"pref_vivo_nice_noise_scale");
                active(p,"pref_vivo_nice_noise_photon");active(p,"pref_vivo_nice_noise_readout");
                active(p,"pref_sharp_usm_enabled_key");
            }
        }
        p.clear();p.putAll(saved);
        new SettingsAvailability(p).reason("pref_vivo_nice_noise_scale");
        if (!saved.equals(p)) throw new AssertionError("Availability changed stored settings");
        p.put("pref_merge_route","hybrid");inactive(p,"pref_vivo_nice_noise_scale");
        p.put("pref_merge_route","scamhdr");
        // LMC hybrid availability: its rows need the hybrid route (the default) and a plain Bayer route; then the stages its
        // route skips are inactive and SCAM HDR's own rows are inactive too.
        Map<String,Object> h=new HashMap<>();
        h.put("pref_merge_route","scamhdr");inactive(h,"pref_lmc_hybrid_cdm");
        h.remove("pref_merge_route");
        active(h,"pref_lmc_hybrid_cdm");active(h,"pref_lmc_hybrid_sabre61");active(h,"pref_lmc_hybrid_highlight_recovery");
        inactive(h,"pref_vivo_nice_noise_photon");
        active(h,"pref_sharp_usm_enabled_key");
        // the removed legacy switches (RAW MFSR, standalone remosaic) no longer take the hybrid off
        h.put("pref_raw_mfsr_enabled_key",true);h.put("pref_remosaic_enabled_key",true);active(h,"pref_lmc_hybrid_cdm");
        // LMC hybrid (own section): pref_lmc_hybrid_* bounds; copies of SCAM HDR knobs keep the original bounds; number lists.
        eq(SettingsNumericRules.value("pref_lmc_hybrid_sabre61","7",2),2);
        eq(SettingsNumericRules.value("pref_lmc_hybrid_highlight_recovery","150",100),100);
        if(SettingsNumericRules.bounds("pref_vivo_nice_hybrid_cdm")!=null)throw new AssertionError("legacy hybrid key still bounded");
        eq(SettingsNumericRules.value("pref_lmc_hybrid_cdm","9",0.07),2);
        eq(SettingsNumericRules.value("pref_lmc_hybrid_bento_factor","32",8),16);
        eq(SettingsNumericRules.value("pref_lmc_hybrid_zsl_frames","99",20),44);
        eq(SettingsNumericRules.value("pref_lmc_hybrid_zsl_frames","7.6",20),8);
        if(SettingsNumericRules.error("pref_lmc_hybrid_zsl_frames","3")==null)throw new AssertionError("hybrid N below 4 accepted");
        eq(SettingsNumericRules.value("pref_lmc_hybrid_noise_photon","0",1),.25);
        // P10: the SCAMERA tone keys (headroom, AgX, Exposure Fusion) and their hybrid copies are gone and unbounded
        if(SettingsNumericRules.bounds("pref_vivo_hdr_gamma")!=null||SettingsNumericRules.bounds("pref_lmc_hybrid_hdr_shadows")!=null
                ||SettingsNumericRules.bounds("pref_lmc_hybrid_fusion_dark_ev")!=null)throw new AssertionError("removed tone key still bounded");
        // the ARK tone and its sharpening stay active on SCAM HDR (one tone for both routes)
        Map<String,Object> s=new HashMap<>();s.put("pref_merge_route","scamhdr");
        active(s,"pref_lmc_hybrid_ark_ae_target");active(s,"pref_lmc_hybrid_sharp_mode");inactive(s,"pref_lmc_hybrid_cdm");
        if(SettingsNumericRules.bounds("pref_lmc_hybrid_agx_desat")!=null||SettingsNumericRules.bounds("pref_lmc_hybrid_enabled")!=null)
            throw new AssertionError("unbounded hybrid copies got bounds");
        float[] list=SettingsNumericRules.listValue("pref_lmc_hybrid_x","1; 2.5 3",new float[]{0,0,0});
        eq(list.length,3);eq(list[0],1);eq(list[1],2.5);eq(list[2],3);
        if(SettingsNumericRules.listValue("pref_lmc_hybrid_x","1,NaN,3",new float[]{0,0,0})[0]!=0)throw new AssertionError("NaN list accepted");
        if(SettingsNumericRules.listValue("pref_lmc_hybrid_x","1,2",new float[]{0,0,0}).length!=3)throw new AssertionError("short list accepted");
        if(SettingsNumericRules.listValue("pref_lmc_hybrid_x","",null)!=null)throw new AssertionError("empty list");
        System.out.println("Settings model PASS: exact precision, legacy types, finite bounds, mode/algorithm availability, LMC hybrid bounds");
    }
}

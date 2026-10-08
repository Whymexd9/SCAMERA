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
        // P28 RAW CA: off by default (rows below the mode explain themselves), passes with auto, red / blue without; bounds
        Map<String,Object> ca=new HashMap<>();
        active(ca,"pref_lmc_hybrid_rawca_mode");inactive(ca,"pref_lmc_hybrid_rawca_passes");inactive(ca,"pref_lmc_hybrid_rawca_avoid_shift");
        ca.put("pref_lmc_hybrid_rawca_mode","2");
        active(ca,"pref_lmc_hybrid_rawca_passes");active(ca,"pref_lmc_hybrid_rawca_auto");active(ca,"pref_lmc_hybrid_rawca_avoid_shift");inactive(ca,"pref_lmc_hybrid_rawca_red");
        ca.put("pref_lmc_hybrid_rawca_auto",false);active(ca,"pref_lmc_hybrid_rawca_red");active(ca,"pref_lmc_hybrid_rawca_blue");inactive(ca,"pref_lmc_hybrid_rawca_passes");
        ca.put("pref_merge_route","scamhdr");inactive(ca,"pref_lmc_hybrid_rawca_mode");
        eq(SettingsNumericRules.value("pref_lmc_hybrid_rawca_mode","7",0),2);
        eq(SettingsNumericRules.value("pref_lmc_hybrid_rawca_passes","9",2),5);
        eq(SettingsNumericRules.value("pref_lmc_hybrid_rawca_passes","1.6",2),2);
        eq(SettingsNumericRules.value("pref_lmc_hybrid_rawca_red","-9",0),-4);
        if(SettingsNumericRules.error("pref_lmc_hybrid_rawca_blue","4.5")==null)throw new AssertionError("RAW CA blue beyond 4 px accepted");
        if(SettingsNumericRules.error("pref_lmc_hybrid_rawca_passes","0")==null)throw new AssertionError("RAW CA without a pass accepted");
        // P29 / P34 / P35 native mosaic merge (the default for Quad and Tetra): its rows need “Native mosaic”, the fill threshold the
        // ArkCam fill; the split's edge kernel is live only when Quad or Tetra (“Tetra path” 0) merges on the split; worker key bounds
        Map<String,Object> mo=new HashMap<>();
        String[] nativeRows={"window","window_full","kernel_scale","native_edge_scale","kernel_g","kernel_rb","chroma_fill","tetra",
                "native_flat_scale","native_clamp"};
        active(mo,"pref_lmc_hybrid_mosaic_path");active(mo,"pref_lmc_hybrid_mosaic_frames");inactive(mo,"pref_lmc_hybrid_mosaic_edge_scale");
        for(String k:nativeRows)active(mo,"pref_lmc_hybrid_mosaic_"+k);
        inactive(mo,"pref_lmc_hybrid_mosaic_fill_support");
        mo.put("pref_lmc_hybrid_mosaic_path","0");
        for(String k:nativeRows)inactive(mo,"pref_lmc_hybrid_mosaic_"+k);
        inactive(mo,"pref_lmc_hybrid_mosaic_fill_support");active(mo,"pref_lmc_hybrid_mosaic_edge_scale");active(mo,"pref_lmc_hybrid_mosaic_frames");
        mo.put("pref_lmc_hybrid_mosaic_path","1");
        for(String k:nativeRows)active(mo,"pref_lmc_hybrid_mosaic_"+k);
        inactive(mo,"pref_lmc_hybrid_mosaic_edge_scale");
        mo.put("pref_lmc_hybrid_mosaic_tetra","0");active(mo,"pref_lmc_hybrid_mosaic_edge_scale");
        mo.put("pref_lmc_hybrid_mosaic_tetra","2");inactive(mo,"pref_lmc_hybrid_mosaic_edge_scale");
        mo.put("pref_lmc_hybrid_mosaic_tetra","1");inactive(mo,"pref_lmc_hybrid_mosaic_edge_scale");
        mo.put("pref_lmc_hybrid_mosaic_chroma_fill","1");active(mo,"pref_lmc_hybrid_mosaic_fill_support");
        mo.put("pref_merge_route","scamhdr");inactive(mo,"pref_lmc_hybrid_mosaic_path");inactive(mo,"pref_lmc_hybrid_mosaic_window");
        eq(SettingsNumericRules.value("pref_lmc_hybrid_mosaic_path","3",0),1);
        eq(SettingsNumericRules.value("pref_lmc_hybrid_mosaic_window","9",3),6);
        eq(SettingsNumericRules.value("pref_lmc_hybrid_mosaic_window","2.6",3),3);
        eq(SettingsNumericRules.value("pref_lmc_hybrid_mosaic_kernel_rb","0.1",0.85),0.5);
        eq(SettingsNumericRules.value("pref_lmc_hybrid_mosaic_native_edge_scale","2",0.4),1);
        eq(SettingsNumericRules.value("pref_lmc_hybrid_mosaic_tetra","0",2),0); // 0 = Tetra on the split (1 = T1, the default)
        eq(SettingsNumericRules.value("pref_lmc_hybrid_mosaic_tetra","3",0),2);
        eq(SettingsNumericRules.value("pref_lmc_hybrid_mosaic_tetra","-1",2),0);
        eq(SettingsNumericRules.value("pref_lmc_hybrid_mosaic_native_flat_scale","9",2.4),4);
        eq(SettingsNumericRules.value("pref_lmc_hybrid_mosaic_native_flat_scale","0.5",2.4),1);
        eq(SettingsNumericRules.value("pref_lmc_hybrid_mosaic_native_clamp","1.6",2),2);
        eq(SettingsNumericRules.value("pref_lmc_hybrid_mosaic_native_clamp","5",2),2);
        eq(SettingsNumericRules.value("pref_lmc_hybrid_mosaic_native_night_kernel_scale","0.1",1),0.25);
        eq(SettingsNumericRules.value("pref_lmc_hybrid_mosaic_native_night_edge_scale","3",0.6),1);
        eq(SettingsNumericRules.value("pref_lmc_hybrid_mosaic_tetra_night_kernel_scale","0.1",0.7),0.25);
        eq(SettingsNumericRules.value("pref_lmc_hybrid_mosaic_tetra_night_edge_scale","3",0.45),1);
        eq(SettingsNumericRules.value("pref_lmc_hybrid_mosaic_tetra_night_flat_scale","9",1.667),4);
        if(SettingsNumericRules.error("pref_lmc_hybrid_mosaic_fill_support","1.5")==null)throw new AssertionError("fill support above 1 accepted");
        if(SettingsNumericRules.error("pref_lmc_hybrid_mosaic_kernel_scale","0.1")==null)throw new AssertionError("kernel scale below 0.25 accepted");
        float[] list=SettingsNumericRules.listValue("pref_lmc_hybrid_x","1; 2.5 3",new float[]{0,0,0});
        eq(list.length,3);eq(list[0],1);eq(list[1],2.5);eq(list[2],3);
        if(SettingsNumericRules.listValue("pref_lmc_hybrid_x","1,NaN,3",new float[]{0,0,0})[0]!=0)throw new AssertionError("NaN list accepted");
        if(SettingsNumericRules.listValue("pref_lmc_hybrid_x","1,2",new float[]{0,0,0}).length!=3)throw new AssertionError("short list accepted");
        if(SettingsNumericRules.listValue("pref_lmc_hybrid_x","",null)!=null)throw new AssertionError("empty list");
        // «Формат фото»: the rows of the formats not chosen are hidden; Ultra HDR / JPEG quality need a JPEG in the shot; quality bounds
        Map<String,Object> f=new HashMap<>();
        String[] avifRows={"pref_avif_quality","pref_avif_lossless","pref_avif_depth","pref_avif_chroma","pref_avif_speed"};
        for(String k:new String[]{"pref_heic_quality","pref_webp_quality","pref_webp_lossless","pref_photo_also_jpeg"})
            if(!new SettingsAvailability(f).hidden(k))throw new AssertionError("JPEG default shows "+k);
        for(String k:avifRows)if(!new SettingsAvailability(f).hidden(k))throw new AssertionError("JPEG default shows "+k);
        if(new SettingsAvailability(f).hidden("pref_photo_format")||new SettingsAvailability(f).hidden("pref_jpeg_quality"))throw new AssertionError("format rows hidden");
        active(f,"pref_ultrahdr_key");active(f,"pref_jpeg_quality");active(f,"pref_photo_format");
        f.put("pref_photo_format","heic");
        if(new SettingsAvailability(f).hidden("pref_heic_quality")||new SettingsAvailability(f).hidden("pref_photo_also_jpeg"))throw new AssertionError("HEIC rows hidden");
        if(!new SettingsAvailability(f).hidden("pref_webp_quality")||!new SettingsAvailability(f).hidden("pref_webp_lossless"))throw new AssertionError("WebP rows shown for HEIC");
        inactive(f,"pref_ultrahdr_key");inactive(f,"pref_jpeg_quality");active(f,"pref_heic_quality");
        f.put("pref_photo_also_jpeg",true);active(f,"pref_ultrahdr_key");active(f,"pref_jpeg_quality");
        f.put("pref_photo_format","webp");f.put("pref_photo_also_jpeg",false);
        if(new SettingsAvailability(f).hidden("pref_webp_quality")||new SettingsAvailability(f).hidden("pref_webp_lossless")||!new SettingsAvailability(f).hidden("pref_heic_quality"))
            throw new AssertionError("WebP rows");
        inactive(f,"pref_ultrahdr_key");active(f,"pref_webp_quality");
        f.put("pref_webp_lossless",true);inactive(f,"pref_webp_quality");active(f,"pref_webp_lossless");
        f.put("pref_photo_format","jxl");active(f,"pref_ultrahdr_key"); // an unknown stored value is JPEG
        if(!new SettingsAvailability(f).hidden("pref_webp_quality"))throw new AssertionError("unknown format shows WebP rows");
        // AVIF: its five rows only with AVIF chosen (not with WebP / HEIC), «Без потерь» explains quality, depth and chroma
        f.put("pref_photo_format","webp");
        for(String k:avifRows)if(!new SettingsAvailability(f).hidden(k))throw new AssertionError("WebP shows "+k);
        f.put("pref_photo_format","avif");f.remove("pref_webp_lossless");
        for(String k:avifRows){if(new SettingsAvailability(f).hidden(k))throw new AssertionError("AVIF hides "+k);active(f,k);}
        if(!new SettingsAvailability(f).hidden("pref_webp_quality")||!new SettingsAvailability(f).hidden("pref_heic_quality")||new SettingsAvailability(f).hidden("pref_photo_also_jpeg"))
            throw new AssertionError("AVIF rows");
        inactive(f,"pref_ultrahdr_key");inactive(f,"pref_jpeg_quality");
        f.put("pref_avif_lossless",true);
        inactive(f,"pref_avif_quality");inactive(f,"pref_avif_depth");inactive(f,"pref_avif_chroma");active(f,"pref_avif_speed");active(f,"pref_avif_lossless");
        f.put("pref_photo_also_jpeg",true);active(f,"pref_ultrahdr_key");
        eq(SettingsNumericRules.value("pref_avif_quality","0",90),1);eq(SettingsNumericRules.value("pref_avif_quality","101",90),100);
        eq(SettingsNumericRules.value("pref_avif_speed","12",6),10);eq(SettingsNumericRules.value("pref_avif_speed","x",6),6);
        eq(SettingsNumericRules.value("pref_heic_quality","0",90),1);eq(SettingsNumericRules.value("pref_webp_quality","150",90),100);
        eq(SettingsNumericRules.value("pref_webp_quality","NaN",90),90);
        if(SettingsNumericRules.error("pref_heic_quality","50.5")==null)throw new AssertionError("fractional HEIC quality accepted");
        System.out.println("Settings model PASS: exact precision, legacy types, finite bounds, mode/algorithm availability, LMC hybrid bounds, RAW CA, native mosaic, photo format");
    }
}

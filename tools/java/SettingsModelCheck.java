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
        // H1: a slider stores the decimals its step and minimum need (Bento auto threshold 0.0005 was stored as "0.00")
        if(!PreferenceNumber.gridText(5/10000.0,PreferenceNumber.gridDecimals(10000,0)).equals("0.0005"))throw new AssertionError("0.0005 rounded");
        if(!PreferenceNumber.gridText(1.414f,Math.max(PreferenceNumber.gridDecimals(100,1),PreferenceNumber.decimalsOf("1.414"))).equals("1.414"))throw new AssertionError("1.414 rounded");
        if(!PreferenceNumber.gridText(0.001,PreferenceNumber.gridDecimals(100,.001f)).equals("0.001"))throw new AssertionError("0.001 rounded");
        if(!PreferenceNumber.gridText(0.5,PreferenceNumber.gridDecimals(20,0)).equals("0.50"))throw new AssertionError("two decimals kept");
        eq(PreferenceNumber.progress(.30f,.10f,100,100),20);
        if(SettingsNumericRules.error("pref_antibanding_hz_key","100.5")==null)throw new AssertionError("integer validation");
        if(SettingsNumericRules.bounds("pref_mfsr_frames_key")!=null)throw new AssertionError("removed RAW MFSR key still bounded");
        eq(Double.parseDouble(SettingsNumericRules.normalized("pref_scam_hybrid_cdm","0.0625","0.07")),.0625);
        eq(Double.parseDouble(SettingsNumericRules.normalized("pref_scam_hybrid_cdm","NaN","0.07")),.07);
        // the legacy post-processing keys (ACES, noise-model ISO overrides) are gone and unbounded
        if(SettingsNumericRules.bounds("pref_aces_gamut_key")!=null||SettingsNumericRules.bounds("pref_noise_iso_manual_key")!=null)
            throw new AssertionError("removed legacy post-processing key still bounded");
        eq(SettingsNumericRules.value("pref_scamhdr_noise_photon","0",1),.25);
        eq(SettingsNumericRules.value("pref_scamhdr_noise_readout","NaN",1),1);
        eq(SettingsNumericRules.value("pref_scamhdr_noise_readout","8",1),4);
        Map<String,Object> p=new HashMap<>();
        // P175: the neural remosaic is gone: its old switch gates nothing, its old rows get no rule (and no XML row)
        p.put("pref_scam_hybrid_neural_remosaic",false);
        for(String k:new String[]{"hexquad_luma","quad2x2_luma","hexquad_iso_low_luma","pref_remosaic_block_key","pref_tetra_response_key"})
            active(p,k);
        // Retired route values from saved / imported configs (the SCAM HDR route included) never hide the sharpening.
        p.clear();
        p.put("pref_camera_mode_key", "3");
        p.put("pref_merge_route", "scamhdr");
        p.put("pref_scamhdr_route", "vcf2");
        Map<String,Object> saved = new HashMap<>(p);
        for (String mode : new String[]{"0", "1", "2", "3", "4", "5"}) {
            p.put("pref_camera_mode_key", mode);
            for (String route : new String[]{"raw", "vcf2", "invalid"}) {
                p.put("pref_scamhdr_route", route);
                active(p,"pref_scam_hybrid_ark_sharp_gain");active(p,"pref_scam_hybrid_cdm");
            }
        }
        p.clear();p.putAll(saved);
        new SettingsAvailability(p).reason("pref_scam_hybrid_cdm");
        if (!saved.equals(p)) throw new AssertionError("Availability changed stored settings");
        // SCAM Hybrid availability: its rows need the hybrid route (the default) and a plain Bayer route; then the stages its
        // route skips are inactive and SCAM HDR's own rows are inactive too.
        Map<String,Object> h=new HashMap<>();
        h.put("pref_merge_route","scamhdr");active(h,"pref_scam_hybrid_cdm"); // P129: SHDR is the only merge
        h.remove("pref_merge_route");
        active(h,"pref_scam_hybrid_cdm");active(h,"pref_scam_hybrid_sabre61");active(h,"pref_scam_hybrid_highlight_recovery");
        // P118: the ARK sharpening is the only one, its rows are always active
        active(h,"pref_scam_hybrid_ark_sharp_gain");
        // the removed legacy switches (RAW MFSR, standalone remosaic) no longer take the hybrid off
        h.put("pref_raw_mfsr_enabled_key",true);h.put("pref_remosaic_enabled_key",true);active(h,"pref_scam_hybrid_cdm");
        // SCAM Hybrid (own section): pref_scam_hybrid_* bounds; copies of SCAM HDR knobs keep the original bounds; number lists.
        eq(SettingsNumericRules.value("pref_scam_hybrid_sabre61","7",2),2);
        eq(SettingsNumericRules.value("pref_scam_hybrid_highlight_recovery","150",100),100);
        if(SettingsNumericRules.bounds("pref_scamhdr_hybrid_cdm")!=null)throw new AssertionError("legacy hybrid key still bounded");
        eq(SettingsNumericRules.value("pref_scam_hybrid_cdm","9",0.07),2);
        eq(SettingsNumericRules.value("pref_scam_hybrid_bento_factor","32",8),16);
        eq(SettingsNumericRules.value("pref_scam_hybrid_zsl_frames","99",20),44);
        eq(SettingsNumericRules.value("pref_scam_hybrid_zsl_frames","7.6",20),8);
        if(SettingsNumericRules.error("pref_scam_hybrid_zsl_frames","3")==null)throw new AssertionError("hybrid N below 4 accepted");
        eq(SettingsNumericRules.value("pref_scam_hybrid_noise_photon","0",1),.25);
        // P10: the SCAMERA tone keys (headroom, AgX, Exposure Fusion) and their hybrid copies are gone and unbounded
        if(SettingsNumericRules.bounds("pref_scamroute_gamma")!=null||SettingsNumericRules.bounds("pref_scam_hybrid_hdr_shadows")!=null
                ||SettingsNumericRules.bounds("pref_scam_hybrid_fusion_dark_ev")!=null)throw new AssertionError("removed tone key still bounded");
        // P129: a stored SCAM HDR route takes nothing off: SHDR is the only merge
        Map<String,Object> s=new HashMap<>();s.put("pref_merge_route","scamhdr");
        active(s,"pref_scam_hybrid_ark_ae_target");active(s,"pref_scam_hybrid_ark_sharp_gain");active(s,"pref_scam_hybrid_cdm");
        if(SettingsNumericRules.bounds("pref_scam_hybrid_agx_desat")!=null||SettingsNumericRules.bounds("pref_scam_hybrid_enabled")!=null)
            throw new AssertionError("unbounded hybrid copies got bounds");
        // P62 Mochi (GCam 11 PhotometricMerge): 0 off / 1 auto / 2 force
        eq(SettingsNumericRules.value("pref_scam_hybrid_mochi","1",0),1);
        eq(SettingsNumericRules.value("pref_scam_hybrid_mochi","5",0),2);
        eq(SettingsNumericRules.value("pref_scam_hybrid_mochi","-1",0),0);
        // P29 / P34 / P35 native mosaic merge (the default for Quad and Tetra): its rows need “Native mosaic”, the fill threshold the
        // ArkCam fill; the split's edge kernel is live only when Quad or Tetra (“Tetra path” 0) merges on the split; worker key bounds
        Map<String,Object> mo=new HashMap<>();
        String[] nativeRows={"window","window_full","kernel_scale","native_edge_scale","kernel_g","kernel_rb","chroma_fill","tetra",
                "native_flat_scale","native_clamp"};
        active(mo,"pref_scam_hybrid_mosaic_path");active(mo,"pref_scam_hybrid_mosaic_frames");inactive(mo,"pref_scam_hybrid_mosaic_edge_scale");
        for(String k:nativeRows)active(mo,"pref_scam_hybrid_mosaic_"+k);
        inactive(mo,"pref_scam_hybrid_mosaic_fill_support");
        mo.put("pref_scam_hybrid_mosaic_path","0");
        for(String k:nativeRows)inactive(mo,"pref_scam_hybrid_mosaic_"+k);
        inactive(mo,"pref_scam_hybrid_mosaic_fill_support");active(mo,"pref_scam_hybrid_mosaic_edge_scale");active(mo,"pref_scam_hybrid_mosaic_frames");
        mo.put("pref_scam_hybrid_mosaic_path","1");
        for(String k:nativeRows)active(mo,"pref_scam_hybrid_mosaic_"+k);
        inactive(mo,"pref_scam_hybrid_mosaic_edge_scale");
        mo.put("pref_scam_hybrid_mosaic_tetra","0");active(mo,"pref_scam_hybrid_mosaic_edge_scale");
        mo.put("pref_scam_hybrid_mosaic_tetra","2");inactive(mo,"pref_scam_hybrid_mosaic_edge_scale");
        mo.put("pref_scam_hybrid_mosaic_tetra","1");inactive(mo,"pref_scam_hybrid_mosaic_edge_scale");
        mo.put("pref_scam_hybrid_mosaic_chroma_fill","1");active(mo,"pref_scam_hybrid_mosaic_fill_support");
        mo.put("pref_merge_route","scamhdr");active(mo,"pref_scam_hybrid_mosaic_path");
        eq(SettingsNumericRules.value("pref_scam_hybrid_mosaic_path","3",0),1);
        eq(SettingsNumericRules.value("pref_scam_hybrid_mosaic_window","9",3),4);
        eq(SettingsNumericRules.value("pref_scam_hybrid_mosaic_window","2.6",3),3);
        eq(SettingsNumericRules.value("pref_scam_hybrid_mosaic_kernel_rb","0.1",0.85),0.5);
        eq(SettingsNumericRules.value("pref_scam_hybrid_mosaic_native_edge_scale","2",0.4),1);
        eq(SettingsNumericRules.value("pref_scam_hybrid_mosaic_tetra","0",2),0); // 0 = Tetra on the split (1 = T1, the default)
        eq(SettingsNumericRules.value("pref_scam_hybrid_mosaic_tetra","3",0),2);
        eq(SettingsNumericRules.value("pref_scam_hybrid_mosaic_tetra","-1",2),0);
        eq(SettingsNumericRules.value("pref_scam_hybrid_mosaic_native_flat_scale","9",2.4),4);
        eq(SettingsNumericRules.value("pref_scam_hybrid_mosaic_native_flat_scale","0.5",2.4),1);
        eq(SettingsNumericRules.value("pref_scam_hybrid_mosaic_native_clamp","1.6",2),2);
        eq(SettingsNumericRules.value("pref_scam_hybrid_mosaic_native_clamp","5",2),2);
        eq(SettingsNumericRules.value("pref_scam_hybrid_mosaic_native_night_kernel_scale","0.1",1),0.25);
        eq(SettingsNumericRules.value("pref_scam_hybrid_mosaic_native_night_edge_scale","3",0.6),1);
        eq(SettingsNumericRules.value("pref_scam_hybrid_mosaic_tetra_night_kernel_scale","0.1",0.7),0.25);
        eq(SettingsNumericRules.value("pref_scam_hybrid_mosaic_tetra_night_edge_scale","3",0.45),1);
        eq(SettingsNumericRules.value("pref_scam_hybrid_mosaic_tetra_night_flat_scale","9",1.667),4);
        if(SettingsNumericRules.error("pref_scam_hybrid_mosaic_fill_support","1.5")==null)throw new AssertionError("fill support above 1 accepted");
        if(SettingsNumericRules.error("pref_scam_hybrid_mosaic_kernel_scale","0.1")==null)throw new AssertionError("kernel scale below 0.25 accepted");
        float[] list=SettingsNumericRules.listValue("pref_scam_hybrid_x","1; 2.5 3",new float[]{0,0,0});
        eq(list.length,3);eq(list[0],1);eq(list[1],2.5);eq(list[2],3);
        if(SettingsNumericRules.listValue("pref_scam_hybrid_x","1,NaN,3",new float[]{0,0,0})[0]!=0)throw new AssertionError("NaN list accepted");
        if(SettingsNumericRules.listValue("pref_scam_hybrid_x","1,2",new float[]{0,0,0}).length!=3)throw new AssertionError("short list accepted");
        if(SettingsNumericRules.listValue("pref_scam_hybrid_x","",null)!=null)throw new AssertionError("empty list");
        // «Формат фото»: the rows of the formats not chosen are hidden; Ultra HDR / JPEG quality need a JPEG in the shot; quality bounds
        Map<String,Object> f=new HashMap<>();
        String[] avifRows={"pref_avif_quality","pref_avif_lossless","pref_avif_depth","pref_avif_chroma","pref_avif_speed"};
        for(String k:new String[]{"pref_heic_quality","pref_heic_10bit","pref_webp_quality","pref_webp_lossless","pref_photo_also_jpeg"})
            if(!new SettingsAvailability(f).hidden(k))throw new AssertionError("JPEG default shows "+k);
        for(String k:avifRows)if(!new SettingsAvailability(f).hidden(k))throw new AssertionError("JPEG default shows "+k);
        if(new SettingsAvailability(f).hidden("pref_photo_format")||new SettingsAvailability(f).hidden("pref_jpeg_quality"))throw new AssertionError("format rows hidden");
        active(f,"pref_ultrahdr_key");active(f,"pref_jpeg_quality");active(f,"pref_photo_format");
        f.put("pref_photo_format","heic");
        if(new SettingsAvailability(f).hidden("pref_heic_quality")||new SettingsAvailability(f).hidden("pref_photo_also_jpeg"))throw new AssertionError("HEIC rows hidden");
        if(!new SettingsAvailability(f).hidden("pref_webp_quality")||!new SettingsAvailability(f).hidden("pref_webp_lossless"))throw new AssertionError("WebP rows shown for HEIC");
        inactive(f,"pref_ultrahdr_key");inactive(f,"pref_jpeg_quality");active(f,"pref_heic_quality");
        // «HEIC 10 бит»: shown with HEIC, active unless the phone reports a reason (Android 13, Main10 + P010 encoder)
        if(new SettingsAvailability(f).hidden("pref_heic_10bit"))throw new AssertionError("10-bit HEIC row hidden for HEIC");
        active(f,"pref_heic_10bit");
        if(new SettingsAvailability(f).heic10Unavailable(null).reason("pref_heic_10bit")!=null)throw new AssertionError("10-bit HEIC inactive on a capable phone");
        if(!"нет".equals(new SettingsAvailability(f).heic10Unavailable("нет").reason("pref_heic_10bit")))throw new AssertionError("10-bit HEIC reason lost");
        if(new SettingsAvailability(f).heic10Unavailable("нет").reason("pref_heic_quality")!=null)throw new AssertionError("10-bit reason on HEIC quality");
        f.put("pref_photo_also_jpeg",true);active(f,"pref_ultrahdr_key");active(f,"pref_jpeg_quality");
        f.put("pref_photo_format","webp");f.put("pref_photo_also_jpeg",false);
        if(new SettingsAvailability(f).hidden("pref_webp_quality")||new SettingsAvailability(f).hidden("pref_webp_lossless")||!new SettingsAvailability(f).hidden("pref_heic_quality")
                ||!new SettingsAvailability(f).hidden("pref_heic_10bit"))
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
        if(!new SettingsAvailability(f).hidden("pref_webp_quality")||!new SettingsAvailability(f).hidden("pref_heic_quality")
                ||!new SettingsAvailability(f).hidden("pref_heic_10bit")||new SettingsAvailability(f).hidden("pref_photo_also_jpeg"))
            throw new AssertionError("AVIF rows");
        active(f,"pref_ultrahdr_key");inactive(f,"pref_jpeg_quality"); // P266: AVIF holds the HDR photo's map
        f.put("pref_avif_lossless",true);
        inactive(f,"pref_avif_quality");inactive(f,"pref_avif_depth");inactive(f,"pref_avif_chroma");active(f,"pref_avif_speed");active(f,"pref_avif_lossless");
        f.put("pref_photo_also_jpeg",true);active(f,"pref_ultrahdr_key");
        eq(SettingsNumericRules.value("pref_avif_quality","0",90),1);eq(SettingsNumericRules.value("pref_avif_quality","101",90),100);
        eq(SettingsNumericRules.value("pref_avif_speed","12",6),10);eq(SettingsNumericRules.value("pref_avif_speed","x",6),6);
        eq(SettingsNumericRules.value("pref_heic_quality","0",90),1);eq(SettingsNumericRules.value("pref_webp_quality","150",90),100);
        eq(SettingsNumericRules.value("pref_webp_quality","NaN",90),90);
        if(SettingsNumericRules.error("pref_heic_quality","50.5")==null)throw new AssertionError("fractional HEIC quality accepted");
        // P46 «Цветовое пространство» serves every format; P266 «HDR-фото» (one switch, the Ultra HDR key) is always shown: active
        // with a JPEG, a 10-bit HEIC or an AVIF, not with WebP alone, and not below Android 13 for HEIC / AVIF (device fact).
        Map<String,Object> c=new HashMap<>();
        for(String fmt:new String[]{"jpeg","heic","webp","avif"}){
            c.put("pref_photo_format",fmt);
            if(new SettingsAvailability(c).hidden("pref_photo_color_space"))throw new AssertionError("colour space hidden for "+fmt);
            active(c,"pref_photo_color_space");
            if(new SettingsAvailability(c).hidden("pref_ultrahdr_key"))throw new AssertionError("HDR row hidden for "+fmt);
        }
        c.put("pref_photo_format","jpeg");active(c,"pref_ultrahdr_key");
        c.put("pref_photo_format","webp");inactive(c,"pref_ultrahdr_key");
        c.put("pref_photo_format","heic");inactive(c,"pref_ultrahdr_key");
        c.put("pref_heic_10bit",true);active(c,"pref_ultrahdr_key");
        if(!new SettingsAvailability(c).heic10Unavailable("нет кодека").reason("pref_ultrahdr_key").endsWith("нет кодека"))throw new AssertionError("HDR reason without the 10-bit HEIC reason");
        if(!"старый".equals(new SettingsAvailability(c).hdrUnavailable("старый").reason("pref_ultrahdr_key")))throw new AssertionError("HDR device reason lost");
        if(new SettingsAvailability(c).hdrUnavailable("старый").reason("pref_heic_10bit")!=null)throw new AssertionError("HDR reason on 10-bit HEIC");
        c.put("pref_photo_format","avif");c.remove("pref_heic_10bit");active(c,"pref_ultrahdr_key");
        c.put("pref_avif_depth","8");active(c,"pref_ultrahdr_key"); // P125: the gain map fits an 8-bit AVIF
        c.put("pref_avif_lossless",true);active(c,"pref_ultrahdr_key");
        c.put("pref_avif_lossless",false);c.put("pref_avif_depth","12");active(c,"pref_ultrahdr_key");
        // Settings audit H3: rows that do nothing in the current configuration explain why.
        Map<String,Object> sh=new HashMap<>(); // P118: only the ARK sharpening stays, its rows are always active
        active(sh,"pref_scam_hybrid_ark_sharp_gain");active(sh,"pref_scam_hybrid_ark_sharp_rl1_kernel");active(sh,"pref_scam_hybrid_ark_sharp_note");
        sh.put("pref_scam_hybrid_sharp_mode","off"); // a stored value of the removed mode list changes nothing
        active(sh,"pref_scam_hybrid_ark_sharp_usm_amount");active(sh,"pref_scam_hybrid_ark_sharp_gain");
        Map<String,Object> dn=new HashMap<>(); // denoise engine "gcam" (default)
        active(dn,"pref_scam_hybrid_dn_luma_mult");active(dn,"pref_scam_hybrid_dn_luma_t1_snr");active(dn,"pref_scam_hybrid_dn_engine");
        inactive(dn,"pref_scam_hybrid_post_luma");inactive(dn,"pref_scam_hybrid_post_chroma");
        dn.put("pref_scam_hybrid_dn_engine","nlm");
        active(dn,"pref_scam_hybrid_post_luma");active(dn,"pref_scam_hybrid_post_chroma");active(dn,"pref_scam_hybrid_dn_engine");
        inactive(dn,"pref_scam_hybrid_dn_luma_mult");inactive(dn,"pref_scam_hybrid_dn_dark_fade");active(dn,"pref_scam_hybrid_despeckle");
        Map<String,Object> out=new HashMap<>(); // output "sensor" (default)
        inactive(out,"pref_scam_hybrid_downsampler");active(out,"pref_scam_hybrid_output");
        // the 2x colour share also acts on 200 MP shots (their 2x2 means): inactive at the sensor size only without them
        active(out,"pref_scam_hybrid_dn_chroma_2x_keep");
        if(new SettingsAvailability(out).fullResSizes(true,false).reason("pref_scam_hybrid_dn_chroma_2x_keep")==null)throw new AssertionError("2x colour share live without 200 MP");
        out.put("pref_scam_hybrid_output","2x");inactive(out,"pref_scam_hybrid_downsampler");active(out,"pref_scam_hybrid_dn_chroma_2x_keep");
        for(String mp:new String[]{"12","16","20"}){out.put("pref_scam_hybrid_output",mp);active(out,"pref_scam_hybrid_downsampler");active(out,"pref_scam_hybrid_dn_chroma_2x_keep");}
        Map<String,Object> scam=new HashMap<>();
        // «Цветовой метод»: inactive where a tuned / ISP matrix replaces it
        active(scam,"pref_color_method_key");
        if(!"matrix".equals(new SettingsAvailability(scam).colorMethodOverride("matrix").reason("pref_color_method_key")))throw new AssertionError("colour method reason");
        // The diagnostics page is never hidden (P175: the neural remosaic's 8 Elite rule went with its page and its check)
        if(new SettingsAvailability(scam).hidden("scam_diagnostics_screen"))throw new AssertionError("diagnostics hidden");
        // P128: the «Движение» rows act only in that mode; the OPPO saturation only there. P310 settings cleanup: the lamp flicker
        // acts without the long frames too (the native FULLRES AE rounds its shutter to it)
        Map<String,Object> m=new HashMap<>();
        inactive(m,"pref_scam_hybrid_mm_shutter");m.put("pref_motion_mode",true);active(m,"pref_scam_hybrid_mm_shutter");
        active(m,"pref_antibanding_hz_key");m.put("pref_scam_hybrid_shasta",false);active(m,"pref_antibanding_hz_key");
        // P310 settings cleanup: the night / day boundary acts at every kernel setting; the ARK denoise rows follow «Шумоподавление»;
        // the RAW burst row yields to the lab's copy; a DCP profile replaces the colour method's matrix
        Map<String,Object> cu=new HashMap<>();
        cu.put("pref_scam_hybrid_sabre61","1");active(cu,"pref_scam_hybrid_s61_max_key");inactive(cu,"pref_scam_hybrid_s61_min_motion");
        // settings audit 2026-10-21: merge rows the other settings leave out (F6, the colour-difference strength, the new kernel,
        // «Движение»)
        Map<String,Object> au=new HashMap<>();au.put("pref_scam_hybrid_sabre61","2");active(au,"pref_scam_hybrid_s61_min_motion");
        au.put("pref_scam_hybrid_local_align","0");inactive(au,"pref_scam_hybrid_s61_min_motion");inactive(au,"pref_scam_hybrid_boost_threshold");
        active(au,"pref_scam_hybrid_chroma_diff_clamp");au.put("pref_scam_hybrid_chroma_diff","0");inactive(au,"pref_scam_hybrid_chroma_diff_clamp");
        active(au,"pref_scam_hybrid_mosaic_native_flat_scale");active(au,"pref_scam_hybrid_mosaic_native_edge_scale");
        au.put("pref_scam_hybrid_sabre61","0");inactive(au,"pref_scam_hybrid_mosaic_native_flat_scale");inactive(au,"pref_scam_hybrid_mosaic_native_edge_scale");
        au.put("pref_scam_hybrid_mosaic_path","0");inactive(au,"pref_scam_hybrid_mosaic_edge_scale");
        au.put("pref_scam_hybrid_sabre61","1");active(au,"pref_scam_hybrid_mosaic_edge_scale");
        Map<String,Object> mm=new HashMap<>();active(mm,"pref_scam_hybrid_boost_value");active(mm,"pref_scam_hybrid_widen_below");
        mm.put("pref_motion_mode",true);inactive(mm,"pref_scam_hybrid_boost_value");inactive(mm,"pref_scam_hybrid_motion_threshold");
        inactive(mm,"pref_scam_hybrid_motion_boost");inactive(mm,"pref_scam_hybrid_widen_below");active(mm,"pref_scam_hybrid_boost_threshold");
        mm.put("pref_scam_hybrid_mm_motion_boost",false);inactive(mm,"pref_scam_hybrid_boost_threshold");
        // settings audit 2026-10-21 («Кадры светов и теней»): the frames' rows follow the switch in effect («Движение»: the mode's,
        // which replaces the photo rows of the ring's count and of the switches); the kernel widening needs a weight above 10; the
        // motion limit of the long frames «Всегда»; the highlight kernel the native mosaic merge; the edge colour from 0.5
        Map<String,Object> fr=new HashMap<>();active(fr,"pref_scam_hybrid_bento_frames");active(fr,"pref_scam_hybrid_zsl_frames");
        fr.put("pref_scam_hybrid_bento","0");inactive(fr,"pref_scam_hybrid_bento_frames");inactive(fr,"pref_scam_hybrid_bento_ramp");
        fr.put("pref_motion_mode",true);active(fr,"pref_scam_hybrid_bento_frames");inactive(fr,"pref_scam_hybrid_zsl_frames");
        inactive(fr,"pref_scam_hybrid_bento");inactive(fr,"pref_scam_hybrid_shasta");inactive(fr,"pref_scam_hybrid_shasta_sharpness");
        Map<String,Object> lu=new HashMap<>();inactive(lu,"pref_scam_hybrid_lut_sigma");active(lu,"pref_scam_hybrid_shasta_motion_max");
        lu.put("pref_scam_hybrid_shasta_ev","2");active(lu,"pref_scam_hybrid_lut_sigma");
        lu.put("pref_scam_hybrid_shasta_mode","1");inactive(lu,"pref_scam_hybrid_shasta_motion_max");
        active(lu,"pref_scam_hybrid_bento_sigma");
        if(new SettingsAvailability(lu).mosaicStreams(false).reason("pref_scam_hybrid_bento_sigma")==null)throw new AssertionError("highlight kernel live without a mosaic");
        eq(SettingsNumericRules.value("pref_scam_hybrid_bento_chroma_sigma","0",2),0.5);
        // settings audit 2026-10-21: post rows the other settings leave out (the recovery, the ARK detail, the strength map, AgX)
        Map<String,Object> po=new HashMap<>();active(po,"pref_scam_hybrid_highlight_band");active(po,"pref_scam_hybrid_ark_clarity");
        active(po,"pref_scam_hybrid_bento_denoise_max");active(po,"pref_scam_hybrid_ark_ccm_sat");
        po.put("pref_scam_hybrid_highlight_recovery","0");inactive(po,"pref_scam_hybrid_highlight_band");inactive(po,"pref_scam_hybrid_highlight_defringe");
        po.put("pref_scam_hybrid_ark_sharp_gain","0");inactive(po,"pref_scam_hybrid_ark_clarity");inactive(po,"pref_scam_hybrid_ark_flat_protect");
        po.put("pref_scam_hybrid_dn_strength_map","uniform");inactive(po,"pref_scam_hybrid_bento_denoise_max");
        po.put("pref_scam_hybrid_ark_aces_on",true);inactive(po,"pref_scam_hybrid_ark_ccm_sat");
        active(cu,"pref_scam_hybrid_ark_bracket_denoise");cu.put("pref_scam_hybrid_denoise",false);
        inactive(cu,"pref_scam_hybrid_ark_bracket_denoise");inactive(cu,"pref_scam_hybrid_ark_chroma_denoise");
        active(cu,"pref_keep_raw_burst");cu.put("pref_scam_lab_keep",true);inactive(cu,"pref_keep_raw_burst");
        active(cu,"pref_color_method_key");cu.put("pref_dcp_profile_key","0123.dcp");inactive(cu,"pref_color_method_key");
        if(!new SettingsAvailability(m).oppoTunedColor(false).hidden("pref_scam_hybrid_ark_ccm_sat"))throw new AssertionError("OPPO saturation shown");
        if(new SettingsAvailability(m).mosaicStreams(false).reason("pref_scam_hybrid_mosaic_path")==null)throw new AssertionError("mosaic rows without a mosaic");
        if(!new SettingsAvailability(scam).xiaomiSmoothZoom(false).hidden("pref_xiaomi_smooth_zoom"))throw new AssertionError("Xiaomi zoom shown");
        if(!new SettingsAvailability(scam).fullResHwRemosaic(false).hidden("pref_fullres_hw_remosaic"))throw new AssertionError("FULLRES hardware remosaic shown");
        if(new SettingsAvailability(scam).hidden("pref_fullres_hw_remosaic"))throw new AssertionError("FULLRES hardware remosaic hidden where it acts");
        System.out.println("Settings model PASS: exact precision, legacy types, finite bounds, mode/algorithm availability, SHDR bounds, native mosaic, no neural remosaic rules, photo format, 10-bit HEIC, AVIF, sharpening / denoise / output / motion / colour method availability");
    }
}

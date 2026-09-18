package com.particlesdevs.photoncamera.settings;

import com.particlesdevs.photoncamera.app.PhotonCamera;

/** Separate namespace: existing SCAMERA NR values are never reinterpreted as RT values. */
public final class RawTherapeeSettings {
    public static final String BACKEND = "pref_rt_denoise_backend";
    public static String text(String key, String fallback) {
        try { return PhotonCamera.getSettingsManagerStatic().getString(SettingsManager.SCOPE_GLOBAL,key,fallback); }
        catch (RuntimeException e) { return fallback; }
    }
    public static boolean original() { return "rt512".equals(text(BACKEND,"legacy")); }
    public static float number(String key,float fallback,float lo,float hi) {
        try {
            float f=Float.parseFloat(text(key,Float.toString(fallback)));
            return Float.isFinite(f)?Math.max(lo,Math.min(hi,f)):fallback;
        } catch(RuntimeException e) { return fallback; }
    }
    public static float[] parameters() {
        return new float[]{
            number("rt512_luma",0,0,100),number("rt512_chroma",15,0,100),number("rt512_detail",0,0,100),
            number("rt512_red",0,-100,100),number("rt512_blue",0,-100,100),number("rt512_gamma",1.7f,1,3),
            number("rt512_space",0,0,1),number("rt512_quality",0,0,1),number("rt512_median",0,0,5),
            number("rt512_kernel",0,0,5),number("rt512_passes",1,1,10),number("rt512_auto",0,0,1),
            number("rt512_gain",1,0,1),number("rt512_exposure",0,-5,5)
        };
    }
    public static double[] curve(String text) {
        if(text==null||text.trim().isEmpty()||"0".equals(text.trim()))return null;
        String[] parts=text.trim().split("[;,\\s]+");
        if(parts.length<9||parts.length>257||(parts.length-1)%4!=0)
            throw new IllegalArgumentException("Нужны тип 1 и минимум две точки x, y, левая, правая касательная");
        double[] p=new double[parts.length];
        for(int i=0;i<p.length;i++) {
            p[i]=Double.parseDouble(parts[i]);
            if(!Double.isFinite(p[i])||p[i]<0||p[i]>1)throw new IllegalArgumentException("Значения кривой: от 0 до 1");
        }
        if(p[0]!=1)throw new IllegalArgumentException("Тип кривой должен быть 1");
        for(int i=5;i<p.length;i+=4)if(p[i]<=p[i-4])throw new IllegalArgumentException("Координаты x должны возрастать");
        return p;
    }
    private RawTherapeeSettings() {}
}

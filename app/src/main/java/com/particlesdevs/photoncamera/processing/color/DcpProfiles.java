package com.particlesdevs.photoncamera.processing.color;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.OpenableColumns;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.processing.render.Converter;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Private, content-addressed imports; the selected filename is part of the module profile. */
public final class DcpProfiles {
    public static final String KEY="pref_dcp_profile_key";
    private static String cachedId;
    private static DcpProfile cached;
    private static File dir(Context c){File f=new File(c.getFilesDir(),"dcp");f.mkdirs();return f;}
    private static SharedPreferences names(Context c){return c.getSharedPreferences("dcp_import_names",0);}
    public static Map<String,String> list(Context c){
        Map<String,String> result=new LinkedHashMap<>();result.put("","Камерная матрица");
        File[] files=dir(c).listFiles((d,n)->n.matches("[0-9a-f]{64}\\.dcp"));
        if(files!=null){Arrays.sort(files);for(File f:files)result.put(f.getName(),names(c).getString(f.getName(),f.getName()));}
        return result;
    }
    public static String importFile(Context c,Uri uri)throws Exception{
        byte[] bytes;
        try(InputStream in=c.getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Файл не открыт");bytes=read(in);}
        DcpProfile profile=DcpProfile.parse(bytes);
        StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format(Locale.ROOT,"%02x",b&255));
        String id=hash+".dcp",name="Профиль DCP";
        try(android.database.Cursor cursor=c.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(cursor!=null&&cursor.moveToFirst())name=cursor.getString(0);
        }
        File target=new File(dir(c),id);
        if(!target.isFile()){
            File temp=File.createTempFile("import-",".tmp",dir(c));
            try {try(FileOutputStream out=new FileOutputStream(temp)){out.write(bytes);out.getFD().sync();}
                if(!temp.renameTo(target))throw new IOException("Не удалось сохранить DCP");
            }finally{temp.delete();}
        }
        names(c).edit().putString(id,name+(profile.hasLookTables?" · только матрицы":"")).apply();
        return id;
    }
    private static byte[] read(InputStream in)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        while((n=in.read(buffer))!=-1){if(out.size()+n>DcpProfile.MAX_BYTES)throw new IOException("DCP больше 16 МБ");out.write(buffer,0,n);}return out.toByteArray();
    }
    public static synchronized float[] activeToXyz(float[] neutral){
        if(PhotonCamera.getSettingsManagerStatic()==null)return null;
        Context c=PhotonCamera.getSettingsManagerStatic().getContext();
        String id=PhotonCamera.getSettingsManagerStatic().getDefaultPreferences().getString(KEY,"");
        if(id.isEmpty())return null;
        if(!id.equals(cachedId)){
            cachedId=id;cached=null;
            if(id.matches("[0-9a-f]{64}\\.dcp"))try(InputStream in=new FileInputStream(new File(dir(c),id))){cached=DcpProfile.parse(read(in));}
            catch(Exception ex){android.util.Log.w("DCP","Profile unavailable: "+id,ex);}
        }
        try{return cached==null?null:cached.cameraToXyz(neutral);}catch(IllegalArgumentException ex){return null;}
    }
    /** GL expects column-major, and RAW preview already applied the supplied WB gains. */
    public static float[] previewMatrix(float[] gains){
        for(float g:gains)if(!Float.isFinite(g)||g<=0)return null;
        float[] xyz=activeToXyz(new float[]{gains[1]/gains[0],1,gains[1]/gains[2]});
        if(xyz==null)return null;
        float[] srgb=DcpProfile.multiply(Converter.sXYZtoSRGB,xyz),gl=new float[9];
        for(int r=0;r<3;r++)for(int c=0;c<3;c++)gl[c*3+r]=srgb[r*3+c]/gains[c];
        return gl;
    }
}

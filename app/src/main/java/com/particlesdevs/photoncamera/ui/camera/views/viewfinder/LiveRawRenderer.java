package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import android.opengl.GLES20;
import android.opengl.GLES30;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.processing.LiveRawFrame;
import com.particlesdevs.photoncamera.processing.LiveRawMeter;
import com.particlesdevs.photoncamera.processing.color.ColourLook;
import com.particlesdevs.photoncamera.processing.color.FilmLut;
import com.particlesdevs.photoncamera.processing.opengl.postpipeline.ArkTone;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;

/** RAW development adapted from libvf_demosaic, using the preview's own GL context.
 * One context owns upload and draw: no cross-context HardwareBuffer race or readback. */
final class LiveRawRenderer {
    private int program, rawTex, shadingTex, lutTex, width, height;
    private void deleteResources(){
        if(program!=0)GLES20.glDeleteProgram(program);
        GLES20.glDeleteTextures(4,new int[]{rawTex,shadingTex,lutTex,filmLutTex},0);
        program=rawTex=shadingTex=lutTex=filmLutTex=width=height=0;uploadedVersion=-1;uniforms.clear();lutShown=null;
    }
    // P134: the photo's «Цвет» look (ColourLook: the white-balance matrix, monochrome, skin, vibrance by brightness, the
    // mixer, the grading) and the film LUT in the RAW viewfinder, read from the settings twice a second; the LUT file is
    // parsed off the GL thread. The tone stays the viewfinder's own (ArkCore), so the look shows on its rendering.
    private static final java.util.concurrent.ExecutorService LUT_LOADER=java.util.concurrent.Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"vf-film-lut");t.setDaemon(true);return t;});
    private long lookAt=Long.MIN_VALUE;
    private float[] lookWhite,lookSkin,lookVib,lookMix,lookMono,lookGradeA,lookGradeB;
    private boolean lookOn,lookMixOn;
    private float lutStrength;
    private volatile String lutWanted="";
    private volatile FilmLut lutReady;
    private FilmLut lutShown;
    private int filmLutTex;
    private void refreshLook(){
        long now=android.os.SystemClock.elapsedRealtime();
        if(now-lookAt<500)return;
        lookAt=now;
        try{
            ColourLook c=ArkTone.colourLook();
            lookWhite=c.whiteMatrix(0f);
            lookOn=!c.shaderNeutral();
            lookSkin=c.skinUniform();lookVib=c.vibUniform();lookMix=c.mixUniform();lookMixOn=c.mixerOn();lookMono=c.monoUniform();
            float[][] grade=c.gradeUniforms();lookGradeA=grade[0];lookGradeB=grade[1];
            lutStrength=PreferenceKeys.filmLutStrength();
            String name=lutStrength>0f?PreferenceKeys.filmLut():"";
            if(!name.equals(lutWanted)){
                lutWanted=name;lutReady=null;
                if(!name.isEmpty())LUT_LOADER.execute(()->{
                    FilmLut lut=FilmLut.chosen(ScameraApp.getAppContext(),name);
                    if(name.equals(lutWanted))lutReady=lut;
                });
            }
        }catch(RuntimeException e){
            lookWhite=null;lookOn=false;lutStrength=0f;
        }
    }
    /** The camera's colour transform (column-major, as uploaded) with the look's white matrix (row-major, linear sRGB) after it. */
    static float[] lookTransform(float[] white,float[] ccm){
        float[] out=new float[9];
        for(int r=0;r<3;r++)for(int c=0;c<3;c++){
            float s=0f;for(int k=0;k<3;k++)s+=white[r*3+k]*ccm[c*3+k];
            out[c*3+r]=s;
        }
        return out;
    }
    private void setLook(){
        FilmLut lut=lutReady;
        if(lut!=lutShown){
            if(filmLutTex!=0)GLES20.glDeleteTextures(1,new int[]{filmLutTex},0);
            filmLutTex=lut!=null?lut.upload():0;lutShown=lut;
        }
        boolean lutOn=filmLutTex!=0&&lutStrength>0f;
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4);GLES30.glBindTexture(GLES30.GL_TEXTURE_3D,lutOn?filmLutTex:lutTex);
        integer("u_lut_enabled",lutOn?1:0);
        if(lutOn){
            number("u_lut_strength",lutStrength);number("u_lut_size",lut.size);
            GLES20.glUniform3f(loc("u_lut_min"),lut.domainMin[0],lut.domainMin[1],lut.domainMin[2]);
            GLES20.glUniform3f(loc("u_lut_scale"),1f/(lut.domainMax[0]-lut.domainMin[0]),1f/(lut.domainMax[1]-lut.domainMin[1]),
                    1f/(lut.domainMax[2]-lut.domainMin[2]));
        }
        integer("u_look_on",lookOn?1:0);
        if(!lookOn)return;
        GLES20.glUniform3f(loc("u_look_skin"),lookSkin[0],lookSkin[1],lookSkin[2]);
        GLES20.glUniform2f(loc("u_look_vib"),lookVib[0],lookVib[1]);
        integer("u_look_mix_on",lookMixOn?1:0);
        GLES20.glUniform1fv(loc("u_look_mix"),24,lookMix,0);
        GLES20.glUniform3f(loc("u_look_mono"),lookMono[0],lookMono[1],lookMono[2]);
        GLES20.glUniform4f(loc("u_look_grade_a"),lookGradeA[0],lookGradeA[1],lookGradeA[2],lookGradeA[3]);
        GLES20.glUniform4f(loc("u_look_grade_b"),lookGradeB[0],lookGradeB[1],lookGradeB[2],lookGradeB[3]);
    }
    private int uploadedVersion=-1, frameSession=-1, draws;
    private boolean failed;
    private final int[] viewport=new int[4];
    private FloatBuffer shadingBuffer;
    private final HashMap<String,Integer> uniforms=new HashMap<>();
    private final LiveRawMeter meter=new LiveRawMeter();
    private int sceneFrames;
    /**
     * P212h (A6 = M11): MainRenderer returns before its LiveSceneMeter while the developed RAW is drawn, so in a RAW-viewfinder session
     * (FULLRES) the software shutter / ISO priority never had a brightness and its other value never moved. While the priority wants
     * it, every 8th developed frame gives its median (before any display gain) as SceneLuma, marked as the RAW source (ParamController
     * learns the target again when the source changes). Not where the frame's own AE meters the other value (the OPPO native
     * session: NativeRawExposure.setManual); scam_dev.txt "vf_raw_scene_luma 0": as before.
     */
    private void sceneLuma(LiveRawFrame.Frame f) {
        if(!f.sceneLuma || !com.particlesdevs.photoncamera.manual.SceneLuma.wanted() || (++sceneFrames&7)!=0)return;
        final double luma=meter.sceneLuma();
        if(Double.isFinite(luma))com.particlesdevs.photoncamera.manual.SceneLuma.publishRaw(luma);
    }
    void onContextCreated() {
        program=rawTex=shadingTex=lutTex=filmLutTex=width=height=0;lutShown=null;
        uploadedVersion=frameSession=-1; failed=false; uniforms.clear(); meter.reset();
    }
    private int loc(String name) {return uniforms.computeIfAbsent(name,n->GLES20.glGetUniformLocation(program,n));}
    private void integer(String n,int v){GLES20.glUniform1i(loc(n),v);}
    private void number(String n,float v){GLES20.glUniform1f(loc(n),v);}
    boolean draw(FloatBuffer vertices,FloatBuffer coords,float[] rotation,boolean mirror,int peaking) {
        LiveRawFrame.Frame f=LiveRawFrame.acquire();
        if(f==null || System.nanoTime()-f.publishedNanos>500_000_000L)return false;
        if(frameSession!=f.session){frameSession=f.session;failed=false;uploadedVersion=-1;meter.reset();}
        if(failed)return false;
        try {
            if(program==0 && !init())return false;
            refreshLook();
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,rawTex);
            if(uploadedVersion!=f.version) {
                f.buffer.position(0);
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT,2);
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH,f.rowStride/2);
                if(width!=f.width || height!=f.height) {
                    GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES30.GL_R16UI,f.width,f.height,0,GLES30.GL_RED_INTEGER,GLES20.GL_UNSIGNED_SHORT,f.buffer);
                    width=f.width;height=f.height;
                } else GLES30.glTexSubImage2D(GLES20.GL_TEXTURE_2D,0,0,0,f.width,f.height,GLES30.GL_RED_INTEGER,GLES20.GL_UNSIGNED_SHORT,f.buffer);
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH,0);GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT,4);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE3);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,shadingTex);
                if(shadingBuffer==null || shadingBuffer.capacity()<f.shading.length)
                    shadingBuffer=ByteBuffer.allocateDirect(f.shading.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
                shadingBuffer.clear();shadingBuffer.put(f.shading).flip();
                GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES30.GL_RGB16F,f.shadingWidth,f.shadingHeight,0,GLES20.GL_RGB,GLES20.GL_FLOAT,shadingBuffer);
                if(!check("upload"))return false;
                meter.update(f);
                sceneLuma(f);
                uploadedVersion=f.version;
            }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,shadingTex);
            setLook();
            integer("sTexture16",2);integer("u_lsc_map",3);integer("u_lut_tex",4);
            integer("width",f.width);integer("height",f.height);integer("bayer_pattern",f.cfaPattern);
            integer("cfa_mode",f.cfaBlock==1?1:0);integer("cfa_block_size",f.cfaBlock);
            int period=f.cfaBlock*2;
            int left=(int)(f.crop[0]*f.width)/period*period,top=(int)(f.crop[1]*f.height)/period*period;
            int cw=Math.min(f.width-left,(int)(f.crop[2]*f.width))/period*period;
            int ch=Math.min(f.height-top,(int)(f.crop[3]*f.height))/period*period;
            // RAW is in sensor coordinates; match the visible preview aspect without stretching.
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT,viewport,0);
            if(viewport[2]>0 && viewport[3]>0) {
                boolean quarterTurn=Math.abs(rotation[1])>Math.abs(rotation[0]);
                float aspect=quarterTurn ? viewport[2]/(float)viewport[3] : viewport[3]/(float)viewport[2];
                if(cw/(float)Math.max(ch,1)>aspect) {
                    int fitted=Math.max(period,(int)(ch*aspect)/period*period);
                    left+=(cw-fitted)/2/period*period;cw=fitted;
                } else {
                    int fitted=Math.max(period,(int)(cw/aspect)/period*period);
                    top+=(ch-fitted)/2/period*period;ch=fitted;
                }
            }
            integer("crop_left",left);integer("crop_top",top);
            integer("crop_width",Math.max(period,cw));integer("crop_height",Math.max(period,ch));
            int[][] orders={{0,1,2,3},{1,0,3,2},{2,3,0,1},{3,2,1,0}};
            int[] o=orders[f.cfaPattern];
            GLES20.glUniform4f(loc("u_black_level"),f.blackLevel[o[0]],f.blackLevel[o[1]],f.blackLevel[o[2]],f.blackLevel[o[3]]);
            number("u_white_level",f.whiteLevel);number("r_gain",f.wbGains[0]);number("g_gain",f.wbGains[1]);number("b_gain",f.wbGains[2]);
            float[] white=lookWhite;
            GLES20.glUniformMatrix3fv(loc("color_transform"),1,false,white!=null?lookTransform(white,f.colorTransform):f.colorTransform,0);
            number("u_lsc_intensity",f.shadingWidth>1?1:0);
            number("u_auto_exposure",meter.exposure);number("u_contrast_boost",meter.contrast);
            GLES20.glUniform4fv(loc("u_exp_mults"),1,meter.multipliers,0);
            integer("mirror",mirror?1:0);integer("peaking_enabled",peaking);
            GLES20.glUniform3f(loc("peaking_color"),1,.8f,0);
            GLES20.glUniformMatrix4fv(loc("texRotate"),1,false,rotation,0);
            vertices.position(0);coords.position(0);
            GLES20.glEnableVertexAttribArray(0);GLES20.glEnableVertexAttribArray(1);
            GLES20.glVertexAttribPointer(0,2,GLES20.GL_FLOAT,false,8,vertices);
            GLES20.glVertexAttribPointer(1,2,GLES20.GL_FLOAT,false,8,coords);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
            if(!check("draw"))return false;
            if(draws++%120==0)Log.d("LiveRawRenderer","RAW developed session="+f.session+" frame="+f.version+" size="+f.width+"x"+f.height+" CFA="+f.cfaPattern+" block="+f.cfaBlock+" AE="+meter.exposure);
            return true;
        } catch(RuntimeException e) {
            failed=true;Log.e("LiveRawRenderer","RAW preview fallback: "+e);return false;
        } finally {
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH,0);GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT,4);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        }
    }
    private boolean check(String step) {
        int error=GLES20.glGetError();
        if(error==GLES20.GL_NO_ERROR)return true;
        failed=true;Log.e("LiveRawRenderer",step+" GL error="+error+"; using ISP preview");return false;
    }
    private int texture(int unit,int target,int filter) {
        int[] t=new int[1];GLES20.glGenTextures(1,t,0);GLES20.glActiveTexture(unit);GLES20.glBindTexture(target,t[0]);
        GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_MIN_FILTER,filter);GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_MAG_FILTER,filter);
        GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);return t[0];
    }
    private boolean init() {
        int v=compile(GLES20.GL_VERTEX_SHADER,ScameraApp.getAssetLoader().getString("shaders/preview/rawdevelop_vs.glsl"));
        int f=compile(GLES20.GL_FRAGMENT_SHADER,ScameraApp.getAssetLoader().getString("shaders/preview/rawdevelop_fs.glsl"));
        if(v==0 || f==0){if(v!=0)GLES20.glDeleteShader(v);if(f!=0)GLES20.glDeleteShader(f);failed=true;return false;}
        program=GLES20.glCreateProgram();GLES20.glAttachShader(program,v);GLES20.glAttachShader(program,f);GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(v);GLES20.glDeleteShader(f);int[] ok=new int[1];GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,ok,0);
        if(ok[0]==0){Log.e("LiveRawRenderer",GLES20.glGetProgramInfoLog(program));GLES20.glDeleteProgram(program);program=0;failed=true;return false;}
        uniforms.clear();GLES20.glUseProgram(program);
        rawTex=texture(GLES20.GL_TEXTURE2,GLES20.GL_TEXTURE_2D,GLES20.GL_NEAREST);
        shadingTex=texture(GLES20.GL_TEXTURE3,GLES20.GL_TEXTURE_2D,GLES20.GL_LINEAR);
        lutTex=texture(GLES20.GL_TEXTURE4,GLES30.GL_TEXTURE_3D,GLES20.GL_LINEAR);
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D,0,GLES30.GL_RGB8,1,1,1,0,GLES20.GL_RGB,GLES20.GL_UNSIGNED_BYTE,ByteBuffer.allocateDirect(4));
        // The look of libvf_demosaic (ArkCore) at its library defaults: 4-layer exposure fusion metered by LiveRawMeter, ACES,
        // OKLab macro contrast and vibrance, gamma 2.2 with the film toe. Optional LUT/LDC are disabled until supplied with a profile.
        integer("u_ef_enabled",1);integer("u_tonemap_op",0);integer("u_lut_enabled",0);integer("u_ldc_enabled",0);
        number("u_weight_center",.5f);number("u_blend_smoothness",.15f);number("u_macro_contrast",1.1f);
        number("u_gamma",2.2f);number("u_film_toe",.05f);number("u_shadow_vibrance",1.1f);number("u_highlight_vibrance",1);
        number("u_aces_toe",.01f);number("u_aces_pre_gain",1.35f);number("u_aces_a_coeff",2.7f);number("u_aces_d_coeff",.59f);
        GLES20.glUniform4f(loc("u_layer_weights"),.7f,.8f,1,.5f);
        return check("initialize");
    }
    private static int compile(int type,String source) {
        if(source==null)return 0;
        int s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,source);GLES20.glCompileShader(s);
        int[] ok=new int[1];GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);
        if(ok[0]!=0)return s;
        Log.e("LiveRawRenderer",GLES20.glGetShaderInfoLog(s));GLES20.glDeleteShader(s);return 0;
    }
}

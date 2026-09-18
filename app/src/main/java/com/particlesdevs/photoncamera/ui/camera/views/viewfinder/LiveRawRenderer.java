package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import android.opengl.GLES20;
import android.opengl.GLES30;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.processing.LiveRawFrame;
import com.particlesdevs.photoncamera.processing.LiveRawMeter;
import com.particlesdevs.photoncamera.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;

/** RAW development adapted from libvf_demosaic, using the preview's own GL context.
 * One context owns upload and draw: no cross-context HardwareBuffer race or readback. */
final class LiveRawRenderer {
    private int program, rawTex, shadingTex, lutTex, width, height;
    private int uploadedVersion=-1, frameSession=-1, draws;
    private boolean failed;
    private final int[] viewport=new int[4];
    private FloatBuffer shadingBuffer;
    private final HashMap<String,Integer> uniforms=new HashMap<>();
    private final LiveRawMeter meter=new LiveRawMeter();
    void onContextCreated() {
        program=rawTex=shadingTex=lutTex=width=height=0;
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
                meter.update(f); uploadedVersion=f.version;
            }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,shadingTex);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE4);GLES30.glBindTexture(GLES30.GL_TEXTURE_3D,lutTex);
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
            GLES20.glUniformMatrix3fv(loc("color_transform"),1,false,f.colorTransform,0);
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
        int v=compile(GLES20.GL_VERTEX_SHADER,PhotonCamera.getAssetLoader().getString("shaders/preview/rawdevelop_vs.glsl"));
        int f=compile(GLES20.GL_FRAGMENT_SHADER,PhotonCamera.getAssetLoader().getString("shaders/preview/rawdevelop_fs.glsl"));
        if(v==0 || f==0){if(v!=0)GLES20.glDeleteShader(v);if(f!=0)GLES20.glDeleteShader(f);failed=true;return false;}
        program=GLES20.glCreateProgram();GLES20.glAttachShader(program,v);GLES20.glAttachShader(program,f);GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(v);GLES20.glDeleteShader(f);int[] ok=new int[1];GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,ok,0);
        if(ok[0]==0){Log.e("LiveRawRenderer",GLES20.glGetProgramInfoLog(program));GLES20.glDeleteProgram(program);program=0;failed=true;return false;}
        uniforms.clear();GLES20.glUseProgram(program);
        rawTex=texture(GLES20.GL_TEXTURE2,GLES20.GL_TEXTURE_2D,GLES20.GL_NEAREST);
        shadingTex=texture(GLES20.GL_TEXTURE3,GLES20.GL_TEXTURE_2D,GLES20.GL_LINEAR);
        lutTex=texture(GLES20.GL_TEXTURE4,GLES30.GL_TEXTURE_3D,GLES20.GL_LINEAR);
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D,0,GLES30.GL_RGB8,1,1,1,0,GLES20.GL_RGB,GLES20.GL_UNSIGNED_BYTE,ByteBuffer.allocateDirect(4));
        // Library defaults. Optional LUT/LDC are disabled until supplied with an actual profile.
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

package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import android.opengl.GLES20;
import android.opengl.GLES30;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.processing.LiveRawFrame;
import com.particlesdevs.photoncamera.processing.LiveRawMeter;
import com.particlesdevs.photoncamera.processing.color.ColourLook;
import com.particlesdevs.photoncamera.processing.color.FilmLut;
import com.particlesdevs.photoncamera.processing.opengl.postpipeline.WhyTone;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;

/** RAW development adapted from libvf_demosaic, using the preview's own GL context.
 * One context owns upload and draw: no cross-context HardwareBuffer race or readback. */
final class LiveRawRenderer {
    private int program, shadingTex, lutTex;
    /**
     * P365 (owner, 2026-10-27: «В арк камере тоже есть живой видоискатель, сделай его такимже по требованиям как там»; scam_dev
     * vf_fused): two RAW textures taken in turn, so a frame's upload never waits for the GPU to finish reading the texture of the
     * frame before (busy with the merge: the 36-134 ms worst draws of P353's logs); +25 MB at 4096 x 3072. vf_fused 0: one, as
     * before. Their sizes, the frame versions they hold (-1 none) and the one drawn ({@code cur}).
     */
    private final int[] rawTexs=new int[2], texW=new int[2], texH=new int[2], texVersion={-1,-1};
    private int cur;
    private void deleteResources(){
        if(program!=0)GLES20.glDeleteProgram(program);
        GLES20.glDeleteTextures(5,new int[]{rawTexs[0],rawTexs[1],shadingTex,lutTex,filmLutTex},0);
        program=shadingTex=lutTex=filmLutTex=0;forgetTextures();uniforms.clear();programUniforms.clear();lutShown=null;
    }
    private void forgetTextures(){
        rawTexs[0]=rawTexs[1]=texW[0]=texW[1]=texH[0]=texH[1]=cur=0;texVersion[0]=texVersion[1]=-1;shadingShown=null;meteredVersion=-1;
    }
    // P134: the photo's «Цвет» look (ColourLook: the white-balance matrix, monochrome, skin, vibrance by brightness, the
    // mixer, the grading) and the film LUT in the RAW viewfinder, read from the settings twice a second; the LUT file is
    // parsed off the GL thread. The tone stays the viewfinder's own (WhyCore), so the look shows on its rendering.
    private static final java.util.concurrent.ExecutorService LUT_LOADER=java.util.concurrent.Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"vf-film-lut");t.setDaemon(true);return t;});
    // P297 (owner, 2026-10-20: no LUT / film on the ISZ viewfinder): the ISZ modules show this RAW viewfinder (no ISP stream,
    // P223 / P266), the 1x module the ISP one (LiveLensFilter). lookAt started at Long.MIN_VALUE: now-lookAt overflowed to a
    // negative number, below the interval for ever, so refreshLook never read the settings - no look, no film simulation, no LUT
    // in any RAW viewfinder since P134. "Never read" is now one interval before the clock's zero (elapsedRealtime is >= 0).
    static final long LOOK_REFRESH_MS=500, LOOK_NEVER=-LOOK_REFRESH_MS;
    private long lookAt=LOOK_NEVER;
    /** P297: the look is read again (at most twice a second; at once after {@link #LOOK_NEVER}). */
    static boolean lookDue(long now,long last){return now-last>=LOOK_REFRESH_MS;}
    private float[] lookWhite,lookSkin,lookVib,lookMix,lookMono,lookGradeA,lookGradeB;
    private boolean lookOn,lookMixOn;
    private float lutStrength;
    private volatile String lutWanted="";
    private volatile FilmLut lutReady;
    private FilmLut lutShown;
    private int filmLutTex;
    private void refreshLook(){
        long now=android.os.SystemClock.elapsedRealtime();
        if(!lookDue(now,lookAt))return;
        lookAt=now;
        try{
            ColourLook c=WhyTone.colourLook();
            lookWhite=c.whiteMatrix(0f);
            lookOn=!c.shaderNeutral();
            lookSkin=c.skinUniform();lookVib=c.vibUniform();lookMix=c.mixUniform();lookMixOn=c.mixerOn();lookMono=c.monoUniform();
            float[][] grade=c.gradeUniforms();lookGradeA=grade[0];lookGradeB=grade[1];
            // P281: the film simulation and the LUT as one table (FilmChain)
            com.particlesdevs.photoncamera.processing.color.FilmChain.State chain=com.particlesdevs.photoncamera.processing.color.FilmChain.current();
            lutStrength=chain.strength;
            String name=lutStrength>0f?chain.key:"";
            if(!name.equals(lutWanted)){
                lutWanted=name;lutReady=null;
                if(!name.isEmpty())LUT_LOADER.execute(()->{
                    FilmLut lut=com.particlesdevs.photoncamera.processing.color.FilmChain.lut(ScameraApp.getAppContext(),name);
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
    private int frameSession=-1, draws;
    /** P365: the frame version LiveRawMeter measured last (-1 none): the meter runs per frame, not per upload. */
    private int meteredVersion=-1;
    /** P365: the lens-shading map in the texture (null: none yet): a frame with the same map uploads none. */
    private float[] shadingShown;
    private int shadingShownW, shadingShownH;
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
        program=shadingTex=lutTex=filmLutTex=0;lutShown=null;forgetTextures();
        frameSession=-1; failed=false; uniforms.clear(); programUniforms.clear(); meter.reset(); lastView=null; boundFresh=false;
    }
    private int loc(String name) {return uniforms.computeIfAbsent(name,n->GLES20.glGetUniformLocation(program,n));}
    /** P365: the uniform locations of the fused program(s) (LiveWhyTone's why/combine RAW_SOURCE 1), per program. */
    private final HashMap<Integer,HashMap<String,Integer>> programUniforms=new HashMap<>();
    private int loc(int p,String name){
        HashMap<String,Integer> m=programUniforms.get(p);
        if(m==null)programUniforms.put(p,m=new HashMap<>());
        return m.computeIfAbsent(name,n->GLES20.glGetUniformLocation(p,n));
    }
    private void integer(String n,int v){GLES20.glUniform1i(loc(n),v);}
    private void number(String n,float v){GLES20.glUniform1f(loc(n),v);}
    boolean draw(FloatBuffer vertices,FloatBuffer coords,float[] rotation,boolean mirror,int peaking) {
        return draw(vertices,coords,rotation,mirror,peaking,false);
    }
    /** P301: the frame's linear values (LiveWhyTone's input) instead of the viewfinder's own tone; null-safe as draw. */
    boolean drawLinear(FloatBuffer vertices,FloatBuffer coords,float[] rotation,boolean mirror) {
        return draw(vertices,coords,rotation,mirror,0,true);
    }
    /**
     * P301: the frame drawn last ({@link #drawLinear}): its ISO and noise for the WHY tone's AE; P314 with its visible crop and the
     * colour transform it was drawn with (LiveWhyMeter meters it on the CPU). Null before one.
     */
    com.particlesdevs.photoncamera.processing.LiveWhyMeter.View lastView() {
        return lastView;
    }
    private com.particlesdevs.photoncamera.processing.LiveWhyMeter.View lastView;
    /**
     * P314: the frame's visible crop {left, top, width, height} in frame pixels (whole CFA periods, at least one), fitted to the
     * viewport's aspect without stretching. {@code quarterTurn}: the rotation turns the frame by 90 degrees (frame x along the
     * screen's x); without it the frame's x runs along the screen's y (rawdevelop_vs swaps the texture axes).
     */
    static int[] visibleCrop(LiveRawFrame.Frame f,int vpW,int vpH,boolean quarterTurn) {
        int period=f.cfaBlock*2;
        int left=(int)(f.crop[0]*f.width)/period*period,top=(int)(f.crop[1]*f.height)/period*period;
        int cw=Math.min(f.width-left,(int)(f.crop[2]*f.width))/period*period;
        int ch=Math.min(f.height-top,(int)(f.crop[3]*f.height))/period*period;
        // RAW is in sensor coordinates; match the visible preview aspect without stretching.
        if(vpW>0 && vpH>0) {
            float aspect=quarterTurn ? vpW/(float)vpH : vpH/(float)vpW;
            if(cw/(float)Math.max(ch,1)>aspect) {
                int fitted=Math.max(period,(int)(ch*aspect)/period*period);
                left+=(cw-fitted)/2/period*period;cw=fitted;
            } else {
                int fitted=Math.max(period,(int)(cw/aspect)/period*period);
                top+=(ch-fitted)/2/period*period;ch=fitted;
            }
        }
        return new int[]{left,top,Math.max(period,cw),Math.max(period,ch)};
    }
    /**
     * rawdevelop_fs cfa_mode: 1 the range-filtered Bayer of a plain frame, 0 one colour per 2 x 2 of macro pixels (a mosaic's
     * blocks); P314: a frame of Quad / Tetra cell means (LiveRawFrame, block 1) is drawn as its mosaic was, with 0.
     */
    static int cfaMode(LiveRawFrame.Frame f) {
        return f.cfaBlock==1 && !f.cellMean?1:0;
    }
    /** P314: steps of the tone size (a share of the viewport): a zoom does not make the targets anew on every frame. */
    static final int TONE_STEPS=16;
    /**
     * P314: the size the WHY tone is drawn at - the visible crop's colour grid (one colour per 2 x 2 block of macro pixels:
     * crop / (2 block)) in screen orientation, never above the viewport, its aspect kept, the scale rounded up to 1 / TONE_STEPS:
     * a 2x ISZ Quad frame has 1024 x 768 colours (1x Bayer 2048 x 1536), drawn at the 1440 x 1920 viewport each was computed 3.6
     * times (a Tetra one 14 times). The filter / peaking pass scales it to the screen (bilinear).
     */
    static int[] toneSize(int vpW,int vpH,int cropW,int cropH,int block,boolean quarterTurn) {
        vpW=Math.max(1,vpW);vpH=Math.max(1,vpH);
        final int b=Math.max(1,block);
        final double gx=(quarterTurn?cropW:cropH)/(2.0*b), gy=(quarterTurn?cropH:cropW)/(2.0*b);
        final double need=Math.max(gx/vpW,gy/vpH);
        if(!(need<1.0))return new int[]{vpW,vpH};
        final double s=Math.max(1,Math.ceil(need*TONE_STEPS-1e-9))/(double)TONE_STEPS;
        return new int[]{Math.max(1,(int)Math.round(vpW*s)),Math.max(1,(int)Math.round(vpH*s))};
    }
    /** A frame published longer ago than this is not drawn (the ISP picture takes over); P365: longer while a series is held. */
    static final long MAX_AGE_NS=500_000_000L;
    private boolean draw(FloatBuffer vertices,FloatBuffer coords,float[] rotation,boolean mirror,int peaking,boolean linear) {
        return draw(vertices,coords,rotation,mirror,peaking,linear,maxAgeNs);
    }
    /** P365: the age limit of the next draws (MainRenderer: {@link #MAX_AGE_NS}, longer while a series is held). */
    private long maxAgeNs=MAX_AGE_NS;
    void setMaxAge(long ns){maxAgeNs=ns;}
    private boolean draw(FloatBuffer vertices,FloatBuffer coords,float[] rotation,boolean mirror,int peaking,boolean linear,long maxAge) {
        LiveRawFrame.Frame f=LiveRawFrame.acquire();
        if(f==null || System.nanoTime()-f.publishedNanos>maxAge)return false;
        if(frameSession!=f.session){frameSession=f.session;failed=false;texVersion[0]=texVersion[1]=-1;meteredVersion=-1;meter.reset();}
        if(failed)return false;
        try {
            if(program==0 && !init())return false;
            refreshLook();
            GLES20.glUseProgram(program);
            if(!upload(f,fusedOn()))return false;
            meter(f,linear);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,rawTexs[cur]);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,shadingTex);
            setLook();
            integer("sTexture16",2);integer("u_lsc_map",3);integer("u_lut_tex",4);
            integer("width",f.width);integer("height",f.height);integer("bayer_pattern",f.cfaPattern);
            integer("cfa_mode",cfaMode(f));integer("cfa_block_size",f.cfaBlock);
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT,viewport,0);
            final int[] crop=visibleCrop(f,viewport[2],viewport[3],Math.abs(rotation[1])>Math.abs(rotation[0]));
            integer("crop_left",crop[0]);integer("crop_top",crop[1]);
            integer("crop_width",crop[2]);integer("crop_height",crop[3]);
            int[][] orders={{0,1,2,3},{1,0,3,2},{2,3,0,1},{3,2,1,0}};
            int[] o=orders[f.cfaPattern];
            GLES20.glUniform4f(loc("u_black_level"),f.blackLevel[o[0]],f.blackLevel[o[1]],f.blackLevel[o[2]],f.blackLevel[o[3]]);
            number("u_white_level",f.whiteLevel);number("r_gain",f.wbGains[0]);number("g_gain",f.wbGains[1]);number("b_gain",f.wbGains[2]);
            float[] white=lookWhite;
            final float[] transform=white!=null?lookTransform(white,f.colorTransform):f.colorTransform;
            GLES20.glUniformMatrix3fv(loc("color_transform"),1,false,transform,0);
            number("u_lsc_intensity",f.shadingWidth>1?1:0);
            number("u_auto_exposure",meter.exposure);number("u_contrast_boost",meter.contrast);
            GLES20.glUniform4fv(loc("u_exp_mults"),1,meter.multipliers,0);
            integer("mirror",mirror?1:0);integer("peaking_enabled",peaking);integer("u_linear_out",linear?1:0);
            if(linear)lastView=new com.particlesdevs.photoncamera.processing.LiveWhyMeter.View(f,crop[0],crop[1],crop[2],crop[3],transform);
            GLES20.glUniform3f(loc("peaking_color"),1,.8f,0);
            GLES20.glUniformMatrix4fv(loc("texRotate"),1,false,rotation,0);
            vertices.position(0);coords.position(0);
            GLES20.glEnableVertexAttribArray(0);GLES20.glEnableVertexAttribArray(1);
            GLES20.glVertexAttribPointer(0,2,GLES20.GL_FLOAT,false,8,vertices);
            GLES20.glVertexAttribPointer(1,2,GLES20.GL_FLOAT,false,8,coords);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
            if(!check("draw"))return false;
            logDraw(f);
            return true;
        } catch(RuntimeException e) {
            failed=true;Log.e("LiveRawRenderer","RAW preview fallback: "+e);return false;
        } finally {
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH,0);GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT,4);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        }
    }
    private void logDraw(LiveRawFrame.Frame f){
        if(draws++%120==0)Log.d("LiveRawRenderer","RAW developed session="+f.session+" frame="+f.version+" size="+f.width+"x"+f.height+" CFA="+f.cfaPattern+" block="+f.cfaBlock
                +(f.cellMean?" cell means":"")+" AE="+meter.exposure+" target="+viewport[2]+"x"+viewport[3]+" dropped="+LiveRawFrame.droppedFrames()
                +(fusedOn()?" textures 2":""));
    }
    /** P365: scam_dev vf_fused (default on): the fused pass and, with it, this renderer's two textures and its uploads on change. */
    static boolean fusedOn(){
        return com.particlesdevs.photoncamera.processing.opengl.postpipeline.LiveWhyTone.fusedSwitch();
    }
    /**
     * P365: the texture slot a frame of {@code version} is drawn from - the one holding it already, else (two textures) the other
     * one than {@code current} (the GPU read it longest ago), else (one texture, vf_fused 0) slot 0. {@code versions}: the frame
     * version each slot holds (-1 none).
     */
    static int slotFor(int version,int current,int[] versions,boolean twin){
        if(versions[current]==version)return current;
        if(!twin)return 0;
        return 1-current;
    }
    /**
     * The frame into its texture (slotFor) unless the texture holds it already, the shading map with it; P365 {@code twin}: the
     * two textures in turn, the shading map only when it changed. False on a GL error (then failed, as before).
     */
    private boolean upload(LiveRawFrame.Frame f,boolean twin){
        final int slot=slotFor(f.version,cur,texVersion,twin);
        boundFresh=texVersion[slot]!=f.version;
        cur=slot;
        if(rawTexs[slot]==0)rawTexs[slot]=texture(GLES20.GL_TEXTURE2,GLES20.GL_TEXTURE_2D,GLES20.GL_NEAREST);
        if(shadingTex==0)shadingTex=texture(GLES20.GL_TEXTURE3,GLES20.GL_TEXTURE_2D,GLES20.GL_LINEAR);
        if(!boundFresh)return true;
        f.buffer.position(0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,rawTexs[slot]);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT,2);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH,f.rowStride/2);
        if(texW[slot]!=f.width || texH[slot]!=f.height) {
            GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES30.GL_R16UI,f.width,f.height,0,GLES30.GL_RED_INTEGER,GLES20.GL_UNSIGNED_SHORT,f.buffer);
            texW[slot]=f.width;texH[slot]=f.height;
        } else GLES30.glTexSubImage2D(GLES20.GL_TEXTURE_2D,0,0,0,f.width,f.height,GLES30.GL_RED_INTEGER,GLES20.GL_UNSIGNED_SHORT,f.buffer);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH,0);GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT,4);
        texVersion[slot]=f.version;
        if(!twin || !sameShading(shadingShown,shadingShownW,shadingShownH,f.shading,f.shadingWidth,f.shadingHeight)) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,shadingTex);
            if(shadingBuffer==null || shadingBuffer.capacity()<f.shading.length)
                shadingBuffer=ByteBuffer.allocateDirect(f.shading.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            shadingBuffer.clear();shadingBuffer.put(f.shading).flip();
            GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES30.GL_RGB16F,f.shadingWidth,f.shadingHeight,0,GLES20.GL_RGB,GLES20.GL_FLOAT,shadingBuffer);
            shadingShown=f.shading;shadingShownW=f.shadingWidth;shadingShownH=f.shadingHeight;
        }
        if(!check("upload")){texVersion[slot]=-1;shadingShown=null;return false;}
        return true;
    }
    /** P365: whether a frame's lens-shading map is the one in the texture (LiveRawFrame hands every frame its own array). */
    static boolean sameShading(float[] shown,int shownW,int shownH,float[] map,int w,int h){
        return shown!=null && map!=null && shownW==w && shownH==h && java.util.Arrays.equals(shown,map);
    }
    /**
     * LiveRawMeter on a frame it has not measured (the viewfinder's own look exposes by it), then SceneLuma (P212h). P365: vf_fused 0
     * - on every new frame, as before; else only for the own look ({@code linear} false) or while SceneLuma wants a value.
     */
    private void meter(LiveRawFrame.Frame f,boolean linear){
        if(meteredVersion==f.version)return;
        if(fusedOn() && linear && !(f.sceneLuma && com.particlesdevs.photoncamera.manual.SceneLuma.wanted()))return;
        meter.update(f);
        sceneLuma(f);
        meteredVersion=f.version;
    }
    /** P365: whether the frame bound last ({@link #bindFrame}, {@link #draw}) was new to its texture (the WHY tone's AE runs then). */
    private boolean boundFresh;
    boolean boundFresh(){return boundFresh;}
    /**
     * P365 (owner, 2026-10-27: «В арк камере тоже есть живой видоискатель, сделай его такимже по требованиям как там»): the RAW
     * frame for the fused WHY pass (LiveWhyTone.drawFused: why/combine RAW_SOURCE 1 is {@code p}, its target bound and its viewport
     * set) - uploaded as for {@link #draw}, the RAW on unit 9 and the lens-shading map on unit 10 (why/combine takes 0 - 8),
     * rawdevelop_fs's uniforms of the linear frame (the look's white matrix in color_transform), texRotate; lastView as
     * {@link #drawLinear} sets it. False without a frame young enough or after a failure (the caller falls back).
     */
    boolean bindFrame(int p,float[] rotation,boolean mirror){
        LiveRawFrame.Frame f=LiveRawFrame.acquire();
        if(f==null || System.nanoTime()-f.publishedNanos>maxAgeNs)return false;
        if(frameSession!=f.session){frameSession=f.session;failed=false;texVersion[0]=texVersion[1]=-1;meteredVersion=-1;meter.reset();}
        if(failed)return false;
        try {
            refreshLook();
            if(!upload(f,fusedOn()))return false;
            meter(f,true);
            GLES20.glUseProgram(p);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE9);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,rawTexs[cur]);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE10);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,shadingTex);
            GLES20.glUniform1i(loc(p,"sTexture16"),9);GLES20.glUniform1i(loc(p,"u_lsc_map"),10);
            GLES20.glUniform1i(loc(p,"width"),f.width);GLES20.glUniform1i(loc(p,"height"),f.height);
            GLES20.glUniform1i(loc(p,"bayer_pattern"),f.cfaPattern);
            GLES20.glUniform1i(loc(p,"cfa_mode"),cfaMode(f));GLES20.glUniform1i(loc(p,"cfa_block_size"),f.cfaBlock);
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT,viewport,0);
            final int[] crop=visibleCrop(f,viewport[2],viewport[3],Math.abs(rotation[1])>Math.abs(rotation[0]));
            GLES20.glUniform1i(loc(p,"crop_left"),crop[0]);GLES20.glUniform1i(loc(p,"crop_top"),crop[1]);
            GLES20.glUniform1i(loc(p,"crop_width"),crop[2]);GLES20.glUniform1i(loc(p,"crop_height"),crop[3]);
            int[][] orders={{0,1,2,3},{1,0,3,2},{2,3,0,1},{3,2,1,0}};
            int[] o=orders[f.cfaPattern];
            GLES20.glUniform4f(loc(p,"u_black_level"),f.blackLevel[o[0]],f.blackLevel[o[1]],f.blackLevel[o[2]],f.blackLevel[o[3]]);
            GLES20.glUniform1f(loc(p,"u_white_level"),f.whiteLevel);
            GLES20.glUniform1f(loc(p,"r_gain"),f.wbGains[0]);GLES20.glUniform1f(loc(p,"g_gain"),f.wbGains[1]);GLES20.glUniform1f(loc(p,"b_gain"),f.wbGains[2]);
            float[] white=lookWhite;
            final float[] transform=white!=null?lookTransform(white,f.colorTransform):f.colorTransform;
            GLES20.glUniformMatrix3fv(loc(p,"color_transform"),1,false,transform,0);
            GLES20.glUniform1f(loc(p,"u_lsc_intensity"),f.shadingWidth>1?1:0);
            GLES20.glUniform1i(loc(p,"mirror"),mirror?1:0);GLES20.glUniform1i(loc(p,"u_ldc_enabled"),0);
            GLES20.glUniformMatrix4fv(loc(p,"texRotate"),1,false,rotation,0);
            lastView=new com.particlesdevs.photoncamera.processing.LiveWhyMeter.View(f,crop[0],crop[1],crop[2],crop[3],transform);
            logDraw(f);
            return true;
        } catch(RuntimeException e) {
            failed=true;Log.e("LiveRawRenderer","RAW preview fallback: "+e);return false;
        } finally {
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH,0);GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT,4);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        }
    }
    /** P365: the program {@link #bindFrame} bound, over the viewport with the frame's vertices (rawdevelop_vs: locations 0, 1). */
    void drawBound(FloatBuffer vertices,FloatBuffer coords){
        vertices.position(0);coords.position(0);
        GLES20.glEnableVertexAttribArray(0);GLES20.glEnableVertexAttribArray(1);
        GLES20.glVertexAttribPointer(0,2,GLES20.GL_FLOAT,false,8,vertices);
        GLES20.glVertexAttribPointer(1,2,GLES20.GL_FLOAT,false,8,coords);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
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
    /** P312: the program's key in the driver-binary cache (its sources; rawdevelop_vs places its attributes itself). */
    private static volatile String sKey;
    static String programKey() {
        String key=sKey;
        if(key==null)sKey=key=com.particlesdevs.photoncamera.processing.opengl.GLProg.viewfinderKey(
                ScameraApp.getAssetLoader().getString("shaders/preview/rawdevelop_vs.glsl"),
                ScameraApp.getAssetLoader().getString("shaders/preview/rawdevelop_fs.glsl"));
        return key;
    }
    /** P312: whether the program is linked (MainRenderer may then draw the plain RAW look while the WHY programs are built). */
    boolean ready(){return program!=0;}
    /**
     * P312: links the program before the first RAW frame - from its kept driver binary at once; compiled here when it is missing,
     * unless {@code compilingElsewhere} has it (then false: the caller keeps the ISP frame). True when linked or failed.
     */
    boolean prepare(java.util.function.Predicate<String> compilingElsewhere) {
        if(program!=0 || failed)return true;
        final String key=programKey();
        // pending first: the background thread keeps the binary before it lets the key go
        if(compilingElsewhere.test(key))return false;
        int p=com.particlesdevs.photoncamera.processing.opengl.GLProg.cachedViewfinderProgram(key);
        if(p==0){
            // a binary refused or never kept: once to the background, then compiled here (this driver keeps none)
            if(!ViewfinderPrograms.tried(key)){ViewfinderPrograms.prepare(java.util.Collections.singletonList(key));return false;}
            p=com.particlesdevs.photoncamera.processing.opengl.GLProg.linkViewfinderProgram(key);
        }
        if(p==0){failed=true;return true;}
        setup(p);
        return true;
    }
    private boolean init() {
        // P312: from the kept driver binary (650 lines of rawdevelop_fs compiled at every start: the viewfinder stood still)
        int p=com.particlesdevs.photoncamera.processing.opengl.GLProg.viewfinderProgram(programKey());
        if(p==0){failed=true;return false;}
        return setup(p);
    }
    private boolean setup(int p) {
        program=p;
        uniforms.clear();GLES20.glUseProgram(program);
        // the RAW and shading textures are made at the first upload (P365: the fused pass draws without this program)
        lutTex=texture(GLES20.GL_TEXTURE4,GLES30.GL_TEXTURE_3D,GLES20.GL_LINEAR);
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D,0,GLES30.GL_RGB8,1,1,1,0,GLES20.GL_RGB,GLES20.GL_UNSIGNED_BYTE,ByteBuffer.allocateDirect(4));
        // The look of libvf_demosaic (WhyCore) at its library defaults: 4-layer exposure fusion metered by LiveRawMeter, ACES,
        // OKLab macro contrast and vibrance, gamma 2.2 with the film toe. Optional LUT/LDC are disabled until supplied with a profile.
        integer("u_ef_enabled",1);integer("u_tonemap_op",0);integer("u_lut_enabled",0);integer("u_ldc_enabled",0);
        number("u_weight_center",.5f);number("u_blend_smoothness",.15f);number("u_macro_contrast",1.1f);
        number("u_gamma",2.2f);number("u_film_toe",.05f);number("u_shadow_vibrance",1.1f);number("u_highlight_vibrance",1);
        number("u_aces_toe",.01f);number("u_aces_pre_gain",1.35f);number("u_aces_a_coeff",2.7f);number("u_aces_d_coeff",.59f);
        GLES20.glUniform4f(loc("u_layer_weights"),.7f,.8f,1,.5f);
        return check("initialize");
    }
}

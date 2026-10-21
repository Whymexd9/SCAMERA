package com.particlesdevs.photoncamera.processing.opengl;

import com.particlesdevs.photoncamera.util.Log;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.processing.render.Parameters;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static com.particlesdevs.photoncamera.processing.opengl.GLProg.glVersion;

public class GLInterface {
    public final GLProg glProgram;
    public Parameters parameters;
    public GLCoreBlockProcessing glProcessing;
    public GLContext glContext;
    public GLUtils glUtils;

    public GLInterface(GLCoreBlockProcessing processing) {
        glProcessing = processing;
        glProgram = glProcessing.mProgram;
        glUtils = new GLUtils(glProcessing);
    }

    public GLInterface(GLContext context) {
        glContext = context;
        glProgram = glContext.mProgram;
    }
    public static String loadShader(String code){
        return loadShader(code,null);
    }
    public static String loadShader(int fragment){
        return loadShader(fragment,null);
    }
    public static String loadShader(int fragment,ArrayList<String[]> defines){
        BufferedReader reader = new BufferedReader(new InputStreamReader(ScameraApp.getResourcesStatic().openRawResource(fragment)));
        return readProgram(reader,defines);
    }
    public static String loadShader(String code,ArrayList<String[]> defines){
        BufferedReader reader = new BufferedReader(new StringReader(code));
        return readProgram(reader,defines);
    }
    private static int findLeft(String in, char search){
        char left;
        for(int i =0; i<in.length();i++){
            left = in.charAt(i);
            if(left == search) return i;
        }
        return 0;
    }
    private static int findRight(String in, char search){
        char right;
        for(int i = in.length()-1; i>=0;i--){
            right = in.charAt(i);
            if(right == search) return i;
        }
        return in.length()-1;
    }
    private static int getParameter(String[] in, String parameter){
        for(String in2 : in){
            String[] paramVal = in2.replace(" ","").split("=");
            if(paramVal[0].equals(parameter)) return Integer.parseInt(paramVal[1]);
        }
        return 0;
    }
    public static Map<String, GLComputeLayout> getLayouts(String program){
        BufferedReader reader = new BufferedReader(new StringReader(program));
        Map<String, GLComputeLayout> layoutsMap = new HashMap<>();
        for (Object line : reader.lines().toArray()) {
            String val = String.valueOf(line);
            int comment = val.indexOf("//");
            if (comment >= 0) val = val.substring(0, comment);
            if(val.contains("layout")){
                // P317: a block is named by the word before its '{'. A one-line block (ark/aestats, ultrahdr/gainmax:
                // "buffer aeHistY { uint histY[]; };") was keyed "}", so setBufferCompute bound nothing and the shader saw
                // whatever sat at its binding - the GLBuffer's own name by luck on Adreno, nothing on Mali (histograms 0 / 0).
                final int brace = val.indexOf('{');
                if (brace >= 0) val = val.substring(0, brace);
                String[] divided = val.trim().split("\\s+");
                String last = "";
                if(divided.length > 0){
                    last = divided[divided.length-1].replace(";","").replace("\n","");
                }
                String[] parameters = val.substring(findLeft(val,'(')+1,findRight(val,')')).split(",");
                if(last.equals("in")){
                    layoutsMap.put(last,new GLComputeLayout(
                            getParameter(parameters,"local_size_x"),
                            getParameter(parameters,"local_size_y"),
                            getParameter(parameters,"local_size_z")));
                } else {
                    layoutsMap.put(last,new GLComputeLayout(getParameter(parameters,"binding")));
                }
            }
        }
        return layoutsMap;
    }
    /** P317: the int precision every fragment program gets (Mali's mediump int is 16 bit). */
    static final String HIGHP_INT = "precision highp int;\n";

    public static String readProgram(BufferedReader reader, ArrayList<String[]> defines) {
        StringBuilder source = new StringBuilder();
        int linecnt = 0;
        boolean versioned = false;
        // P317: where the leading #extension directives end (the precision statement below must follow them)
        int extensionEnd = -1, extensionLine = 0;
        for (Object line : reader.lines().toArray()) {
            linecnt++;
            String val = String.valueOf(line);
            if(val.contains("#version"))
                versioned = true;
            if(val.contains("#import")){
                String imported = "";
                if(!val.contains("//")) {
                    imported = ScameraApp.getAssetLoader().getString(
                            "shaders/utils/" +
                                    val
                                            .replace("#", "")
                                            .replace(" ", "_")
                                            .replace("\n", "")
                                    + ".glsl");
                }
                if(!imported.equals("")){
                    source.append("#line 1\n");
                    source.append(imported);
                    source.append("\n");
                    source.append("#line ").append(linecnt+1).append("\n");
                }

                continue;
            }
            if(val.contains("#define") && defines != null){
                for(String[] define : defines){
                    if(val.contains(" "+define[0]+" ")){
                        line = "#define "+define[0]+" "+define[1];
                        //Log.d("GLInterface","Overwrite:"+line);
                        break;
                    }
                }
            }
            source.append(line).append("\n");
            if (val.trim().startsWith("#extension")) {
                extensionEnd = source.length();
                extensionLine = linecnt;
            }
        }
        if(versioned) return source.toString();
        // P317 (owner, 2026-10-21: «Исправь работу на всех мтк»): a fragment shader's ints default to mediump (GLSL ES 3.10
        // 4.7.4) - 16 bit on Mali, so ivec2(gl_FragCoord.xy) and its float conversions were fp16 there (coordinates in 2 / 4 px
        // steps above 2048 / 4096 px; the film grain hash went to +inf: white photos on the X300 Pro). highp int for every
        // program without its own #version, after its #extension directives.
        if (extensionEnd >= 0) {
            source.insert(extensionEnd, HIGHP_INT + "#line " + (extensionLine + 1) + "\n");
            return glVersion + "\n" + "#line 1\n" + source;
        }
        return glVersion + "\n" + HIGHP_INT + "#line 1\n" + source;
    }
}

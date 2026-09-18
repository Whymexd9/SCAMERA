// Execute production shader sections at their stage boundary, before display tonemapping.
#define main fusion_fixture_main
#include "check_vivo_hexquad_fusion.cpp"
#undef main
static GLuint fromText(const std::string& text){
    GLuint vs=shader(GL_VERTEX_SHADER,"#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}"),fs=shader(GL_FRAGMENT_SHADER,text),p=glCreateProgram();
    glAttachShader(p,vs);glAttachShader(p,fs);glLinkProgram(p);GLint ok=0;glGetProgramiv(p,GL_LINK_STATUS,&ok);require(ok,"program link");glDeleteShader(vs);glDeleteShader(fs);return p;
}
int main(){try{
    EGLDisplay d=eglGetDisplay(EGL_DEFAULT_DISPLAY);require(eglInitialize(d,nullptr,nullptr),"eglInitialize");require(eglBindAPI(EGL_OPENGL_ES_API),"eglBindAPI");
    const EGLint attr[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,EGL_NONE};EGLConfig cfg;EGLint count;require(eglChooseConfig(d,attr,&cfg,1,&count)&&count,"config");
    const EGLint pa[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE},ca[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE};
    EGLSurface surface=eglCreatePbufferSurface(d,cfg,pa);EGLContext ctx=eglCreateContext(d,cfg,EGL_NO_CONTEXT,ca);require(eglMakeCurrent(d,surface,surface,ctx),"context");
    GLuint fbo,vao;glGenFramebuffers(1,&fbo);glBindFramebuffer(GL_FRAMEBUFFER,fbo);glGenVertexArrays(1,&vao);glBindVertexArray(vao);glDisable(GL_DITHER);
    const int w=24,h=24;std::vector<float> data(w*h*4,.4f);
    for(int y=0;y<h;y++)for(int x=0;x<w;x++)for(int c=0;c<3;c++)data[(y*w+x)*4+c]+=(x+y)%2 ? .01f:-.01f;
    GLuint input=texture(w,h,data),output=texture(w,h,{});
    std::string initial=source("Initial/initial");auto start=initial.find("    #if DARKTABLE_ENABLED == 1",initial.find("void main()"));auto end=initial.find("    #endif",start);require(start!=std::string::npos&&end!=std::string::npos,"darktable stage missing");
    std::string section=initial.substr(start,end+10-start);
    for(float amount:{0.f,1.f}){
        std::string f="#version 300 es\nprecision highp float;uniform sampler2D InputBuffer;out vec3 Output;\n#define DARKTABLE_ENABLED 1\n#define DT_PROFILED_DENOISE "+std::to_string(amount)+"\n#define DT_DIFFUSE_SHARPEN 0.0\n#define DT_TEXTURE 0.0\n#define DT_WIDE_CA 0.0\n#define NOISEO 0.001\n#define NOISES 0.0\n#define luminocity(x) dot(x.rgb,vec3(0.299,0.587,0.114))\nvoid main(){ivec2 xy=ivec2(gl_FragCoord.xy);vec3 sRGB=texelFetch(InputBuffer,xy,0).rgb;\n"+section+"\nOutput=sRGB;}";
        GLuint p=fromText(f);glUseProgram(p);bind(p,"InputBuffer",input,0);render(output,w,h);std::vector<float> out(w*h*4);glReadPixels(0,0,w,h,GL_RGBA,GL_FLOAT,out.data());require(glGetError()==GL_NO_ERROR,"readback");
        for(int y=2;y<h-2;y++)for(int x=2;x<w-2;x++){
            int i=(y*w+x)*4;for(int c=0;c<3;c++){
                if(amount==0)require(std::abs(out[i+c]-data[i+c])<.001,"zero controls changed signal");
                else require(std::abs(out[i+c]-.4f)<.005,"NR ignored in one colour channel");
                require(std::abs(out[i+c]-out[i+1])<.0005,"NR introduced chroma");
            }
        }glDeleteProgram(p);
    }
    // Compile the complete Initial shader too, including every display branch.
    for(int fusion=0;fusion<2;fusion++)for(int aces=0;aces<2;aces++)for(int curve=0;curve<2;curve++){
        GLuint p=program("Initial/initial",{{"FUSION",std::to_string(fusion)},{"ACES_ENABLED",std::to_string(aces)},{"EXPOCURVE",std::to_string(curve)},{"DARKTABLE_ENABLED","1"}});glDeleteProgram(p);
    }
    std::string merge=source("merge/mergeCombineWeight0");start=merge.find("    int m = max(1, int(flowMaxDisp");end=merge.find("    if (block == ivec2(0))",start);require(start!=std::string::npos&&end!=std::string::npos,"flow clamp section missing");
    section=merge.substr(start,end-start);
    GLuint p=fromText("#version 300 es\nprecision highp float;uniform float flowMaxDisp;uniform int mosaicPeriod;out vec3 Output;void main(){vec2 flow=gl_FragCoord.xy-vec2(12.0);\n"+section+"Output=vec3(vec2(block),0);}");
    glUseProgram(p);for(int period:{1,2,4})for(int limit:{1,2,3,4}){
        glUniform1i(glGetUniformLocation(p,"mosaicPeriod"),period);glUniform1f(glGetUniformLocation(p,"flowMaxDisp"),float(limit));render(output,w,h);std::vector<float> out(w*h*4);glReadPixels(0,0,w,h,GL_RGBA,GL_FLOAT,out.data());
        for(int i=0;i<w*h;i++)for(int c=0;c<2;c++){int v=int(std::round(out[i*4+c]));require(std::abs(v)<=limit,"flow exceeded user limit");require(v%period==0,"flow shifted colour phase");}
    }
    std::cout<<"Settings GLES PASS: zero-control identity, NR affects R/G/B, Initial branches link; flow bounds preserve CFA\n";return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}

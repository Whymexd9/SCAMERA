precision highp float;
precision highp sampler2D;
uniform sampler2D InputBuffer;
out vec3 Output;
#define SIZE 1,1
#define ENABLED 0
#define LCC 0.0
#define LCC_RED 0.0
#define LCC_BLUE 0.0
#define CLARITY 0.0
#define STRUCTURE 0.0
#define MOIRE 0.0
#define SINGLE_PIXEL 0.5
#define SKIN 0.0
#define COLOR_HUE 0.0
#define COLOR_RANGE 0.0
#define COLOR_SHIFT 0.0
#define COLOR_SAT 0.0
#define SHADOWS 0.0
#define MIDTONES 0.0
#define HIGHLIGHTS 0.0
const vec3 YW=vec3(0.2126,0.7152,0.0722);
float lum(vec3 c){return dot(c,YW);}
vec3 getp(ivec2 p){return texelFetch(InputBuffer,clamp(p,ivec2(0),ivec2(SIZE)-1),0).rgb;}
vec3 rgb2hsv(vec3 c){
    vec4 K=vec4(0.0,-1.0/3.0,2.0/3.0,-1.0);
    vec4 p=mix(vec4(c.bg,K.wz),vec4(c.gb,K.xy),step(c.b,c.g));
    vec4 q=mix(vec4(p.xyw,c.r),vec4(c.r,p.yzx),step(p.x,c.r));
    float d=q.x-min(q.w,q.y),e=1e-6;
    return vec3(abs(q.z+(q.w-q.y)/(6.0*d+e)),d/(q.x+e),q.x);
}
vec3 hsv2rgb(vec3 c){
    vec3 p=abs(fract(c.xxx+vec3(0.0,2.0/3.0,1.0/3.0))*6.0-3.0);
    return c.z*mix(vec3(1.0),clamp(p-1.0,0.0,1.0),c.y);
}
float hueDistance(float a,float b){float d=abs(a-b);return min(d,1.0-d);}
void main(){
    ivec2 p=ivec2(gl_FragCoord.xy); vec3 c=getp(p);
    if(ENABLED==0){Output=c;return;}
    float y=lum(c); vec3 sum3=vec3(0.0),sum5=vec3(0.0); float w3=0.0,w5=0.0;
    for(int j=-2;j<=2;j++)for(int i=-2;i<=2;i++){
        vec3 s=getp(p+ivec2(i,j)); float d=float(i*i+j*j);
        float w=exp(-d/5.0); sum5+=s*w;w5+=w;
        if(abs(i)<=1&&abs(j)<=1){float q=exp(-d/2.0);sum3+=s*q;w3+=q;}
    }
    vec3 b3=sum3/w3,b5=sum5/w5; float y3=lum(b3),y5=lum(b5);
    // Single-pixel noise: replace only an isolated outlier, never a coherent edge.
    float neighbours=0.25*(lum(getp(p+ivec2(1,0)))+lum(getp(p-ivec2(1,0)))+
            lum(getp(p+ivec2(0,1)))+lum(getp(p-ivec2(0,1))));
    float impulse=smoothstep(0.035,0.14,abs(y-neighbours))*SINGLE_PIXEL;
    c=mix(c,b3,impulse); y=lum(c);
    // LCC radial flat-field correction and independent edge colour casts.
    vec2 uv=(gl_FragCoord.xy/vec2(SIZE)-0.5)*2.0; float r2=dot(uv,uv);
    c*=1.0+LCC*r2*0.45;
    c.r*=1.0+LCC_RED*r2*0.18; c.b*=1.0+LCC_BLUE*r2*0.18;
    y=lum(c);
    // Mid-frequency clarity and fine structure are deliberately independent.
    float tonalProtect=smoothstep(0.02,0.18,y)*(1.0-smoothstep(0.82,0.99,y));
    float delta=(y-y5)*CLARITY*0.85+(y-y3)*STRUCTURE*0.65;
    c*=max(0.0,y+delta*tonalProtect)/max(y,1e-5); y=lum(c);
    // Chroma-only moire suppression preserves luminance detail.
    vec3 smoothChroma=b5-vec3(lum(b5)); vec3 currentChroma=c-vec3(y);
    float chromaMismatch=clamp(length(currentChroma-smoothChroma)*8.0,0.0,1.0);
    c=vec3(y)+mix(currentChroma,smoothChroma,MOIRE*chromaMismatch);
    // Skin uniformity targets common skin hues and retains luminance/texture.
    vec3 hsv=rgb2hsv(clamp(c,0.0,1.0));
    float skinHue=1.0-smoothstep(0.055,0.18,hueDistance(hsv.x,0.07));
    float skinGate=skinHue*smoothstep(0.08,0.25,hsv.y)*(1.0-smoothstep(0.82,1.0,hsv.y));
    vec3 localHsv=rgb2hsv(clamp(b5,0.0,1.0));
    hsv.x=mix(hsv.x,localHsv.x,SKIN*skinGate*0.65);
    hsv.y=mix(hsv.y,localHsv.y,SKIN*skinGate*0.45);
    c=hsv2rgb(hsv); y=lum(c);
    // Smooth segmented HSL color editor.
    hsv=rgb2hsv(clamp(c,0.0,1.0));
    float select=COLOR_RANGE<=0.001?0.0:1.0-smoothstep(COLOR_RANGE*0.5,COLOR_RANGE,hueDistance(hsv.x,COLOR_HUE));
    hsv.x=fract(hsv.x+COLOR_SHIFT*select*0.15+1.0);
    hsv.y=clamp(hsv.y*(1.0+COLOR_SAT*select),0.0,1.0); c=hsv2rgb(hsv); y=lum(c);
    // Three-way warm/cool balance with continuous tonal masks.
    float sw=1.0-smoothstep(0.12,0.48,y), hw=smoothstep(0.52,0.90,y);
    float mw=max(0.0,1.0-sw-hw); float warmth=SHADOWS*sw+MIDTONES*mw+HIGHLIGHTS*hw;
    c+=warmth*vec3(0.045,0.006,-0.045);
    Output=clamp(c,0.0,1.0);
}

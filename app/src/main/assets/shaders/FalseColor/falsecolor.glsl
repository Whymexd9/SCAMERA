precision highp float;
precision highp sampler2D;
uniform sampler2D InputBuffer;
uniform sampler2D SaliencyMask;
uniform int maskRotation;
out vec3 Output;
#define SIZE 1,1
#define STRENGTH 0.75
#define PURPLE 0.8
#define GREEN 0.65
#define CA_RED 0.0
#define CA_BLUE 0.0
#define HAS_SALIENCY 0

const vec3 LUMA=vec3(0.2126,0.7152,0.0722);
vec3 toLab(vec3 rgb) {
    vec3 lms=vec3(dot(rgb,vec3(.4122214708,.5363325363,.0514459929)),
                  dot(rgb,vec3(.2119034982,.6806995451,.1073969566)),
                  dot(rgb,vec3(.0883024619,.2817188376,.6299787005)));
    lms=sign(lms)*pow(abs(lms),vec3(1.0/3.0));
    return vec3(dot(lms,vec3(.2104542553,.7936177850,-.0040720468)),
                dot(lms,vec3(1.9779984951,-2.4285922050,.4505937099)),
                dot(lms,vec3(.0259040371,.7827717662,-.8086757660)));
}
vec3 fromLab(vec3 lab) {
    vec3 lms=vec3(lab.x+.3963377774*lab.y+.2158037573*lab.z,
                  lab.x-.1055613458*lab.y-.0638541728*lab.z,
                  lab.x-.0894841775*lab.y-1.2914855480*lab.z);
    lms=lms*lms*lms;
    return vec3(dot(lms,vec3(4.0767416621,-3.3077115913,.2309699292)),
                dot(lms,vec3(-1.2684380046,2.6097574011,-.3413193965)),
                dot(lms,vec3(-.0041960863,-.7034186147,1.7076147010)));
}
vec3 fetchCorrected(ivec2 p) {
    p=clamp(p,ivec2(0),ivec2(SIZE)-1);
    vec3 c=texelFetch(InputBuffer,p,0).rgb;
    if (CA_RED != 0.0 || CA_BLUE != 0.0) {
    vec2 px=1.0/vec2(SIZE),uv=(vec2(p)+.5)*px;
    vec2 radial=(uv-.5)*abs(uv-.5)*px*4.0;
    c.r=texture(InputBuffer,clamp(uv+radial*CA_RED,px*.5,1.0-px*.5)).r;
    c.b=texture(InputBuffer,clamp(uv+radial*CA_BLUE,px*.5,1.0-px*.5)).b;
    }
    return c;
}
void main() {
    ivec2 p=ivec2(gl_FragCoord.xy);
    vec3 center=fetchCorrected(p);
    if (STRENGTH<=.001) { Output=center;return; }
    vec3 lab=toLab(center);
    vec2 values[9];float low=lab.x,high=lab.x;
    for(int y=-1;y<=1;y++) for(int x=-1;x<=1;x++) {
        vec3 v=toLab(fetchCorrected(p+ivec2(x,y)));
        values[(y+1)*3+x+1]=v.yz;low=min(low,v.x);high=max(high,v.x);
    }
    // Independent median of a/b, including the centre. Input and output are
    // separate textures, unlike the donor's in-place defringe kernel.
    for(int i=1;i<9;i++) for(int j=i;j>0;j--) {
        vec2 lo=min(values[j-1],values[j]);
        values[j]=max(values[j-1],values[j]);values[j-1]=lo;
    }
    vec2 med=values[4];
    float distance=length(lab.yz-med);
    float speckle=smoothstep(.008,.035,distance);
    float hue=atan(lab.z,lab.y);
    float targeted=(hue> -1.2 && hue<.2) ? PURPLE :
                   (hue>1.8 && hue<2.8) ? GREEN : 0.0;
    float fringe=targeted*smoothstep(.04,.16,high-low)*smoothstep(.003,.02,distance);
    float amount=clamp(STRENGTH*max(speckle,fringe),0.0,1.0);
#if HAS_SALIENCY == 1
    vec2 uv=gl_FragCoord.xy/vec2(SIZE);
    if(maskRotation==90) uv=vec2(1.0-uv.y,uv.x);
    else if(maskRotation==180) uv=1.0-uv;
    else if(maskRotation==270) uv=vec2(uv.y,1.0-uv.x);
    amount*=1.0-.65*clamp(texture(SaliencyMask,uv).r,0.0,1.0);
#endif
    if(amount<.00001) { Output=center;return; }
    vec3 result=fromLab(vec3(lab.x,mix(lab.yz,med,amount)));
    // Preserve luminance and reconstructed highlight headroom. Compress only
    // negative out-of-gamut chroma; never clamp positive RGB to display white.
    float luminance=dot(center,LUMA);
    result+=vec3(luminance-dot(result,LUMA));
    float minimum=min(result.r,min(result.g,result.b));
    if(minimum<0.0 && luminance>0.0)
        result=mix(vec3(luminance),result,luminance/(luminance-minimum));
    Output=result;
}

precision highp float;
precision highp sampler2D;
uniform sampler2D InputBuffer;
uniform sampler2D Level1;
uniform sampler2D Level2;
uniform sampler2D Level3;
uniform vec3 frequencyGain;
uniform float noiseFloor;
uniform float shadowGain;
uniform float shadowMatch;
uniform float logMix;
uniform vec2 splitGain;
uniform float highlightAmount;
uniform float localAmount;
uniform float clarityAmount;
uniform float dehazeAmount;
uniform float flareLevel;
uniform float atmosphere;
out vec4 Output;
float luma(vec3 v){return dot(v,vec3(.2126,.7152,.0722));}
// Recovered five-knot frequency curve; decomposition and tuning are SCAMERA.
float detailCurve(float value,float a){
    float x=abs(value),b=max(.001,noiseFloor),d=max(.03,2.0*b),t=d,r=.25;
    float m=a<1.0?min(2.0*a,1.0):max(a/2.0,1.0);
    vec2 p0=vec2(0),p1=vec2(b,b*m),p2=vec2(d,d*a);
    vec2 p3=vec2(d+t,d+t+r*(a-1.0)*d),p4=p3+vec2(1);
    vec2 lo=p0,hi=p1;
    if(x>b){lo=p1;hi=p2;}if(x>d){lo=p2;hi=p3;}if(x>d+t){lo=p3;hi=p4;}
    return sign(value)*(lo.y+(x-lo.x)*(hi.y-lo.y)/max(hi.x-lo.x,1e-6));
}
void main(){
    ivec2 p=ivec2(gl_FragCoord.xy);
    vec4 original=texelFetch(InputBuffer,p,0);
    vec3 c=max(original.rgb,vec3(0));
    vec2 uv=(vec2(p)+.5)/vec2(textureSize(InputBuffer,0));
    float l=luma(c),a=luma(max(texture(Level1,uv).rgb,vec3(0)));
    float b=luma(max(texture(Level2,uv).rgb,vec3(0))),d=luma(max(texture(Level3,uv).rgb,vec3(0)));
    float scale=max(l,.02);
    vec3 band=vec3(l-a,a-b,b-d)/scale;
    float detail=0.0;
    for(int i=0;i<3;i++)detail+=detailCurve(band[i],frequencyGain[i])-band[i];
    float refined=max(0.0,l+detail*scale);
    if(l>1e-7)c*=refined/l;
    // Veiling-flare pedestal, explicitly user-controlled. No ghost removal.
    c=max(c-vec3(flareLevel),vec3(0));
    // Atmospheric-scattering approximation from a coarse dark channel.
    if(dehazeAmount>0.0){
        vec3 low=max(texture(Level3,uv).rgb,vec3(0));
        float dark=min(low.r,min(low.g,low.b));
        float transmission=max(.35,1.0-.75*dehazeAmount*dark/max(atmosphere,.05));
        c=max((c-vec3(atmosphere))/transmission+vec3(atmosphere),vec3(0));
    }
    float now=luma(c);
    float shadow=1.0-smoothstep(.04,.4,now);
    float localLog=log(max(a,1e-5)/max(d,1e-5));
    float clarityLog=log(max(b,1e-5)/max(d,1e-5));
    c*=exp(clamp(localAmount*localLog+clarityAmount*clarityLog,-.5,.5));
    // Separate rolloff and digital parts of shadow gain. The local rolloff
    // response is an integration choice, not the unrecovered full SLM kernel.
    vec3 roll=c*splitGain.x/(vec3(1)+max(splitGain.x-1.0,0.0)*c);
    vec3 variant=roll*splitGain.y;
    float amount=clamp(shadowMatch*shadow,0.0,1.0);
    vec3 linear=mix(c,variant,amount);
    // Original positive-domain SLM geometric blend; preserve exact black.
    vec3 geometric=exp(mix(log(max(c,vec3(1e-20))),log(max(variant,vec3(1e-20))),amount));
    c=mix(linear,geometric,logMix);
    if(now<=1e-20)c=vec3(0);
    // Soft luminance shoulder preserves colour ratios and values above 1.
    float y=luma(c),above=max(y-.8,0.0);
    float target=min(y,.8)+above/(1.0+highlightAmount*above);
    if(y>1e-7)c*=target/y;
    Output=vec4(clamp(c,0.0,65504.0),original.a);
}

#version 300 es
precision highp float;
precision highp sampler2D;
precision highp int;
precision highp usampler2D;
uniform usampler2D RawBuffer;
uniform sampler2D GainMap;
uniform ivec2 size;
uniform ivec2 phase;
uniform ivec4 quadColors;
uniform float blackLevel;
uniform float whiteLevel;
uniform float gainB;
uniform float gainR;
out vec4 Output;
void main() {
    ivec2 p=ivec2(gl_FragCoord.xy), sub=(p+phase)%4, r=(p+phase)%8;
    int q=r.y/4*2+r.x/4;
    ivec2 nodes=textureSize(GainMap,0)/4;
    vec2 pos=(vec2(p)+0.5)/vec2(size)*vec2(nodes-1);
    ivec2 lo=ivec2(floor(pos)), hi=min(lo+1,nodes-1);
    vec2 f=fract(pos);
    // Interpolate the same photosite phase only; never blend adjacent phases.
    float a=texelFetch(GainMap,lo*4+sub,0)[q];
    float b=texelFetch(GainMap,ivec2(hi.x,lo.y)*4+sub,0)[q];
    float c=texelFetch(GainMap,ivec2(lo.x,hi.y)*4+sub,0)[q];
    float d=texelFetch(GainMap,hi*4+sub,0)[q];
    float gain=mix(mix(a,b,f.x),mix(c,d,f.x),f.y);
    float v=(float(texelFetch(RawBuffer,p,0).r)-blackLevel)/max(whiteLevel-blackLevel,1.0);
    v=clamp(v*gain,0.0,1.0);
    int color=quadColors[q];
    Output=vec4(v*(color==0?gainR:color==2?gainB:1.0),0.0,0.0,1.0);
}

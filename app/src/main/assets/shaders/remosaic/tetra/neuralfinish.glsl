#version 300 es
precision highp float;
precision highp sampler2D;
precision highp int;
uniform sampler2D NeuralBuffer;
uniform ivec4 quadColors;
uniform float blackLevel;
uniform float whiteLevel;
uniform float gainR;
uniform float gainB;
out uvec4 Output;
void main(){
    ivec2 p=ivec2(gl_FragCoord.xy);
    int c=quadColors[(p.y%2)*2+p.x%2];
    float v=texelFetch(NeuralBuffer,p,0).r;
    v/=max(c==0?gainR:c==2?gainB:1.0,1e-6);
    float raw=clamp(v,0.0,1.0)*max(whiteLevel-blackLevel,1.0)+blackLevel;
    Output=uvec4(uint(clamp(raw+0.5,0.0,65535.0)),0u,0u,0u);
}

#version 300 es
precision highp float;
precision highp sampler2D;
precision highp int;
precision highp usampler2D;
uniform usampler2D RawBuffer;
uniform ivec2 size;
uniform ivec2 phase;
uniform ivec4 quadColors;
uniform float blackLevel;
uniform float whiteLevel;
uniform float gainB;
uniform float gainR;
out vec4 Output;
void main() {
    ivec2 base=ivec2(gl_FragCoord.xy)*32;
    vec4 sums=vec4(0.0);
    for(int y=0;y<32;++y)for(int x=0;x<32;++x) {
        ivec2 p=base+ivec2(x,y);
        if(any(greaterThanEqual(p,size)))continue;
        ivec2 r=(p+phase)%8;int c=quadColors[(r.y/4)*2+r.x/4];
        float v=clamp((float(texelFetch(RawBuffer,p,0).r)-blackLevel)/max(whiteLevel-blackLevel,1.0),0.0,1.0);
        v*=c==0?gainR:c==2?gainB:1.0;
        if(c==1){sums.x+=v*v;sums.w+=1.0;}
        else if(c==2)sums.y+=v*v;
        else sums.z+=v*v;
    }
    Output=sums;
}

#version 300 es
precision highp float;
precision highp sampler2D;
precision highp int;
uniform sampler2D RawBuffer;
uniform ivec2 size;
uniform ivec2 phase;
uniform ivec4 quadColors;
out vec4 Output;
float readAt(ivec2 p,int q) { return texelFetch(RawBuffer,p,0).r; }
void main() {
    ivec2 cell = ivec2(gl_FragCoord.xy);
    vec4 means = vec4(0.0);
    for(int q=0;q<4;q++) {
        if(quadColors[q]==1)continue;
        ivec2 origin=cell*8+ivec2(q%2,q/2)*4-phase;
        float sum=0.0,count=0.0;
        for(int axis=0;axis<2;axis++)for(int side=-1;side<=1;side+=2) {
            ivec2 normal=axis==0?ivec2(side,0):ivec2(0,side);
            ivec2 tangent=axis==0?ivec2(0,1):ivec2(1,0);
            for(int k=0;k<3;k++) {
                ivec2 p=origin+(axis==0?ivec2(side>0?3:0,k):ivec2(k,side>0?3:0));
                ivec2 g=p+normal;
                ivec2 lo=min(p-normal,min(g+normal,min(p+tangent,g+tangent)));
                ivec2 hi=max(p-normal,max(g+normal,max(p+tangent,g+tangent)));
                lo=min(lo,min(p,g));hi=max(hi,max(p,g));
                if(any(lessThan(lo,ivec2(0)))||any(greaterThanEqual(hi,size)))continue;
                ivec2 rg=(g+phase)%8;int gq=rg.y/4*2+rg.x/4;
                if(quadColors[gq]!=1)continue;
                float c0=readAt(p,q),g0=readAt(g,gq);
                sum+=(c0-readAt(p-normal,q))*(readAt(g+normal,gq)-g0);
                sum+=(readAt(p+tangent,q)-c0)*(readAt(g+tangent,gq)-g0);
                count+=2.0;
            }
        }
        means[q]=count>0.0?sum/count:0.0;
    }
    Output = means;
}

#version 300 es
precision highp float;
precision highp sampler2D;
precision highp int;
uniform sampler2D InputBuffer;
uniform ivec2 size;
uniform ivec2 phase;
out vec4 Output;
void main() {
    ivec2 xy=ivec2(gl_FragCoord.xy);
    vec4 sum=vec4(0.0);
    for(int q=0;q<4;++q) {
        ivec2 offset=ivec2(q%2,q/2)*4;
        ivec2 first=max(ivec2(0),(phase-offset+ivec2(4))/8);
        ivec2 last=max((size-1+phase-offset)/8,first);
        float total=0.0,weight=0.0;
        for(int y=-3;y<=3;++y)for(int x=-3;x<=3;++x) {
            ivec2 p=clamp(xy+ivec2(x,y),first,last);
            float w=1.0;
            total+=w*texelFetch(InputBuffer,p,0)[q];weight+=w;
        }
        sum[q]=total/weight;
    }
    Output=sum;
}

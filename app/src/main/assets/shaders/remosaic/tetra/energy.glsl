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
    for (int q = 0; q < 4; ++q) {
        float sum = 0.0, count = 0.0;
        ivec2 origin = cell * 8 + ivec2(q % 2, q / 2) * 4 - phase;
        for (int y = 0; y < 4; ++y) for (int x = 0; x < 4; ++x) {
            ivec2 p=origin+ivec2(x,y);
            if(any(lessThan(p,ivec2(0)))||any(greaterThanEqual(p,size)))continue;
            float v=readAt(p,q);
            for(int axis=0;axis<2;++axis) {
                ivec2 d=axis==0?ivec2(1,0):ivec2(0,1);
                if((axis==0?x:y)==3 || any(greaterThanEqual(p+d,size)))continue;
                float delta=readAt(p+d,q)-v;
                sum+=delta*delta;count+=1.0;
            }
        }
        means[q]=count>0.0?sum/count:0.0;
    }
    Output = means;
}

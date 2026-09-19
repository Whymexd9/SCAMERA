#define LAYOUT //
LAYOUT
precision highp float;
layout(rgba16f,binding=0) readonly uniform highp image2D inputMask;
layout(rgba16f,binding=1) readonly uniform highp image2D originalConfidence;
layout(rgba16f,binding=2) writeonly uniform highp image2D outputMask;
uniform int stage;
float readMask(ivec2 p) {return imageLoad(inputMask,clamp(p,ivec2(0),imageSize(inputMask)-1)).r;}
void main() {
    ivec2 p=ivec2(gl_GlobalInvocationID.xy);
    if(any(greaterThanEqual(p,imageSize(outputMask))))return;
    float value=0.0;
    if(stage==0) {
        vec4 c=imageLoad(inputMask,p);
        value=min(min(c.r,c.g),min(c.b,c.a))>.5?1.0:0.0;
    } else if(stage<=3) {
        for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++)value+=readMask(p+ivec2(x,y))/9.0;
    } else {
        // Original sigma=1 specialization: N=trunc(6*sigma)=6, weight centre
        // floor((N-1)/2)=2, sample centre floor(N/2)=3 (even-kernel asymmetry).
        float sum=0.0;
        for(int i=0;i<6;i++) {
            float w=exp(-.5*float((i-2)*(i-2)));
            ivec2 d=stage==4?ivec2(i-3,0):ivec2(0,i-3);
            float v=readMask(p+d);
            if(stage==4)v=1.0-step(1.0,v);
            value+=w*v;sum+=w;
        }
        value/=sum;
        if(stage==5) {
            float occlusion=floor(255.0*clamp(value,0.0,1.0));
            vec4 c=imageLoad(originalConfidence,p);
            float rejection=255.0-floor(255.0*clamp(min(min(c.r,c.g),min(c.b,c.a)),0.0,1.0));
            // Cyclops byte blend with subject=smooth=255 and g=1.
            value=floor(max(255.0-occlusion-rejection,0.0))/255.0;
        }
    }
    imageStore(outputMask,p,vec4(value));
}

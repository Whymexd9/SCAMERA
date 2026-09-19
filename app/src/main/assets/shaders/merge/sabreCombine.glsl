#define LAYOUT //
LAYOUT
precision highp float;
layout(rgba16f,binding=0) readonly uniform highp image2D referenceTexture;
layout(rgba16f,binding=1) readonly uniform highp image2D donorTexture;
layout(rgba16f,binding=2) readonly uniform highp image2D confidenceTexture;
layout(rgba16f,binding=3) readonly uniform highp image2D oldMassTexture;
layout(rgba16f,binding=4) writeonly uniform highp image2D outputTexture;
layout(rgba16f,binding=5) writeonly uniform highp image2D newMassTexture;
uniform int first;
void main() {
    ivec2 p=ivec2(gl_GlobalInvocationID.xy);
    if(any(greaterThanEqual(p,imageSize(outputTexture)))) return;
    vec4 a=imageLoad(referenceTexture,p), b=imageLoad(donorTexture,p);
    vec4 w=clamp(imageLoad(confidenceTexture,p),0.0,1.0);
    vec4 old=first==1?vec4(1):imageLoad(oldMassTexture,p);
    vec4 total=old+w;
    imageStore(outputTexture,p,(a*old+b*w)/max(total,vec4(1e-7)));
    imageStore(newMassTexture,p,total);
}

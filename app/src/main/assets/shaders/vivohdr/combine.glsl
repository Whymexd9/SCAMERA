#define LAYOUT //
LAYOUT
precision highp float;
precision highp image2D;
layout(rgba16f,binding=0) readonly uniform image2D referenceTexture;
layout(rgba16f,binding=1) readonly uniform image2D accumulatedTexture;
layout(rgba16f,binding=2) readonly uniform image2D donorTexture;
layout(rgba16f,binding=3) readonly uniform image2D confidenceTexture;
layout(rgba16f,binding=4) readonly uniform image2D oldMassTexture;
layout(rgba16f,binding=5) writeonly uniform image2D outputTexture;
layout(rgba16f,binding=6) writeonly uniform image2D newMassTexture;
uniform int first;
uniform float referenceScale;
uniform float donorScale;
uniform vec2 noiseRef;
uniform vec2 noiseAlt;
float peak(vec4 v){return max(max(v.r,v.g),max(v.b,v.a));}
void main() {
    ivec2 p=ivec2(gl_GlobalInvocationID.xy);
    if(any(greaterThanEqual(p,imageSize(outputTexture))))return;
    vec4 ref=imageLoad(referenceTexture,p), a=imageLoad(accumulatedTexture,p);
    vec4 b=imageLoad(donorTexture,p);
    float validRef=1.0-smoothstep(0.90,0.995,peak(ref)/max(referenceScale,1e-8));
    float validAlt=1.0-smoothstep(0.90,0.995,peak(b)/max(donorScale,1e-8));
    float signal=max(dot(ref,vec4(0.25)),0.0);
    float vr=max(signal*noiseRef.x+noiseRef.y,1e-10);
    float va=max(max(dot(b,vec4(0.25)),0.0)*noiseAlt.x+noiseAlt.y,1e-10);
    // Inverse-variance weighting in common exposure units. One weight per CFA
    // quad preserves colour; clipped reference pixels may be fully replaced.
    float w=clamp(imageLoad(confidenceTexture,p).r,0.0,1.0)*validAlt*clamp(vr/va,0.0625,16.0);
    vec4 state=first==1?vec4(validRef,validRef*validRef,1,0):imageLoad(oldMassTexture,p);
    float total=state.r+w, squares=state.g+w*w;
    vec4 result=total>1e-8?(a*state.r+b*w)/total:ref;
    imageStore(outputTexture,p,max(result,vec4(0)));
    imageStore(newMassTexture,p,vec4(total,squares,max(1.0,total*total/max(squares,1e-8)),1));
}

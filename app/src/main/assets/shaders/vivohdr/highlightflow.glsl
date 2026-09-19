#define LAYOUT //
LAYOUT
precision highp float;
precision highp sampler2D;
precision highp image2D;
uniform sampler2D alignmentTexture;
layout(rgba16f,binding=0) readonly uniform image2D referenceTexture;
layout(rgba16f,binding=1) readonly uniform image2D donorTexture;
layout(rgba16f,binding=2) readonly uniform image2D previousFlow;
layout(rgba16f,binding=3) writeonly uniform image2D outputFlow;
uniform ivec2 shift;
uniform ivec2 rawHalf;
uniform int stage;
uniform int tileStep;
uniform float referenceScale;
uniform float exposure;
uniform vec2 noiseRef;
uniform vec2 noiseAlt;
float peak(vec4 v){return max(max(v.r,v.g),max(v.b,v.a));}
vec2 flow(ivec2 p,ivec2 size){
    vec4 v=texelFetch(alignmentTexture,clamp(p,ivec2(0),size-1)+shift,0);
    return floor(v.xy*vec2(rawHalf)+0.5)+v.zw;
}
void main(){
    ivec2 p=ivec2(gl_GlobalInvocationID.xy),sz=imageSize(outputFlow);
    if(any(greaterThanEqual(p,sz)))return;
    if(stage==0){
        vec2 v=flow(p,sz);float spread=0.0;
        for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++)
            spread=max(spread,length(flow(p+ivec2(x,y),sz)-v));
        // One flow cell per 8 packed CFA quads (16 RAW pixels). A clipped
        // window has no observable alignment; its vectors must not become seeds.
        ivec2 rawSize=imageSize(referenceTexture);
        float clipped=0.0,error=0.0;bool inside=true;
        for(int y=-2;y<=2;y++)for(int x=-2;x<=2;x++){
            ivec2 q=clamp(p*tileStep+ivec2(x,y)*max(tileStep/4,1),ivec2(0),rawSize-1);
            ivec2 a=q+ivec2(floor(v));
            inside=inside&&all(greaterThanEqual(a,ivec2(0)))&&all(lessThan(a,rawSize));
            vec4 r=imageLoad(referenceTexture,q),d=imageLoad(donorTexture,clamp(a,ivec2(0),rawSize-1));
            clipped=max(clipped,step(0.90*referenceScale,peak(r)));
            vec4 diff=d*exposure-r;
            vec4 variance=max(r*noiseRef.x+noiseRef.y+d*exposure*exposure*noiseAlt.x+noiseAlt.y*exposure*exposure,vec4(1e-10));
            error+=dot(diff*diff/variance,vec4(0.25))/25.0;
        }
        float seed=clipped==0.0&&spread<=1.0&&error<9.0&&inside?1.0:0.0;
        imageStore(outputFlow,p,vec4(v,seed,clipped));return;
    }
    vec4 current=imageLoad(previousFlow,p);
    // Preserve measured vectors. Fill only the saturated hole, from neighbours
    // that agree; no blind averaging across different object motions.
    if(current.w<0.5||current.z>0.0){imageStore(outputFlow,p,current);return;}
    vec2 sum=vec2(0),lo=vec2(1e9),hi=vec2(-1e9);float mass=0.0,support=0.0;
    for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){
        ivec2 q=p+ivec2(x,y);
        if(any(lessThan(q,ivec2(0)))||any(greaterThanEqual(q,sz)))continue;
        vec4 v=imageLoad(previousFlow,q);
        if(v.z<=0.2)continue;
        lo=min(lo,v.xy);hi=max(hi,v.xy);sum+=v.xy*v.z;mass+=v.z;support=max(support,v.z);
    }
    if(mass>0.0&&length(hi-lo)<=1.0)current=vec4(sum/mass,support*0.97,1.0);
    imageStore(outputFlow,p,current);
}

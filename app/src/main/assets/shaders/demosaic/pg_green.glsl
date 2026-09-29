precision highp float;
precision mediump sampler2D;
// Pixel-grouping demosaic (GCam), step 1: green at R/B sites. Four directional
// gradients (same-colour difference x2 + green difference); the estimate along
// the smoothest direction: (3*near green + far green + centre - same colour 2 px away)/4.
// Canonical RGGB input (Bayer2Float), white-balance normalised samples.
uniform sampler2D RawBuffer;
uniform int yOffset;
uniform vec4 neutral;
out float Output;
ivec2 sz;
ivec2 mirrorPhase(ivec2 p){
    // Reflection keeps the parity, i.e. the CFA colour.
    if(p.x<0)p.x=-p.x; if(p.y<0)p.y=-p.y;
    if(p.x>=sz.x)p.x=2*(sz.x-1)-p.x; if(p.y>=sz.y)p.y=2*(sz.y-1)-p.y;
    return p;
}
float S(ivec2 p){
    p=mirrorPhase(p);
    return texelFetch(RawBuffer,p,0).r/neutral[((p.y&1)<<1)|(p.x&1)];
}
void main(){
    ivec2 xy=ivec2(gl_FragCoord.xy);
    xy+=ivec2(0,yOffset);
    sz=textureSize(RawBuffer,0);
    if(((xy.x+xy.y)&1)==1){Output=S(xy);return;}
    float c=S(xy);
    float gn=S(xy+ivec2(0,-1)),gs=S(xy+ivec2(0,1)),gw=S(xy+ivec2(-1,0)),ge=S(xy+ivec2(1,0));
    float cn=S(xy+ivec2(0,-2)),cs=S(xy+ivec2(0,2)),cw=S(xy+ivec2(-2,0)),ce=S(xy+ivec2(2,0));
    float dv=abs(gn-gs),dh=abs(gw-ge);
    float grad[4];
    grad[0]=abs(cn-c)*2.0+dv; grad[1]=abs(ce-c)*2.0+dh;
    grad[2]=abs(cw-c)*2.0+dh; grad[3]=abs(cs-c)*2.0+dv;
    int best=0;
    for(int i=1;i<4;i++)if(grad[i]<grad[best])best=i;
    float g;
    if(best==0)g=(gn*3.0+gs+c-cn)*0.25;
    else if(best==1)g=(ge*3.0+gw+c-ce)*0.25;
    else if(best==2)g=(gw*3.0+ge+c-cw)*0.25;
    else g=(gs*3.0+gn+c-cs)*0.25;
    Output=max(g,0.0);
}

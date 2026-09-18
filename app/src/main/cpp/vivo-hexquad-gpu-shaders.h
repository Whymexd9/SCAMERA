#pragma once
namespace vivo_hexquad {
// Scalar std430 buffers avoid vec3 padding. All stages use highp FP32 and the
// same sample coordinates, LUT indices and loop order as the CPU reference.
static const char* GpuCommon=R"GLSL(#version 310 es
precision highp float;
precision highp int;
layout(local_size_x=8,local_size_y=8) in;
layout(std430,binding=0) readonly buffer Raw { uint rawWords[]; };
layout(std430,binding=1) readonly buffer Coarse { float fields[]; };
layout(std430,binding=2) readonly buffer Gains { float gains[]; };
layout(std430,binding=3) buffer Guide { float guides[]; };
layout(std430,binding=4) buffer Reference { float references[]; };
layout(std430,binding=5) readonly buffer Neural { float neural[]; };
layout(std430,binding=6) readonly buffer Inverse { float inverseLut[]; };
layout(std430,binding=7) writeonly buffer Result { float result[]; };
uniform ivec2 imageSize,origin,guideOrigin,guideSize;
uniform int red,scale,fullOutput,hybrid;
uniform vec3 neutral;
uniform float black,white,luma,chroma,textureStrength,shot,variance;
int colour(ivec2 p){int q=((p.y&7)/4)*2+(p.x&7)/4;return q==0?0:q==3?2:1;}
bool inside(ivec2 p){return all(greaterThanEqual(p,ivec2(0)))&&all(lessThan(p,imageSize));}
float raw(ivec2 p){
    ivec2 s=ivec2((red&1)!=0?imageSize.x-1-p.x:p.x,(red&2)!=0?imageSize.y-1-p.y:p.y);
    int i=s.y*imageSize.x+s.x;
    uint v=(rawWords[i/2]>>uint((i&1)*16))&65535u;
    return clamp((float(v)-black)/(white-black)*gains[(p.y&7)*8+(p.x&7)],0.,1.)/neutral[colour(p)];
}
float cell(ivec2 p,int k){ivec2 sz=imageSize/8;p=clamp(p,ivec2(0),sz-1);return fields[(p.y*sz.x+p.x)*12+k];}
float at(ivec2 p,int q,int f){
    vec2 t=(vec2(p)-vec2(q%2,q/2)*4.-1.5)/8.;ivec2 i=ivec2(floor(t));vec2 a=t-vec2(i);int k=f*4+q;
    return (cell(i,k)*(1.-a.x)+cell(i+ivec2(1,0),k)*a.x)*(1.-a.y)+
           (cell(i+ivec2(0,1),k)*(1.-a.x)+cell(i+ivec2(1,1),k)*a.x)*a.y;
}
float smoothValue(float a,float b,float x){float t=clamp((x-a)/(b-a),0.,1.);return t*t*(3.-2.*t);}
vec2 cellColour(ivec2 p){float g=max(.005,.5*(cell(p,1)+cell(p,2)));return log(vec2(max(cell(p,0),.005),max(cell(p,3),.005))/g);}
vec3 axis(ivec2 p,ivec2 dir){
    float a=0.,b=0.;int da=0,db=0;
    for(int d=1;d<=8;++d){ivec2 pa=p-dir*d,pb=p+dir*d;
        if(da==0&&inside(pa)&&colour(pa)==1){a=raw(pa);da=d;}
        if(db==0&&inside(pb)&&colour(pb)==1){b=raw(pb);db=d;}
        if(da!=0&&db!=0)break;
    }
    if(da==0&&db==0)return vec3(0.);if(da==0)return vec3(b,0.,.5);if(db==0)return vec3(a,0.,.5);
    float span=float(da+db);return vec3((a*float(db)+b*float(da))/span,abs(a-b)/span,1.);
}
float green(ivec2 p){
    float v=raw(p);if(colour(p)==1)return v;int q=((p.y&7)/4)*2+(p.x&7)/4;
    float weighted=0.,weights=0.;const ivec2 dirs[4]=ivec2[4](ivec2(1,0),ivec2(0,1),ivec2(1,1),ivec2(1,-1));
    for(int k=0;k<4;++k){vec3 a=axis(p,dirs[k]);float w=a.z/((.005+a.y)*(.005+a.y));weighted+=a.x*w;weights+=w;}
    float directional=weighted/max(weights,1.e-6),bg=.5*(at(p,1,0)+at(p,2,0)),bc=at(p,q,0);
    float floorValue=4./(white-black),ratio=bg/max(bc,floorValue),confidence=min(bc,bg)/max(max(bc,bg),floorValue);
    vec2 a=cellColour(p/8-ivec2(2,0)),c=cellColour(p/8+ivec2(2,0)),d=cellColour(p/8-ivec2(0,2)),e=cellColour(p/8+ivec2(0,2));
    float edge=max(max(abs(a.x-c.x),abs(a.y-c.y)),max(abs(d.x-e.x),abs(d.y-e.y)))/.12;
    confidence/=1.+edge*edge*edge*edge;confidence*=smoothValue(floorValue,4.*floorValue,min(bc,bg));
    float eg=.5*(at(p,1,1)+at(p,2,1)),ec=at(p,q,1),nf=2./((white-black)*(white-black));
    float contrast=sqrt(max(eg-nf,0.)/max(ec-nf,nf));
    float consistency=clamp(1.-abs(log(max(contrast/max(ratio,.001),.001)))/log(1.5),0.,1.);
    confidence*=1.+(consistency-1.)*smoothValue(nf,16.*nf,max(eg,ec));
    confidence*=smoothValue(-.2,.05,at(p,q,2)/max(sqrt(eg*ec),nf));
    float detail=bg+(v-bc)*clamp(ratio,.25,4.);
    return clamp(directional+(detail-directional)*confidence,0.,1./neutral.y);
}
float guide(ivec2 p){ivec2 t=p-guideOrigin;return guides[t.y*guideSize.x+t.x];}
vec3 rgb(ivec2 p){
    float self=guide(p);int measured=colour(p);vec3 outRgb=vec3(self),sw=vec3(0.),sg=vec3(0.),sc=vec3(0.),sgg=vec3(0.),sgc=vec3(0.);
    for(int dy=-4;dy<=4;++dy)for(int dx=-4;dx<=4;++dx){
        ivec2 n=p+ivec2(dx,dy);if(!inside(n))continue;int c=colour(n);if(c==1||c==measured)continue;
        float g=guide(n),v=raw(n),dg=g-self,w=1./((1.+.2*float(dx*dx+dy*dy))*(1.+dg*dg/.0025));
        sw[c]+=w;sg[c]+=w*g;sc[c]+=w*v;sgg[c]+=w*g*g;sgc[c]+=w*g*v;
    }
    for(int c=0;c<=2;c+=2){
        if(c==measured)outRgb[c]=raw(p);
        else if(sw[c]>0.){float g=sg[c]/sw[c],v=sc[c]/sw[c],va=max(0.,sgg[c]/sw[c]-g*g);
            float slope=clamp((sgc[c]/sw[c]-g*v+.0001)/(va+.0001),0.,4.);outRgb[c]=v+slope*(self-g);}
    }
    return clamp(outRgb*neutral,0.,1.);
}
float textureConfidence(ivec2 p){
    float wp=neutral.y,mean=.5*(at(p,1,0)+at(p,2,0)),energy=.5*(at(p,1,1)+at(p,2,1));
    float quant=1./((white-black)*(white-black)*12.),noise=2.*(shot*max(0.,mean*wp)+variance+quant)/(wp*wp);
    float snr=max(0.,energy-noise)/max(energy+noise,1.e-10),signal=mean*wp;
    return smoothValue(.15,.65,snr)*smoothValue(.005,.025,signal)*(1.-smoothValue(.85,.98,signal));
}
vec4 referenceAt(ivec2 p){int i=(p.y*226+p.x)*4;return vec4(references[i],references[i+1],references[i+2],references[i+3]);}
float ivst(float v,int c){int i=v<=0.?0:v>=1.?65535:int(v*65535.);return inverseLut[c*65536+i];}
float luminance(vec3 c){return .25*c.r+.5*c.g+.25*c.b;}
vec3 mixDetail(vec3 r,vec3 n,float amount){
    if(amount==1.&&chroma==1.)return n;
    r/=neutral;n/=neutral;float yr=luminance(r),yn=luminance(n),y=yr+(yn-yr)*amount;
    return (vec3(y)+(r-yr)*(1.-chroma)+(n-yn)*chroma)*neutral;
}
)GLSL";
static const char* GpuGreen=R"GLSL(
void main(){ivec2 t=ivec2(gl_GlobalInvocationID.xy);if(any(greaterThanEqual(t,guideSize)))return;guides[t.y*guideSize.x+t.x]=green(guideOrigin+t);}
)GLSL";
static const char* GpuReference=R"GLSL(
void main(){ivec2 t=ivec2(gl_GlobalInvocationID.xy);if(any(greaterThanEqual(t,ivec2(226))))return;
    ivec2 p=clamp(origin+t-1,ivec2(0),imageSize-1);vec3 v=rgb(p);float mask=textureStrength>0.?textureConfidence(p):0.;
    int i=(t.y*226+t.x)*4;references[i]=v.r;references[i+1]=v.g;references[i+2]=v.b;references[i+3]=mask;}
)GLSL";
static const char* GpuAssembly=R"GLSL(
void main(){
    ivec2 t=ivec2(gl_GlobalInvocationID.xy);int os=fullOutput!=0?2:1,side=224*os;
    if(any(greaterThanEqual(t,ivec2(side))))return;int dst=t.y*side+t.x;ivec2 p=origin*os+t;
    if(any(greaterThanEqual(p,imageSize*os))){result[dst]=0.;return;}
    int q=(p.y&1)*2+(p.x&1),c=q==0?0:q==3?2:1,ns=288*scale;vec3 v=vec3(0.);
    if(fullOutput!=0){int i=((t.y+64)*ns+t.x+64)*3;v=vec3(ivst(neural[i],0),ivst(neural[i+1],1),ivst(neural[i+2],2));}
    else{float area=1./float(scale*scale);
        for(int dy=0;dy<scale;++dy)for(int dx=0;dx<scale;++dx){int i=(((t.y+32)*scale+dy)*ns+(t.x+32)*scale+dx)*3;
            for(int k=0;k<3;++k)v[k]+=ivst(neural[i+k],k)*area;}}
    if(hybrid!=0){vec4 r=vec4(0.);
        if(fullOutput!=0){vec2 s=(vec2(t)+.5)*.5+.5;ivec2 i=ivec2(s);vec2 f=s-vec2(i);
            for(int j=0;j<2;++j)for(int k=0;k<2;++k){float w=(k!=0?f.x:1.-f.x)*(j!=0?f.y:1.-f.y);r+=referenceAt(i+ivec2(k,j))*w;}}
        else r=referenceAt(t+1);
        v=mixDetail(r.rgb,v,luma*(1.-textureStrength*r.a));
    }
    result[dst]=v[c];
}
)GLSL";
} // namespace vivo_hexquad

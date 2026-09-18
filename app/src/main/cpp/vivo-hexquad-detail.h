#pragma once
#include <array>
#include <vector>
#include <cmath>
#include <algorithm>
#include "vivo-hexquad-parallel.h"

namespace vivo_hexquad {
using Rgb = std::array<float,3>;
inline float detailClamp(float v,float a,float b){return std::max(a,std::min(b,v));}
inline float detailSmooth(float a,float b,float x){float t=detailClamp((x-a)/(b-a),0.f,1.f);return t*t*(3-2*t);}
// Opponent components in neutral-balanced CAMERA RGB, before tone mapping.
// This is not a claim that camera primaries are sRGB or display CIE luminance.
inline float detailLuma(const Rgb& c){return .25f*c[0]+.5f*c[1]+.25f*c[2];}
inline Rgb mixDetail(const Rgb& reference,const Rgb& neural,float luma,float chroma,const Rgb& neutral){
    if(luma==1.f&&chroma==1.f)return neural; // exact existing reconstruction
    Rgb r{},n{},out{};
    for(int c=0;c<3;++c){r[c]=reference[c]/neutral[c];n[c]=neural[c]/neutral[c];}
    const float yr=detailLuma(r),yn=detailLuma(n),y=yr+(yn-yr)*luma;
    for(int c=0;c<3;++c)out[c]=(y+(r[c]-yr)*(1-chroma)+(n[c]-yn)*chroma)*neutral[c];
    // Clipping only at final Bayer encoding, as in the existing path.
    return out;
}

// CPU adaptation of our independent Tetra Detail v2, not a Vivo denoise API.
// Same first real exposure as the neural alignment reference; no sharpening,
// temporal averaging, synthetic noise or altered ISO/VST. Measured CFA samples
// remain measured. Only the missing colours are reconstructed.
// Coarse colour/energy/correlation are small (1/64 area), the guide is tile-local.
template<class Source> class TetraDetailReference {
    const Source& b;
    RowExecutor* team;
    int cw,ch;
    using Fields=std::array<float,12>;
    std::vector<Fields> field;
    float raw(int x,int y)const{return b.sample(0,x,y)/b.neutral[b.color(x,y)];}
    bool inside(int x,int y)const{return x>=0&&y>=0&&x<b.w&&y<b.h;}
    const Fields& cell(int x,int y)const{return field[size_t(std::max(0,std::min(ch-1,y)))*cw+std::max(0,std::min(cw-1,x))];}
    float at(int x,int y,int q,int f)const{
        float tx=(x-(q%2)*4-1.5f)/8.f,ty=(y-(q/2)*4-1.5f)/8.f;
        int ix=int(std::floor(tx)),iy=int(std::floor(ty));float fx=tx-ix,fy=ty-iy;int k=f*4+q;
        return (cell(ix,iy)[k]*(1-fx)+cell(ix+1,iy)[k]*fx)*(1-fy)+
               (cell(ix,iy+1)[k]*(1-fx)+cell(ix+1,iy+1)[k]*fx)*fy;
    }
    std::array<float,2> colour(int x,int y)const{
        const auto& m=cell(x,y);float g=std::max(.005f,.5f*(m[1]+m[2]));
        return {{std::log(std::max(m[0],.005f)/g),std::log(std::max(m[3],.005f)/g)}};
    }
    std::array<float,3> axis(int x,int y,int dx,int dy)const{
        float a=0,bv=0;int da=0,db=0;
        for(int d=1;d<=8;++d){
            int ax=x-dx*d,ay=y-dy*d,bx=x+dx*d,by=y+dy*d;
            if(!da&&inside(ax,ay)&&b.color(ax,ay)==1){a=raw(ax,ay);da=d;}
            if(!db&&inside(bx,by)&&b.color(bx,by)==1){bv=raw(bx,by);db=d;}
            if(da&&db)break; // later samples cannot change either nearest neighbour
        }
        if(!da&&!db)return {{0,0,0}};
        if(!da)return {{bv,0,.5f}};
        if(!db)return {{a,0,.5f}};
        float span=float(da+db);return {{(a*db+bv*da)/span,std::abs(a-bv)/span,1}};
    }
public:
    explicit TetraDetailReference(const Source& source,RowExecutor* executor=nullptr):b(source),team(executor),cw(b.w/8),ch(b.h/8),field(size_t(cw)*ch){
        independentRows(team,ch,[&](int cy){for(int cx=0;cx<cw;++cx)for(int q=0;q<4;++q){
            auto& dst=field[size_t(cy)*cw+cx];int ox=cx*8+(q%2)*4,oy=cy*8+(q/2)*4;
            float mean=0,energy=0,correlation=0;int count=0;
            for(int y=0;y<4;++y)for(int x=0;x<4;++x){float v=raw(ox+x,oy+y);mean+=v/16.f;
                if(x<3){float d=raw(ox+x+1,oy+y)-v;energy+=d*d/24.f;}
                if(y<3){float d=raw(ox+x,oy+y+1)-v;energy+=d*d/24.f;}
            }
            if(q==0||q==3)for(int a=0;a<2;++a)for(int side:{-1,1})for(int k=0;k<3;++k){
                int nx=a==0?side:0,ny=a==1?side:0,tx=a==1?1:0,ty=a==0?1:0;
                int x=ox+(a==0?(side>0?3:0):k),y=oy+(a==1?(side>0?3:0):k),gx=x+nx,gy=y+ny;
                if(!inside(x-nx,y-ny)||!inside(gx+nx,gy+ny)||!inside(x+tx,y+ty)||!inside(gx+tx,gy+ty)||!inside(gx,gy))continue;
                float c0=raw(x,y),g0=raw(gx,gy);
                correlation+=(c0-raw(x-nx,y-ny))*(raw(gx+nx,gy+ny)-g0);
                correlation+=(raw(x+tx,y+ty)-c0)*(raw(gx+tx,gy+ty)-g0);count+=2;
            }
            dst[q]=mean;dst[4+q]=energy;dst[8+q]=count?correlation/count:0;
        }});
        // Separable 7x7 box, matching the existing coarse support (no full-res blur).
        std::vector<Fields> temp(field.size());
        independentRows(team,ch,[&](int y){for(int x=0;x<cw;++x)for(int k=0;k<12;++k){float v=0;
            for(int d=-3;d<=3;++d)v+=cell(x+d,y)[k];
            temp[size_t(y)*cw+x][k]=v/7.f;}});
        field.swap(temp);
        independentRows(team,ch,[&](int y){for(int x=0;x<cw;++x)for(int k=0;k<12;++k){float v=0;
            for(int d=-3;d<=3;++d)v+=cell(x,y+d)[k];
            temp[size_t(y)*cw+x][k]=v/7.f;}});
        field.swap(temp);
    }
    float green(int x,int y)const{
        float v=raw(x,y);if(b.color(x,y)==1)return v;
        int q=(y%8/4)*2+x%8/4;
        float weighted=0,weights=0;
        for(auto dir:std::array<std::array<int,2>,4>{{{{1,0}},{{0,1}},{{1,1}},{{1,-1}}}}){
            auto a=axis(x,y,dir[0],dir[1]);float w=a[2]/((.005f+a[1])*(.005f+a[1]));weighted+=a[0]*w;weights+=w;
        }
        float directional=weighted/std::max(weights,1.e-6f),bg=.5f*(at(x,y,1,0)+at(x,y,2,0)),bc=at(x,y,q,0);
        float floor=4.f/(b.white-b.black),ratio=bg/std::max(bc,floor);
        float confidence=std::min(bc,bg)/std::max(std::max(bc,bg),floor);
        auto a=colour(x/8-2,y/8),c=colour(x/8+2,y/8),d=colour(x/8,y/8-2),e=colour(x/8,y/8+2);
        float edge=std::max(std::max(std::abs(a[0]-c[0]),std::abs(a[1]-c[1])),std::max(std::abs(d[0]-e[0]),std::abs(d[1]-e[1])))/.12f;
        confidence/=1+edge*edge*edge*edge;
        confidence*=detailSmooth(floor,4*floor,std::min(bc,bg));
        float eg=.5f*(at(x,y,1,1)+at(x,y,2,1)),ec=at(x,y,q,1),nf=2.f/((b.white-b.black)*(b.white-b.black));
        float contrast=std::sqrt(std::max(eg-nf,0.f)/std::max(ec-nf,nf));
        float consistency=detailClamp(1-std::abs(std::log(std::max(contrast/std::max(ratio,.001f),.001f)))/std::log(1.5f),0.f,1.f);
        confidence*=1+(consistency-1)*detailSmooth(nf,16*nf,std::max(eg,ec));
        confidence*=detailSmooth(-.2f,.05f,at(x,y,q,2)/std::max(std::sqrt(eg*ec),nf));
        float detail=bg+(v-bc)*detailClamp(ratio,.25f,4.f);
        return detailClamp(directional+(detail-directional)*confidence,0.f,1.f/b.neutral[1]);
    }
    // Average same-colour G differences over the existing coarse support.
    // Independent noise contributes 2*sigma^2 to squared neighbour differences.
    // Use the physical ISO profile, never the user's experimental VST multiplier:
    // turning that knob down must not relabel sensor noise as real texture.
    float textureConfidence(int x,int y,float shot,float variance)const{
        const float wp=b.neutral[1];
        const float mean=.5f*(at(x,y,1,0)+at(x,y,2,0));
        const float energy=.5f*(at(x,y,1,1)+at(x,y,2,1));
        const float quant=1.f/((b.white-b.black)*(b.white-b.black)*12.f);
        const float noise=2.f*(shot*std::max(0.f,mean*wp)+variance+quant)/(wp*wp);
        const float snr=std::max(0.f,energy-noise)/std::max(energy+noise,1.e-10f);
        const float signal=mean*wp;
        return detailSmooth(.15f,.65f,snr)*detailSmooth(.005f,.025f,signal)*
               (1.f-detailSmooth(.85f,.98f,signal));
    }
    struct Tile{int x,y,w,h;std::vector<float> guide;};
    Tile tile(int ox,int oy,int core,int pad=4)const{
        Tile t{std::max(0,ox-pad),std::max(0,oy-pad),0,0,{}};
        t.w=std::min(b.w,ox+core+pad)-t.x;t.h=std::min(b.h,oy+core+pad)-t.y;t.guide.resize(size_t(t.w)*t.h);
        independentRows(team,t.h,[&](int y){for(int x=0;x<t.w;++x)t.guide[size_t(y)*t.w+x]=green(t.x+x,t.y+y);});
        return t;
    }
    Rgb rgb(const Tile& t,int x,int y)const{
        float self=t.guide[size_t(y-t.y)*t.w+x-t.x];int measured=b.color(x,y);
        Rgb out{{self,self,self}};
        std::array<float,3> sw{},sg{},sc{},sgg{},sgc{};
        for(int dy=-4;dy<=4;++dy)for(int dx=-4;dx<=4;++dx){
            int px=x+dx,py=y+dy;if(!inside(px,py))continue;int c=b.color(px,py);if(c==1||c==measured)continue;
            float g=t.guide[size_t(py-t.y)*t.w+px-t.x],v=raw(px,py),dg=g-self;
            float w=1.f/((1+.2f*(dx*dx+dy*dy))*(1+dg*dg/.0025f));
            sw[c]+=w;sg[c]+=w*g;sc[c]+=w*v;sgg[c]+=w*g*g;sgc[c]+=w*g*v;
        }
        for(int c:{0,2}){
            if(c==measured)out[c]=raw(x,y);
            else if(sw[c]>0){float g=sg[c]/sw[c],v=sc[c]/sw[c],variance=std::max(0.f,sgg[c]/sw[c]-g*g);
                float slope=detailClamp((sgc[c]/sw[c]-g*v+.0001f)/(variance+.0001f),0.f,4.f);out[c]=v+slope*(self-g);}
        }
        for(int c=0;c<3;++c)out[c]=detailClamp(out[c]*b.neutral[c],0.f,1.f);
        return out;
    }
};
} // namespace vivo_hexquad

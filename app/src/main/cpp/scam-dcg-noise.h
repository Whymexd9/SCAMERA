#pragma once
// P320 DCG (dual conversion gain: one exposure read at a high and a low conversion gain, fused into one linear frame) noise model.
// The shot noise is the same in both reads (the same electrons, the LCG read brought to HCG units), the read noise is not: the HCG
// read below the knee (where the HCG ADC saturates), the much larger LCG read (x gain ratio, plus its quantisation) above it. Owner's
// rule: never one noise curve over the whole range of a DCG frame. Fit on the flat blocks of the worker's noise check (block mean m,
// temporal noise variance of one frame, normalised units (v - black) / (white - black)):
//   var = S m + (m < T ? Oh : Ol)
// one shared slope S, two offsets; knees T scanned over the quantiles of the block means (each side >= kDcgMinSide blocks), weighted
// least squares (weights 1 / model^2: the relative error, as a block variance of n samples has a relative spread sqrt(2 / n)), two
// robust passes trimming relative residuals beyond 3 MAD (x 1.4826: 3 sigma of a normal residual). Two zones only when they cut the
// weighted residual by >= 20 % against one line (S, O) fitted the same way (both on the blocks both fits kept) and Ol > 1.3 Oh; else
// one zone (Oh = Ol = O, T = 0). Fewer than kDcgMinBlocks flat blocks: not measured. Pure C++ (worker noise check,
// tools/check_dcg_noise.cpp).
#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <vector>

namespace scam {

constexpr int kDcgMinBlocks=100,kDcgMinSide=30;

struct DcgNoiseFit {
    bool measured=false;      // false: fewer than kDcgMinBlocks usable blocks or no positive slope (the caller keeps its model)
    int zones=0;              // 1 or 2 when measured
    double slope=0,hcg=0,lcg=0,knee=0; // the model; one zone: lcg = hcg, knee = 0
    long blocks=0,used=0;     // usable blocks given, inliers of the chosen fit
    double oneSlope=0,oneOffset=0; // the one-line fit (report)
    double twoSlope=0,twoHcg=0,twoLcg=0,twoKnee=0; // the best two-zone fit, accepted or not (report; knee 0 = none)
    double resOne=0,resTwo=0; // mean squared relative residual of the one line / the best two zones on the blocks both kept
    const char* why="";       // not measured: why; one zone: which test refused two
};

namespace dcg_detail {
// var = s m + (m < t ? oh : ol); t = +inf: one line (oh everywhere)
struct Model {
    double s=0,oh=0,ol=0,t=std::numeric_limits<double>::infinity();bool ok=false;
    double at(double m) const {return s*m+(m<t?oh:ol);}
};
inline double median(std::vector<double>& v){
    if(v.empty())return 0;
    const size_t k=v.size()/2;std::nth_element(v.begin(),v.begin()+long(k),v.end());
    return v[k];
}
// The model as the weight divisor: never below a tenth of the block's own variance (a fitted offset of 0 under the darkest blocks
// would give them weights without bound)
inline double modelFloor(const Model& m,double mean,double var){return std::max({m.at(mean),0.1*var,1e-30});}
// Weighted least squares of var = s m + oh [m < t] + ol [m >= t] (ol only when two) on the blocks in use, weights w. An offset
// below 0 is fixed at 0 and the rest fitted again (non-negative read noise); s <= 0 or a singular system: not ok.
inline Model solve(const std::vector<std::array<double,2>>& b,const std::vector<char>& use,const std::vector<double>& w,double t,bool two){
    Model out;out.t=two?t:std::numeric_limits<double>::infinity();
    bool fixed[3]={false,false,!two};
    for(int round=0;round<3;++round){
        int idx[3],k=0;
        for(int p=0;p<3;++p)if(!fixed[p])idx[k++]=p;
        double A[3][4]{};
        for(size_t i=0;i<b.size();++i){
            if(!use[i])continue;
            const double m=b[i][0],v=b[i][1],x[3]={m,m<out.t?1.0:0.0,m<out.t?0.0:1.0};
            for(int r=0;r<k;++r){
                for(int c=0;c<k;++c)A[r][c]+=w[i]*x[idx[r]]*x[idx[c]];
                A[r][k]+=w[i]*x[idx[r]]*v;
            }
        }
        for(int c=0;c<k;++c){ // Gauss-Jordan with partial pivoting (k <= 3)
            int p=c;
            for(int r=c+1;r<k;++r)if(std::abs(A[r][c])>std::abs(A[p][c]))p=r;
            if(!(std::abs(A[p][c])>1e-300))return out;
            if(p!=c)for(int j=0;j<=k;++j)std::swap(A[p][j],A[c][j]);
            for(int r=0;r<k;++r){
                if(r==c)continue;
                const double f=A[r][c]/A[c][c];
                for(int j=c;j<=k;++j)A[r][j]-=f*A[c][j];
            }
        }
        double q[3]={0,0,0};
        for(int r=0;r<k;++r)q[idx[r]]=A[r][k]/A[r][r];
        bool again=false;
        for(int p=1;p<3;++p)if(!fixed[p]&&q[p]<0){fixed[p]=true;again=true;}
        if(again)continue;
        if(!(q[0]>0)||!std::isfinite(q[0])||!std::isfinite(q[1])||!std::isfinite(q[2]))return out;
        out.s=q[0];out.oh=q[1];out.ol=two?q[2]:q[1];out.ok=true;
        return out;
    }
    return out;
}
// Iteratively reweighted, `rounds` fits: weights 1 / model^2 from the fit before (without a start, the first from the blocks' own variances)
inline Model irls(const std::vector<std::array<double,2>>& b,const std::vector<char>& use,double t,bool two,const Model* start,int rounds){
    std::vector<double> w(b.size());
    Model m;if(start)m=*start;
    for(int it=0;it<rounds;++it){
        for(size_t i=0;i<b.size();++i){
            const double d=m.ok?modelFloor(m,b[i][0],b[i][1]):std::max(b[i][1],1e-30);
            w[i]=1.0/(d*d);
        }
        const Model n=solve(b,use,w,t,two);
        if(!n.ok)return n;
        m=n;
    }
    return m;
}
// The fit with its two robust passes; keep = the inliers of the last pass. res = the knee scan's score: the mean over ALL blocks of the
// squared relative residual, a trimmed block counted at the trim threshold (the inliers' mean alone let a wrong knee trim the blocks
// it puts in the wrong zone and score as well as the right one). Two zones: each side keeps at least kDcgMinSide blocks, else not ok.
// start: the first weights (the one-line fit for a knee candidate), else the blocks' own variances.
inline Model robust(const std::vector<std::array<double,2>>& b,double t,bool two,std::vector<char>& keep,double& res,const Model* start=nullptr){
    keep.assign(b.size(),1);res=0;double thr=0;
    auto sidesOk=[&]{
        if(!two)return true;
        long lo=0,hi=0;
        for(size_t i=0;i<b.size();++i)if(keep[i])++(b[i][0]<t?lo:hi);
        return lo>=kDcgMinSide&&hi>=kDcgMinSide;
    };
    if(!sidesOk())return Model{};
    Model m=irls(b,keep,t,two,start,start?3:4);
    std::vector<double> r(b.size()),d;
    for(int pass=0;pass<2&&m.ok;++pass){
        for(size_t i=0;i<b.size();++i){const double M=std::max(m.at(b[i][0]),1e-30);r[i]=(b[i][1]-M)/M;}
        d=r;const double med=median(d);
        for(size_t i=0;i<b.size();++i)d[i]=std::abs(r[i]-med);
        thr=std::max(3.0*1.4826*median(d),1e-9);
        for(size_t i=0;i<b.size();++i)keep[i]=std::abs(r[i]-med)<=thr;
        if(!sidesOk())return Model{};
        m=irls(b,keep,t,two,&m,2);
    }
    if(!m.ok)return m;
    const double cap=std::max(thr*thr,1e-6); // a noiseless burst (MAD 0) still pays for every block it trims
    for(size_t i=0;i<b.size();++i){
        const double M=std::max(m.at(b[i][0]),1e-30),e=(b[i][1]-M)/M;
        res+=keep[i]?std::min(e*e,cap):cap;
    }
    res=b.empty()?0:res/double(b.size());
    return m;
}
} // namespace dcg_detail

// blocks: (mean, temporal noise variance) of the flat blocks; non-finite or non-positive entries are skipped
inline DcgNoiseFit dcgNoiseFit(const std::vector<std::array<float,2>>& blocksIn){
    using namespace dcg_detail;
    DcgNoiseFit fit;
    std::vector<std::array<double,2>> b;b.reserve(blocksIn.size());
    for(const auto& p:blocksIn)if(std::isfinite(p[0])&&std::isfinite(p[1])&&p[0]>0&&p[1]>0)b.push_back({double(p[0]),double(p[1])});
    std::sort(b.begin(),b.end());
    fit.blocks=long(b.size());
    if(fit.blocks<kDcgMinBlocks){fit.why="fewer than 100 flat blocks";return fit;}
    if(b.size()>4000){ // every s-th block by level, at most 4000 (a 50 MP plain Bayer frame has ~12000 blocks): the knee scan's cost
        const size_t s=(b.size()+3999)/4000;size_t o=0;
        for(size_t i=0;i<b.size();i+=s)b[o++]=b[i];
        b.resize(o);
    }
    std::vector<char> keep1,keep2,keepT;double res1=0,resT=0;
    const Model one=robust(b,0,false,keep1,res1);
    if(!one.ok){fit.why="no positive slope";return fit;}
    fit.oneSlope=one.s;fit.oneOffset=one.oh;
    // knees between neighbouring block means (k blocks below): 24 quantiles, then around the best at a quarter of the step down to
    // every block (the score falls toward the true knee by the blocks a wrong one puts in the wrong zone)
    const long n=long(b.size()),lo=kDcgMinSide,hi=n-kDcgMinSide;
    Model best;double bestRes=0;long bestK=-1;
    std::vector<char> tried(size_t(n)+1,0);
    auto tryKnee=[&](long k){
        if(k<lo||k>hi||tried[size_t(k)]||!(b[size_t(k)][0]>b[size_t(k-1)][0]))return;
        tried[size_t(k)]=1;
        const double t=0.5*(b[size_t(k-1)][0]+b[size_t(k)][0]);
        const Model m=robust(b,t,true,keepT,resT,&one);
        if(m.ok&&(!best.ok||resT<bestRes)){best=m;bestRes=resT;bestK=k;keep2=keepT;}
    };
    long step=std::max<long>(1,(hi-lo)/24);
    for(long k=lo;k<=hi;k+=step)tryKnee(k);
    tryKnee(hi);
    while(best.ok&&step>1){
        const long at=bestK,fine=std::max<long>(1,step/4);
        for(long k=std::max(lo,at-step);k<=std::min(hi,at+step);k+=fine)tryKnee(k);
        step=fine;
    }
    fit.used=0;for(char c:keep1)fit.used+=c;
    fit.slope=one.s;fit.hcg=fit.lcg=one.oh;fit.knee=0;fit.zones=1;fit.measured=true;
    if(!best.ok){fit.why="no two-zone fit";return fit;}
    fit.twoSlope=best.s;fit.twoHcg=best.oh;fit.twoLcg=best.ol;fit.twoKnee=best.t;
    // both on the blocks both fits kept: an outlier one of them trimmed counts for neither
    long both=0;
    for(size_t i=0;i<b.size();++i){
        if(!keep1[i]||!keep2[i])continue;
        const double m=b[i][0],v=b[i][1],M1=std::max(one.at(m),1e-30),M2=std::max(best.at(m),1e-30);
        fit.resOne+=((v-M1)/M1)*((v-M1)/M1);fit.resTwo+=((v-M2)/M2)*((v-M2)/M2);++both;
    }
    if(both){fit.resOne/=double(both);fit.resTwo/=double(both);}
    if(!(both>=2*kDcgMinSide)){fit.why="too few blocks kept by both fits";return fit;}
    if(!(fit.resTwo<=0.8*fit.resOne)){fit.why="two zones cut the residual by less than 20 %";return fit;}
    if(!(best.ol>1.3*best.oh)){fit.why="LCG offset not above 1.3 x the HCG offset";return fit;}
    fit.slope=best.s;fit.hcg=best.oh;fit.lcg=best.ol;fit.knee=best.t;fit.zones=2;fit.why="";
    fit.used=0;for(char c:keep2)fit.used+=c;
    return fit;
}

} // namespace scam

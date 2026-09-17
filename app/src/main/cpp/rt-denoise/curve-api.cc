#include "curve-subset.h"
#include <cmath>
#include <stdexcept>
#include <cstdio>
#include "engine.h"
extern "C" int rt_curve(const double* points,int count,float* lut,char* error,size_t errorSize) {
    try {
        if(!points||!lut||count<9||count>257||(count-1)%4||points[0]!=1)
            throw std::invalid_argument("Curve: expected type 1 and at least two x,y,left,right points");
        double prev=-1;
        for(int i=1;i<count;i+=4) {
            for(int j=0;j<4;j++) if(!std::isfinite(points[i+j])||points[i+j]<0||points[i+j]>1)
                throw std::invalid_argument("Curve values must be finite and in [0,1]");
            if(points[i]<=prev)throw std::invalid_argument("Curve x positions must increase");
            prev=points[i];
        }
        rtengine::FlatCurve curve(std::vector<double>(points,points+count),false,500);
        curve.setIdentityValue(0.);
        if(curve.isIdentity()) return 2;
        for(int i=0;i<501;i++)lut[i]=std::max(float(curve.getVal(double(i)/500)),0.01f);
        return 1;
    } catch(const std::exception& e) {
        if(error&&errorSize)std::snprintf(error,errorSize,"%s",e.what());return 0;
    }
}

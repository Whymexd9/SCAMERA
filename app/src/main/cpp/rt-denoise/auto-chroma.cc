/* Global automatic chroma dispatch adapted from RawTherapee 5.12 simpleprocess.cc.
 * Copyright RawTherapee contributors; GPL-3.0-or-later, see vendor/LICENSE.
 * Image loading is replaced by crops of our already-linear ProPhoto input. */
#include "portable.h"
#include <stdexcept>
using namespace rtengine;
void rtAutoChroma(Imagefloat& image, ImProcFunctions& ipf, procparams::ProcParams& params) {
    const int fw=image.getWidth(), fh=image.getHeight();
    int nw,nh,tw,th,sw,sh;
    ipf.Tile_calc(1024,128,2,fw,fh,nw,nh,tw,th,sw,sh);
    const int crW=std::min(sw/2,fw), crH=std::min(sh/2,fh);
    if(crW<32||crH<32) throw std::invalid_argument("Image too small for automatic chroma");
    int coordW[3]={std::min(50,fw-crW), (fw-crW)/2, std::max(0,fw-crW-50)};
    int coordH[3]={std::min(50,fh-crH), (fh-crH)/2, std::max(0,fh-crH-50)};
    float ch_M[9]={},max_r[9]={},max_b[9]={},min_r[9]={},min_b[9]={};
    float lumL[9]={},chromC[9]={},ry[9]={},sk[9]={},pcsk[9]={}; int Nb[9]={};
    const float autoNR=10.f, autoNRmax=40.f, lowdenoise=1.f; const int levaut=0;
    LUTf gamcurve(65536,0); float gam,gamthresh,gamslope;
    ipf.RGB_denoise_infoGamCurve(params.dirpyrDenoise,true,gamcurve,gam,gamthresh,gamslope);
    for(int yy=0;yy<3;yy++) for(int xx=0;xx<3;xx++) {
        Imagefloat crop(crW,crH), half((crW+1)/2,(crH+1)/2);
        for(int y=0;y<crH;y++) for(int x=0;x<crW;x++) {
            crop.r(y,x)=image.r(y+coordH[yy],x+coordW[xx]);
            crop.g(y,x)=image.g(y+coordH[yy],x+coordW[xx]);
            crop.b(y,x)=image.b(y+coordH[yy],x+coordW[xx]);
            if(!(y%2)&&!(x%2)) {
                half.r(y/2,x/2)=crop.r(y,x);half.g(y/2,x/2)=crop.g(y,x);half.b(y/2,x/2)=crop.b(y,x);
            }
        }
        float chaut=0,redaut=0,blueaut=0,maxredaut=0,maxblueaut=0,minredaut=0,minblueaut=0;
        float chromina=0,sigma=0,lumema=0,sigma_L=0,redyel=0,skinc=0,nsknc=0;int nb=0;
        ipf.RGB_denoise_info(&crop,&half,true,gamcurve,gam,gamthresh,gamslope,params.dirpyrDenoise,
            0,chaut,nb,redaut,blueaut,maxredaut,maxblueaut,minredaut,minblueaut,chromina,sigma,
            lumema,sigma_L,redyel,skinc,nsknc);
        int k=yy*3+xx;
        ch_M[k]=chaut;Nb[k]=nb;max_r[k]=maxredaut;max_b[k]=maxblueaut;
        min_r[k]=minredaut;min_b[k]=minblueaut;lumL[k]=lumema;chromC[k]=chromina;
        ry[k]=redyel;sk[k]=skinc;pcsk[k]=nsknc;
    }
                float chM = 0.f;
                float MaxR = 0.f;
                float MaxB = 0.f;
                float MinR = 100000000.f;
                float MinB = 100000000.f;
                float maxr = 0.f;
                float maxb = 0.f;
                float multip = 1.f;
                float adjustr = 1.f;
                float Max_R[9] = {0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f};
                float Max_B[9] = {0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f};
                float Min_R[9];
                float Min_B[9];
                float MaxRMoy = 0.f;
                float MaxBMoy = 0.f;
                float MinRMoy = 0.f;
                float MinBMoy = 0.f;

                if (params.icm.workingProfile == "ProPhoto")   {
                    adjustr = 1.f;
                } else if (params.icm.workingProfile == "Adobe RGB")  {
                    adjustr = 1.f / 1.3f;
                } else if (params.icm.workingProfile == "sRGB")       {
                    adjustr = 1.f / 1.3f;
                } else if (params.icm.workingProfile == "WideGamut")  {
                    adjustr = 1.f / 1.1f;
                } else if (params.icm.workingProfile == "Rec2020")  {
                    adjustr = 1.f / 1.1f;
                } else if (params.icm.workingProfile == "Beta RGB")   {
                    adjustr = 1.f / 1.2f;
                } else if (params.icm.workingProfile == "BestRGB")    {
                    adjustr = 1.f / 1.2f;
                } else if (params.icm.workingProfile == "BruceRGB")   {
                    adjustr = 1.f / 1.2f;
                }

                if (false) {
                    multip = 2.f;    //take into account gamma for TIF / JPG approximate value...not good for gamma=1
                }

                float delta[9];
                int mode = 1;
                int lissage = 0;

                for (int k = 0; k < 9; k++) {
                    float maxmax = max(max_r[k], max_b[k]);
                    ipf.calcautodn_info(ch_M[k], delta[k], Nb[k], levaut, maxmax, lumL[k], chromC[k], mode, lissage, ry[k], sk[k], pcsk[k]);
                    //  printf("ch_M=%f delta=%f\n",ch_M[k], delta[k]);
                }

                for (int k = 0; k < 9; k++) {
                    if (max_r[k] > max_b[k]) {
                        //printf("R delta=%f  koef=%f\n",delta[k],autoNRmax*multip*adjustr*lowdenoise);
                        Max_R[k] = (delta[k]) / ((autoNRmax * multip * adjustr * lowdenoise) / 2.f);
                        Min_B[k] = - (ch_M[k] - min_b[k]) / (autoNRmax * multip * adjustr * lowdenoise);
                        Max_B[k] = 0.f;
                        Min_R[k] = 0.f;
                    } else {
                        //printf("B delta=%f  koef=%f\n",delta[k],autoNRmax*multip*adjustr*lowdenoise);
                        Max_B[k] = (delta[k]) / ((autoNRmax * multip * adjustr * lowdenoise) / 2.f);
                        Min_R[k] = - (ch_M[k] - min_r[k])   / (autoNRmax * multip * adjustr * lowdenoise);
                        Min_B[k] = 0.f;
                        Max_R[k] = 0.f;
                    }
                }

                for (int k = 0; k < 9; k++) {
                    //  printf("ch_M= %f Max_R=%f Max_B=%f min_r=%f min_b=%f\n",ch_M[k],Max_R[k], Max_B[k],Min_R[k], Min_B[k]);
                    chM += ch_M[k];
                    MaxBMoy += Max_B[k];
                    MaxRMoy += Max_R[k];
                    MinRMoy += Min_R[k];
                    MinBMoy += Min_B[k];

                    if (Max_R[k] > MaxR) {
                        MaxR = Max_R[k];
                    }

                    if (Max_B[k] > MaxB) {
                        MaxB = Max_B[k];
                    }

                    if (Min_R[k] < MinR) {
                        MinR = Min_R[k];
                    }

                    if (Min_B[k] < MinB) {
                        MinB = Min_B[k];
                    }

                }

                chM /= 9;
                MaxBMoy /= 9;
                MaxRMoy /= 9;
                MinBMoy /= 9;
                MinRMoy /= 9;

                if (MaxR > MaxB) {
                    maxr = MaxRMoy + (MaxR - MaxRMoy) * 0.66f; //#std Dev
                    //  maxb=MinB;
                    maxb = MinBMoy + (MinB - MinBMoy) * 0.66f;

                } else {
                    maxb = MaxBMoy + (MaxB - MaxBMoy) * 0.66f;
                    //  maxr=MinR;
                    maxr = MinRMoy + (MinR - MinRMoy) * 0.66f;

                }

//              printf("SIMPL cha=%f red=%f bl=%f \n",chM,maxr,maxb);

                params.dirpyrDenoise.chroma = chM / (autoNR * multip * adjustr);
                params.dirpyrDenoise.redchro = maxr;
                params.dirpyrDenoise.bluechro = maxb;
    params.dirpyrDenoise.C2method="AUTO";
}

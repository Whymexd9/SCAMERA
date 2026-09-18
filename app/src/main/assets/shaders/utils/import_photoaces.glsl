float acesHable(float x) {
    float A=.15, B=.50, C=.10, D=.20, E=.02, F=.30;
    return ((x*(A*x+C*B)+D*E)/(x*(A*x+B)+D*F))-E/F;
}
float acesLottes(float x) {
    const float a=1.6, d=0.977, hdrMax=8.0, midIn=0.18, midOut=0.267;
    float ha=pow(hdrMax,a), had=pow(hdrMax,a*d);
    float ma=pow(midIn,a), mad=pow(midIn,a*d);
    float b=(-ma+ha*midOut)/((had-mad)*midOut);
    float c=(had*ma-ha*mad*midOut)/((had-mad)*midOut);
    return pow(x,a)/(pow(x,a*d)*b+c);
}
float acesUchimura(float x) {
    const float P=1.0, a=1.0, m=0.22, l=0.4, c=1.33, b=0.0;
    float l0=((P-m)*l)/a, L0=m-m/a, L1=m+(1.0-m)/a;
    float S0=m+l0, S1=m+a*l0, C2=(a*P)/(P-S1), CP=-C2/P;
    float w0=1.0-smoothstep(0.0,m,x);
    float w2=step(m+l0,x);
    float w1=1.0-w0-w2;
    float T=m*pow(max(x,0.0)/m,c)+b;
    float S=P-(P-S1)*exp(CP*(x-S0));
    float L=m+a*(x-m);
    return T*w0+L*w1+S*w2;
}
float acesTone(float x, float peakScale) {
    x=max(x,0.0);
    #if ACES_TONE_CURVE == 1
      return clamp((x*(2.51*x+.03))/(x*(2.43*x+.59)+.14),0.0,1.0);
    #elif ACES_TONE_CURVE == 2
      return max(0.0,(x-0.004))/(max(0.0,x-0.004)*6.2+1.0);
    #elif ACES_TONE_CURVE == 3
      return x/(1.0+x);
    #elif ACES_TONE_CURVE == 4
      return acesHable(x*2.0)/max(acesHable(11.2),1e-6);
    #elif ACES_TONE_CURVE == 5
      float lx=clamp((log2(max(x,1e-6))+10.0)/16.5,0.0,1.0);
      return lx*lx*(3.0-2.0*lx);
    #elif ACES_TONE_CURVE == 6
      float c=1.15*ACES_TONE_CONTRAST;
      return pow(x/(x+0.18+ACES_SHOULDER*0.5),1.0/max(c,0.1));
    #elif ACES_TONE_CURVE == 7
      float p=max(0.25,ACES_TONE_CONTRAST*1.5);
      float xp=pow(x,p);
      return xp/(xp+pow(max(ACES_MID_GRAY,0.01),p));
    #elif ACES_TONE_CURVE == 8
      return x;
    #elif ACES_TONE_CURVE == 9
      float toe=0.02+0.35*ACES_TOE;
      float sh=0.35+2.5*(1.0-ACES_SHOULDER);
      float z=pow(x,ACES_TONE_CONTRAST);
      return (z*z/(z+toe))/(1.0+z/sh);
    #elif ACES_TONE_CURVE == 10
      return acesLottes(x);
    #elif ACES_TONE_CURVE == 11
      return acesUchimura(x);
    #else
      float shoulder=(0.65+0.70*ACES_SHOULDER)+0.35*log2(peakScale);
      return (x*(1.0+x/shoulder))/(1.0+x+0.25*ACES_TOE);
    #endif
}
vec3 acesEncode(vec3 x) {
    x=max(x,vec3(0.0));
    #if ACES_GAMMA_CURVE == 1
      return pow(x,vec3(1.0/2.2));
    #elif ACES_GAMMA_CURVE == 2
      return pow(x,vec3(1.0/2.4));
    #elif ACES_GAMMA_CURVE == 3
      return x;
    #elif ACES_GAMMA_CURVE == 4
      vec3 lo=x*4.5;
      vec3 hi=1.099*pow(x,vec3(0.45))-0.099;
      return mix(hi,lo,lessThan(x,vec3(0.018)));
    #elif ACES_GAMMA_CURVE == 6
      const float m1=0.1593017578, m2=78.84375, c1=0.8359375, c2=18.8515625, c3=18.6875;
      vec3 p=pow(clamp(x,0.0,1.0),vec3(m1));
      return pow((c1+c2*p)/(1.0+c3*p),vec3(m2));
    #elif ACES_GAMMA_CURVE == 7
      vec3 lo=sqrt(3.0*x);
      vec3 hi=0.17883277*log(12.0*x-0.28466892)+0.55991073;
      return mix(hi,lo,lessThanEqual(x,vec3(1.0/12.0)));
    #elif ACES_GAMMA_CURVE == 8
      return pow(x,vec3(1.0/max(ACES_CUSTOM_GAMMA,0.1)));
    #else
      vec3 lo=x*12.92;
      vec3 hi=1.055*pow(x,vec3(1.0/2.4))-0.055;
      return mix(hi,lo,lessThanEqual(x,vec3(0.0031308)));
    #endif
}

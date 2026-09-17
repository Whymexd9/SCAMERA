#include "../app/src/main/cpp/vivo-neural-runtime.h"
#include <cassert>
using namespace vivo_nn;
struct FlatNetwork {
 std::vector<float> input=std::vector<float>(144*144*16),output=std::vector<float>(144*144*64);int calls=0;
 void execute(){
  ++calls;
  // Deterministic perfect flat-field reconstruction for testing transport,
  // orientation, tiling and rejection logic; deliberately not a neural model.
  float rgb[3];int xx[3]={256,260,260},yy[3]={256,256,260};
  for(int c=0;c<3;c++)rgb[c]=input[((yy[c]/4)*144+xx[c]/4)*16+morton(xx[c]%4,yy[c]%4,2)];
  for(int y=0;y<1152;y++)for(int x=0;x<1152;x++)output[((y/8)*144+x/8)*64+morton(x%8,y%8,3)]=rgb[color(x,y,1)];
 }
};
int main(){
 int seen[64]={};for(int y=0;y<8;y++)for(int x=0;x<8;x++){int k=morton(x,y,3);assert(k>=0&&k<64);seen[k]++;}for(int n:seen)assert(n==1);
 // Stock ARM64 post-kernel channel sequence, measured with unique values.
 const int expected[8][8]={{0,1,4,5,16,17,20,21},{2,3,6,7,18,19,22,23},{8,9,12,13,24,25,28,29},{10,11,14,15,26,27,30,31},{32,33,36,37,48,49,52,53},{34,35,38,39,50,51,54,55},{40,41,44,45,56,57,60,61},{42,43,46,47,58,59,62,63}};
 for(int y=0;y<8;y++)for(int x=0;x<8;x++)assert(morton(x,y,3)==expected[y][x]);
 for(int p=-32;p<96;p++){int cl=phaseClamp(p,64);assert(cl>=0&&cl<64&&((cl-p)%8)==0);}
 const int w=944,h=24;const float rgb[3]={.12f,.35f,.65f};
 for(int red=0;red<4;red++){
  FlatNetwork net;std::vector<float> raw(w*h);
  for(int y=0;y<h;y++)for(int x=0;x<w;x++){int xx=(red&1)?w-1-x:x,yy=(red&2)?h-1-y:y;raw[y*w+x]=rgb[color(xx,yy,4)];}
  auto out=reconstruct(net,Mapping{4,1,0},raw.data(),w,h,red);assert(net.calls==3);
  for(int y=0;y<h;y++)for(int x=0;x<w;x++){int xx=(red&1)?w-1-x:x,yy=(red&2)?h-1-y:y;assert(std::abs(out[y*w+x]-rgb[color(xx,yy,1)])<1e-5f);}
 }
 FlatNetwork net;auto m=calibrate(net);assert(m.inputBlock==4&&m.outputBlock==1&&m.error<1e-5);
 struct BadNetwork:FlatNetwork{void execute(){std::fill(output.begin(),output.end(),0);}} bad;
 bool rejected=false;try{calibrate(bad);}catch(const std::runtime_error&){rejected=true;}assert(rejected);
 std::cout<<"PASS: stock channel order, phase-preserving edges, tile coverage, four CFA orientations, calibration and wrong-model rejection\n";
}

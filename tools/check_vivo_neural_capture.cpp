#include "../app/src/main/cpp/vivo-neural-layout-diagnostics.h"
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
 // A rejected block-4 hypothesis must not prevent checking block-2.
 struct Block2Network:FlatNetwork {
  void execute(){
   auto at=[&](int x,int y){return input[((y/4)*144+x/4)*16+morton(x%4,y%4,2)];};
   float r=at(256,256),g=at(258,256),b=at(258,258);
   if(r==g && r==b && at(260,260)!=r)throw OutputError("Fixture: wrong input CFA");
   float rgb[3]={r,g,b};
   for(int y=0;y<1152;y++)for(int x=0;x<1152;x++)output[((y/8)*144+x/8)*64+morton(x%8,y%8,3)]=rgb[color(x,y,1)];
  }
 } block2;
 auto second=calibrate(block2);assert(second.inputBlock==2&&second.outputBlock==1&&second.error<1e-5);
 struct RuntimeFailure:FlatNetwork {void execute(){throw std::runtime_error("driver failure");}} runtimeFailure;
 rejected=false;try{calibrate(runtimeFailure);}catch(const OutputError&){assert(false);}catch(const std::runtime_error& e){rejected=std::string(e.what())=="driver failure";}assert(rejected);
 std::vector<float> checkOutput={0.f,.2f,1.f};validateOutput(checkOutput,1);
 for(float v:{-.251f,2.001f,std::numeric_limits<float>::infinity(),std::numeric_limits<float>::quiet_NaN()}) {
  checkOutput[1]=v;rejected=false;try{validateOutput(checkOutput,1);}catch(const OutputError&){rejected=true;}assert(rejected);
 }
 poisonOutput(checkOutput);rejected=false;try{validateOutput(checkOutput,1);}catch(const OutputError&){rejected=true;}assert(rejected);
 // Reproduce the real phone report: finite overshoot at (15,1149),
 // outside the retained tile, must not reject a good interior.
 auto xy=outputPixel(1318007);assert(xy[0]==15&&xy[1]==1149);
 auto index=[](int x,int y){return ((y/8)*144+x/8)*64+morton(x%8,y%8,3);};
 std::vector<float> tile(1152*1152,std::sqrt(.2f));
 tile[1318007]=-.401123047f;tile[index(1151,0)]=3.f;
 validateOutput(tile,9);
 // Range violations on every boundary of the protected area remain fatal.
 for(auto p:std::vector<std::array<int,2>>{{{USED_BEGIN,576}},{{USED_END-1,576}},{{576,USED_BEGIN}},{{576,USED_END-1}},{{576,576}}}) {
  auto i=index(p[0],p[1]);float old=tile[i];tile[i]=2.01f;
  rejected=false;try{validateOutput(tile,9);}catch(const OutputError&){rejected=true;}assert(rejected);tile[i]=old;
 }
 for(auto p:std::vector<std::array<int,2>>{{{USED_BEGIN-1,576}},{{USED_END,576}},{{576,USED_BEGIN-1}},{{576,USED_END}}}) {
  auto i=index(p[0],p[1]);float old=tile[i];tile[i]=-3.f;validateOutput(tile,9);tile[i]=old;
 }
 tile[1318007]=std::numeric_limits<float>::quiet_NaN();
 rejected=false;try{validateOutput(tile,9);}catch(const OutputError&){rejected=true;}assert(rejected);
 // Check the actual sampler across the full tile span for both scale factors,
 // both output CFA hypotheses, all colours and the four tile edges.
 std::fill(tile.begin(),tile.end(),std::sqrt(.2f));
 for(int scale:{1,2})for(int block:{1,2})for(int c=0;c<3;c++) {
  int span=(INPUT_TILE-2*INPUT_HALO)*scale;
  for(int p=0;p<span;p++)for(int edge:{0,span-1}) {
   float f=((p+.5f)/scale+INPUT_HALO)*2-.5f;
   float g=((edge+.5f)/scale+INPUT_HALO)*2-.5f;
   assert(std::abs(sample(tile,f,g,c,block)-.2f)<1e-5);
   assert(std::abs(sample(tile,g,f,c,block)-.2f)<1e-5);
  }
 }
 struct HaloNetwork:FlatNetwork {
  void execute(){FlatNetwork::execute();output[1318007]=-.401123047f;validateOutput(output,9);}
 } halo;
 auto haloMapping=calibrate(halo);assert(haloMapping.inputBlock==4&&haloMapping.outputBlock==1);
 // Diagnostics must retain within-phase variance: an alternating error may
 // have the correct mean but must not be mistaken for a correct reconstruction.
 for(int order=0;order<3;++order)for(int block:{1,2,4,8}) {
  std::fill(tile.begin(),tile.end(),0);
  for(int y=0;y<1152;++y)for(int x=0;x<1152;++x)
   tile[((y/8)*144+x/8)*64+LayoutEvidence::channel(x%8,y%8,order)]=std::sqrt(rgb[color(x,y,block)]);
  LayoutEvidence ev(tile,rgb);assert(ev.finite);assert(ev.rmse(rgb,order,block,0)<1e-6);
  assert(ev.rmse(rgb,order,block,3)>.1); // Red/blue swap must not pass.
 }
 for(int y=0;y<1152;++y)for(int x=0;x<1152;++x)
  tile[index(x,y)]=std::sqrt(rgb[color(x,y,1)]+(((x/16)&1)? .05f:-.05f));
 LayoutEvidence variance(tile,rgb);assert(std::abs(variance.rmse(rgb,0,1,0)-.05)<1e-6);
 tile[index(576,576)]=std::numeric_limits<float>::quiet_NaN();
 LayoutEvidence nanEvidence(tile,rgb);assert(!nanEvidence.finite);assert(std::isinf(nanEvidence.rmse(rgb,0,1,0)));
 // A failed diagnostic sweep never returns or installs a capture Mapping.
 // It runs ten colour charts and still rejects runtime/driver failures.
 struct DiagnosticBad:BadNetwork { void execute(){++calls;BadNetwork::execute();} } diagnosticBad;std::ostringstream captured;auto* old=std::cout.rdbuf(captured.rdbuf());
 diagnoseLayouts(diagnosticBad);std::cout.rdbuf(old);assert(diagnosticBad.calls==10);
 assert(captured.str().find("no capture mapping installed")!=std::string::npos);
 assert(captured.str().find("LAYOUT RANK 1")!=std::string::npos);
 rejected=false;try{diagnoseLayouts(runtimeFailure);}catch(const std::runtime_error& e){rejected=std::string(e.what())=="driver failure";}assert(rejected);
 std::cout<<"PASS: stock channel order, phase-preserving edges, tile coverage, four CFA orientations, calibration, invalid-first-layout recovery, driver-error propagation, output rejection, discarded-halo coverage and layout diagnostics\n";
}


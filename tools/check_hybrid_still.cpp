// P150: a still burst (tripod, a phone on a table) merges per site (cellClip 0); a moving burst keeps cellClip.
// The decision: donors that moved at least staticShift RAW px against the base (homography at the corners and the centre).
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <unistd.h>

using namespace scam;

static BackwardHomography shifted(float dx,float dy,float up=1.f){
    BackwardHomography h;h.h={1,0,dx*up,0,1,dy*up,0,0};h.h[0]=up;h.h[4]=up;h.upRatio=up;
    return h;
}

int main() {
    const int w=4096,h=3072;
    float largest=-1.f;
    // a tripod: every donor on the base's sites
    std::vector<BackwardHomography> still{BackwardHomography{},shifted(0,0),shifted(0.2f,-0.1f),shifted(-0.3f,0.25f)};
    int moved=hybridMovedDonors(still,w,h,0.75f,&largest);
    assert(moved==0&&largest>0.38f&&largest<0.40f);
    assert(hybridStillBurst(moved,3));
    // a hand-held burst: every donor moved
    std::vector<BackwardHomography> hand{BackwardHomography{},shifted(2.5f,-1.f),shifted(-1.2f,3.f),shifted(0.9f,0.f),shifted(-4.f,-2.f)};
    moved=hybridMovedDonors(hand,w,h,0.75f,&largest);
    assert(moved==4&&largest>4.4f);
    assert(!hybridStillBurst(moved,4));
    // one donor of eight moved (the phone touched once): still; two of eight: moving
    std::vector<BackwardHomography> once(9,BackwardHomography{});once[3]=shifted(1.5f,0.f);
    assert(hybridStillBurst(hybridMovedDonors(once,w,h,0.75f),8));
    once[6]=shifted(0.f,-2.f);
    assert(!hybridStillBurst(hybridMovedDonors(once,w,h,0.75f),8));
    // the 2x grid: the homography works in the up-scaled frame (upRatio 2), the move is in RAW px
    std::vector<BackwardHomography> up{BackwardHomography{},shifted(0.5f,0.f,2.f),shifted(1.f,0.f,2.f)};
    moved=hybridMovedDonors(up,w,h,0.75f,&largest);
    assert(moved==1&&largest>0.99f&&largest<1.01f);
    // a small rotation about the frame origin, no translation: the far corner moves most
    BackwardHomography rot;const float a=0.0005f;
    rot.h={std::cos(a),-std::sin(a),0,std::sin(a),std::cos(a),0,0,0};
    std::vector<BackwardHomography> rotated{BackwardHomography{},rot};
    moved=hybridMovedDonors(rotated,w,h,0.75f,&largest);
    assert(moved==1&&largest>2.f);
    assert(!hybridStillBurst(0,0));
    // the tuning key (0 = never)
    const char* temp=std::getenv("TMPDIR");
    std::string dir=std::string(temp?temp:"/tmp")+"/scamera-still-XXXXXX";
    assert(mkdtemp(dir.data())!=nullptr);
    {std::ofstream t(dir+"/hybrid_tuning.txt");t<<"staticShift 0\n";}
    const HybridTuning off=loadHybridTuning(dir,[](const std::string&){});
    assert(off.staticShift==0.f&&off.cellClip==1);
    assert(HybridTuning{}.staticShift==0.75f);
    unlink((dir+"/hybrid_tuning.txt").c_str());rmdir(dir.c_str());
    std::printf("hybrid still burst check PASS\n");
    return 0;
}

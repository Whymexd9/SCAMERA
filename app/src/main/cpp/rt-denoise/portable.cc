#include "portable.h"
PortableOptions options;
namespace rtengine {
static PortableSettings defaultSettings;
const PortableSettings* settings = &defaultSettings;
static MyMutex fftwLock;
MyMutex* fftwMutex = &fftwLock;
LUTf Color::cachef, Color::cachefy, Color::denoiseGammaTab, Color::denoiseIGammaTab;
LUTf Color::gammatab_srgb, Color::igammatab_srgb;
void initializeDenoiseColor() {
    static std::once_flag initialized;
    std::call_once(initialized, [] {
        Color::cachef(65536,LUT_CLIP_BELOW); Color::cachefy(65536,LUT_CLIP_BELOW);
        Color::denoiseGammaTab(65536,0); Color::denoiseIGammaTab(65536,0);
        Color::gammatab_srgb(65536,0); Color::igammatab_srgb(65536,0);
        for(int i=0;i<65536;i++) {
            Color::cachef[i] = i <= int(Color::eps_max)
                ? 327.68 * ((Color::kappa*i/MAXVALF+16.0)/116.0)
                : 327.68 * std::cbrt(double(i)/MAXVALF);
            Color::cachefy[i] = i <= int(Color::eps_max)
                ? 327.68 * (Color::kappa*i/MAXVALF)
                : 327.68 * (116.0*std::cbrt(double(i)/MAXVALF)-16.0);
            Color::denoiseGammaTab[i] = 65535.0*Color::gamma55(i/65535.0);
            Color::denoiseIGammaTab[i] = 65535.0*Color::igamma55(i/65535.0);
            Color::gammatab_srgb[i] = 65535.0*Color::gamma2(i/65535.0);
            Color::igammatab_srgb[i] = 65535.0*Color::igamma2(i/65535.0);
        }
    });
}
}

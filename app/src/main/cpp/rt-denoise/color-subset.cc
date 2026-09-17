/*
*  This file is part of RawTherapee.
*
*  Copyright (c) 2004-2010 Gabor Horvath <hgabor@rawtherapee.com>
*
*  RawTherapee is free software: you can redistribute it and/or modify
*  it under the terms of the GNU General Public License as published by
*  the Free Software Foundation, either version 3 of the License, or
*  (at your option) any later version.
*
*  RawTherapee is distributed in the hope that it will be useful,
*  but WITHOUT ANY WARRANTY; without even the implied warranty of
*  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*  GNU General Public License for more details.
*
*  You should have received a copy of the GNU General Public License
*  along with RawTherapee.  If not, see <https://www.gnu.org/licenses/>.
*/
/* Extracted from RawTherapee 5.12 color.cc; GPL-3.0-or-later. See vendor/LICENSE. */
#include "portable.h"
namespace rtengine {
void Color::gammaf2lut (LUTf &gammacurve, float gamma, float start, float slope, float divisor, float factor)
{
#ifdef __SSE2__
    // SSE2 version is more than 6 times faster than scalar version
    vfloat iv = _mm_set_ps(3.f, 2.f, 1.f, 0.f);
    vfloat fourv = F2V(4.f);
    vfloat gammav = F2V(1.f / gamma);
    vfloat slopev = F2V((slope / divisor) * factor);
    vfloat divisorv = F2V(xlogf(divisor));
    vfloat factorv = F2V(factor);
    vfloat comparev = F2V(start * divisor);
    int border = start * divisor;
    int border1 = border - (border & 3);
    int border2 = border1 + 4;
    int i = 0;

    for(; i < border1; i += 4) {
        vfloat resultv = iv * slopev;
        STVFU(gammacurve[i], resultv);
        iv += fourv;
    }

    for(; i < border2; i += 4) {
        vfloat result0v = iv * slopev;
        vfloat result1v = xexpf((xlogf(iv) - divisorv) * gammav) * factorv;
        STVFU(gammacurve[i], vself(vmaskf_le(iv, comparev), result0v, result1v));
        iv += fourv;
    }

    for(; i < 65536; i += 4) {
        vfloat resultv = xexpfNoCheck((xlogfNoCheck(iv) - divisorv) * gammav) * factorv;
        STVFU(gammacurve[i], resultv);
        iv += fourv;
    }

#else

    for (int i = 0; i < 65536; ++i) {
        gammacurve[i] = gammaf(static_cast<float>(i) / divisor, gamma, start, slope) * factor;
    }

#endif
}
void Color::gammanf2lut (LUTf &gammacurve, float gamma, float divisor, float factor)           //standard gamma without slope...
{
#ifdef __SSE2__
    // SSE2 version is more than 6 times faster than scalar version
    vfloat iv = _mm_set_ps(3.f, 2.f, 1.f, 0.f);
    vfloat fourv = F2V(4.f);
    vfloat gammav = F2V(1.f / gamma);
    vfloat divisorv = F2V(xlogf(divisor));
    vfloat factorv = F2V(factor);

    // first input value is zero => we have to use the xlogf function which checks this
    vfloat resultv = xexpf((xlogf(iv) - divisorv) * gammav) * factorv;
    STVFU(gammacurve[0], resultv);
    iv += fourv;

    // inside the loop we can use xlogfNoCheck and xexpfNoCheck because we know about the input values
    for(int i = 4; i < 65536; i += 4) {
        resultv = xexpfNoCheck((xlogfNoCheck(iv) - divisorv) * gammav) * factorv;
        STVFU(gammacurve[i], resultv);
        iv += fourv;
    }

#else

    for (int i = 0; i < 65536; ++i) {
        gammacurve[i] = Color::gammanf(static_cast<float>(i) / divisor, gamma) * factor;
    }

#endif
}
float Color::computeXYZ2Lab(float f)
{
    if (f < 0.f) {
        return 327.68 * ((kappa * f / MAXVALF + 16.0) / 116.0);
    } else if (f > 65535.f) {
        return (327.68f * xcbrtf(f / MAXVALF));
    } else {
        return cachef[f];
    }
}
void Color::RGB2Lab(float *R, float *G, float *B, float *L, float *a, float *b, const float wp[3][3], int width)
{

#ifdef __SSE2__
    const vfloat minvalfv = ZEROV;
    const vfloat maxvalfv = F2V(MAXVALF);
    const vfloat c500v = F2V(500.f);
    const vfloat c200v = F2V(200.f);
#endif
    int i = 0;
    
#ifdef __SSE2__
    for(;i < width - 3; i+=4) {
        const vfloat rv = LVFU(R[i]);
        const vfloat gv = LVFU(G[i]);
        const vfloat bv = LVFU(B[i]);
        const vfloat xv = F2V(wp[0][0]) * rv + F2V(wp[0][1]) * gv + F2V(wp[0][2]) * bv;
        const vfloat yv = F2V(wp[1][0]) * rv + F2V(wp[1][1]) * gv + F2V(wp[1][2]) * bv;
        const vfloat zv = F2V(wp[2][0]) * rv + F2V(wp[2][1]) * gv + F2V(wp[2][2]) * bv;

        if (_mm_movemask_ps((vfloat)vorm(vmaskf_gt(vmaxf(xv, vmaxf(yv, zv)), maxvalfv), vmaskf_lt(vminf(xv, vminf(yv, zv)), minvalfv)))) {
            // take slower code path for all 4 pixels if one of the values is > MAXVALF. Still faster than non SSE2 version
            for(int k = 0; k < 4; ++k) {
                float x = xv[k];
                float y = yv[k];
                float z = zv[k];
                float fx = computeXYZ2Lab(x);
                float fy = computeXYZ2Lab(y);
                float fz = computeXYZ2Lab(z);

                L[i + k] = computeXYZ2LabY(y);
                a[i + k] = (500.f * (fx - fy) );
                b[i + k] = (200.f * (fy - fz) );
            }
        } else {
            const vfloat fx = cachef[xv];
            const vfloat fy = cachef[yv];
            const vfloat fz = cachef[zv];

            STVFU(L[i], cachefy[yv]);
            STVFU(a[i], c500v * (fx - fy));
            STVFU(b[i], c200v * (fy - fz));
        }
    }
#endif
    for(;i < width; ++i) {
        const float rv = R[i];
        const float gv = G[i];
        const float bv = B[i];
        float x = wp[0][0] * rv + wp[0][1] * gv + wp[0][2] * bv;
        float y = wp[1][0] * rv + wp[1][1] * gv + wp[1][2] * bv;
        float z = wp[2][0] * rv + wp[2][1] * gv + wp[2][2] * bv;
        float fx, fy, fz;

        fx = computeXYZ2Lab(x);
        fy = computeXYZ2Lab(y);
        fz = computeXYZ2Lab(z);

        L[i] = computeXYZ2LabY(y);
        a[i] = 500.0f * (fx - fy);
        b[i] = 200.0f * (fy - fz);
    }
}
void Color::Lab2RGBLimit(float *L, float *a, float *b, float *R, float *G, float *B, const float wp[3][3], float limit, float afactor, float bfactor, int width)
{

    int i = 0;

#ifdef __SSE2__
    const vfloat wpv[3][3] = {
                              {F2V(wp[0][0]), F2V(wp[0][1]), F2V(wp[0][2])},
                              {F2V(wp[1][0]), F2V(wp[1][1]), F2V(wp[1][2])},
                              {F2V(wp[2][0]), F2V(wp[2][1]), F2V(wp[2][2])}
                             };
    const vfloat limitv = F2V(limit);
    const vfloat afactorv = F2V(afactor);
    const vfloat bfactorv = F2V(bfactor);

    for(;i < width - 3; i+=4) {
        const vfloat Lv = LVFU(L[i]);
        vfloat av = LVFU(a[i]);
        vfloat bv = LVFU(b[i]);

        const vmask mask = vmaskf_gt(SQRV(av) + SQRV(bv), limitv);
        av = vself(mask, av * afactorv, av);
        bv = vself(mask, bv * bfactorv, bv);
        vfloat Xv, Yv, Zv;
        Lab2XYZ(Lv, av, bv, Xv, Yv, Zv);
        vfloat Rv, Gv, Bv;
        xyz2rgb(Xv, Yv, Zv, Rv, Gv, Bv, wpv);
        STVFU(R[i], Rv);
        STVFU(G[i], Gv);
        STVFU(B[i], Bv);
    }
#endif
    for(;i < width; ++i) {
        float X, Y, Z;
        float av = a[i];
        float bv = b[i];
        if (SQR(av) + SQR(bv) > limit) {
            av *= afactor;
            bv *= bfactor;
        }
        Lab2XYZ(L[i], av, bv, X, Y, Z);
        xyz2rgb(X, Y, Z, R[i], G[i], B[i], wp);
    }
}
}

namespace rtengine {
void Color::XYZ2Lab(float X, float Y, float Z, float &L, float &a, float &b)
{

    float x = X / D50x;
    float z = Z / D50z;
    float y = Y;
    float fx, fy, fz;

    fx = computeXYZ2Lab(x);
    fy = computeXYZ2Lab(y);
    fz = computeXYZ2Lab(z);

    L = computeXYZ2LabY(y);
    a = (500.0f * (fx - fy) );
    b = (200.0f * (fy - fz) );
}
}

namespace rtengine {
void Color::rgbxyz (float r, float g, float b, float &x, float &y, float &z, const float xyz_rgb[3][3])
{
    x = ((xyz_rgb[0][0] * r + xyz_rgb[0][1] * g + xyz_rgb[0][2] * b)) ;
    y = ((xyz_rgb[1][0] * r + xyz_rgb[1][1] * g + xyz_rgb[1][2] * b)) ;
    z = ((xyz_rgb[2][0] * r + xyz_rgb[2][1] * g + xyz_rgb[2][2] * b)) ;
}
}

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
/* Extracted RT 5.12 Curve/FlatCurve declarations; GPL-3.0-or-later. */
#pragma once
#include <vector>
#include <cstdio>
#include "vendor/rt_math.h"
#include "vendor/flatcurvetypes.h"
#define CURVES_MIN_POLY_POINTS 1000
namespace rtengine {
class Curve
{

    class HashEntry
    {
    public:
        unsigned short smallerValue;
        unsigned short higherValue;
    };
protected:
    int N;
    int ppn;            // targeted polyline point number
    double* x;
    double* y;
    // begin of variables used in Parametric curves only
    double mc;
    double mfc;
    double msc;
    double mhc;
    // end of variables used in Parametric curves only
    std::vector<double> poly_x;     // X points of the faceted curve
    std::vector<double> poly_y;     // Y points of the faceted curve
    std::vector<double> dyByDx;
    std::vector<HashEntry> hash;
    unsigned short hashSize;        // hash table's size, between [10, 100, 1000]

    double* ypp;

    // Fields for the elementary curve polygonisation
    double x1, y1, x2, y2, x3, y3;
    bool firstPointIncluded;
    double increment;
    int nbr_points;

    void fillHash();
    void fillDyByDx();

public:
    Curve();
    virtual ~Curve() {};
    void AddPolygons();
    int getSize() const;  // return the number of control points
    void getControlPoint(int cpNum, double &x, double &y) const;
    virtual double getVal(double t) const = 0;
    virtual void   getVal(const std::vector<double>& t, std::vector<double>& res) const = 0;

    virtual bool   isIdentity() const = 0;
};

class FlatCurve final : public Curve
{

private:
    FlatCurveType kind;
    double* leftTangent;
    double* rightTangent;
    double identityValue;
    bool periodic;

    void CtrlPoints_set();

public:

    explicit FlatCurve(const std::vector<double>& points, bool isPeriodic = true, int ppn = CURVES_MIN_POLY_POINTS);
    ~FlatCurve() override;

    double getVal(double t) const override;
    void   getVal(const std::vector<double>& t, std::vector<double>& res) const override;
    bool   setIdentityValue(double iVal);
    bool   isIdentity() const override
    {
        return kind == FCT_Empty;
    };
};

}

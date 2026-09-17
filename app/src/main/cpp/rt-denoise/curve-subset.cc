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
/* Extracted from RT 5.12 curves.cc; GPL-3.0-or-later. */
#include "curve-subset.h"
namespace rtengine {
Curve::Curve() : N(0), ppn(0), x(nullptr), y(nullptr), mc(0.0), mfc(0.0), msc(0.0), mhc(0.0), hashSize(1000 /* has to be initialized to the maximum value */), ypp(nullptr), x1(0.0), y1(0.0), x2(0.0), y2(0.0), x3(0.0), y3(0.0), firstPointIncluded(false), increment(0.0), nbr_points(0) {}

void Curve::AddPolygons()
{
    if (firstPointIncluded) {
        poly_x.push_back(x1);
        poly_y.push_back(y1);
    }

    for (int k = 1; k < (nbr_points - 1); k++) {
        double t = k * increment;
        double t2 = t * t;
        double tr = 1. - t;
        double tr2 = tr * tr;
        double tr2t = tr * 2 * t;

        // adding a point to the polyline
        poly_x.push_back(tr2 * x1 + tr2t * x2 + t2 * x3);
        poly_y.push_back(tr2 * y1 + tr2t * y2 + t2 * y3);
    }

    // adding the last point of the sub-curve
    poly_x.push_back(x3);
    poly_y.push_back(y3);
}

void Curve::fillDyByDx()
{
    dyByDx.resize(poly_x.size() - 1);

    for (unsigned int i = 0; i < poly_x.size() - 1; i++) {
        double dx = poly_x[i + 1] - poly_x[i];
        double dy = poly_y[i + 1] - poly_y[i];
        dyByDx[i] = dy / dx;

    }
}

void Curve::fillHash()
{
    hash.resize(hashSize + 2);

    unsigned int polyIter = 0;
    double const increment = 1. / hashSize;
    double milestone = 0.;

    for (unsigned short i = 0; i < (hashSize + 1);) {
        while (poly_x[polyIter] <= milestone) {
            ++polyIter;
        }

        hash.at(i).smallerValue = polyIter - 1;
        ++i;
        milestone = i * increment;
    }

    milestone = 0.;
    polyIter = 0;

    for (unsigned int i = 0; i < hashSize + 1u;) {
        while (poly_x[polyIter] < (milestone + increment)) {
            ++polyIter;
        }

        hash.at(i).higherValue = polyIter;
        ++i;
        milestone = i * increment;
    }

    hash.at(hashSize + 1).smallerValue = poly_x.size() - 1;
    hash.at(hashSize + 1).higherValue = poly_x.size();

    /*
     * Uncomment the code below to dump the polygon points and the hash table in files
    if (poly_x.size() > 500) {
        printf("Files generated (%d points)\n", poly_x.size());
        FILE* f = fopen ("hash.txt", "wt");
        for (unsigned int i=0; i<hashSize;i++) {
            unsigned short s = hash.at(i).smallerValue;
            unsigned short h = hash.at(i).higherValue;
            fprintf (f, "%d: %d<%d (%.5f<%.5f)\n", i, s, h, poly_x[s], poly_x[h]);
        }
        fclose (f);
        f = fopen ("poly_x.txt", "wt");
        for (size_t i=0; i<poly_x.size();i++) {
            fprintf (f, "%d: %.5f, %.5f\n", i, poly_x[i], poly_y[i]);
        }
        fclose (f);
    }
    */

}

/** @ brief Return the number of control points of the curve
 * This method return the number of control points of a curve. Not suitable for parametric curves.
 * @return number of control points of the curve. 0 will be sent back for Parametric curves
 */
int Curve::getSize() const
{
    return N;
}

/** @ brief Return the a control point's value
 * This method return a control points' value. Not suitable for parametric curves.
 * @param cpNum id of the control points we're interested in
 * @param x Y value of the control points, or -1 if invalid
 * @param y Y value of the control points, or -1 if invalid
 */
void Curve::getControlPoint(int cpNum, double &x, double &y) const
{
    if (this->x && cpNum < N) {
        x = this->x[cpNum];
        y = this->y[cpNum];
    } else {
        x = y = -1.;
    }
}

}

// Point-spread functions for the deconvolution stages.
//
// Each stage models a different physical cause of blur, so each gets its own
// kernel shape rather than one gaussian standing in for all of them:
//   0 gaussian - the catch-all approximation, and what sensor AA filtering plus
//     small residual motion looks like in aggregate
//   1 pillbox  - a uniform disc, the geometric shape of defocus: every point
//     maps to a circle of confusion with hard edges, not a soft falloff
//   2 Airy     - the diffraction pattern of a circular aperture, a central lobe
//     with rings, which is what limits a stopped-down lens

float besselJ1(float x) {
    // Abramowitz & Stegun 9.4.4 / 9.4.6 polynomial approximations: max error
    // around 1.3e-8 for the small branch and 1e-7 for the asymptotic one, far
    // below anything a 7x7 weight table needs.
    float ax = abs(x);
    if (ax < 8.0) {
        float y = x * x;
        float p = x * (72362614232.0 + y * (-7895059235.0 + y * (242396853.1
                + y * (-2972611.439 + y * (15704.48260 + y * (-30.16036606))))));
        float q = 144725228442.0 + y * (2300535178.0 + y * (18583304.74
                + y * (99447.43394 + y * (376.9991397 + y))));
        return p / q;
    }
    float z = 8.0 / ax;
    float y = z * z;
    float xx = ax - 2.356194491;
    float p1 = 1.0 + y * (0.183105e-2 + y * (-0.3516396496e-4
             + y * (0.2457520174e-5 + y * (-0.240337019e-6))));
    float p2 = 0.04687499995 + y * (-0.2002690873e-3
             + y * (0.8449199096e-5 + y * (-0.88228987e-6 + y * 0.105787412e-6)));
    float r = sqrt(0.636619772 / ax) * (cos(xx) * p1 - z * sin(xx) * p2);
    return x < 0.0 ? -r : r;
}

float psfWeight(int kernelType, float dx, float dy, float radius) {
    float d2 = dx * dx + dy * dy;
    float d = sqrt(d2);

    if (kernelType == 1) {
        // Uniform disc with one pixel of smoothing at the rim, so the kernel
        // does not alias on the sample grid.
        return 1.0 - smoothstep(radius - 0.5, radius + 0.5, d);
    }

    if (kernelType == 2) {
        // (2*J1(x)/x)^2 with x = pi*d/radius, which places the first zero of
        // the pattern at d = 1.22*radius, as for a circular aperture.
        if (d < 1e-4) return 1.0;
        float x = 3.14159265 * d / max(radius, 1e-4);
        float j = 2.0 * besselJ1(x) / x;
        return j * j;
    }

    // Gaussian.
    return exp(-d2 / (2.0 * radius * radius));
}

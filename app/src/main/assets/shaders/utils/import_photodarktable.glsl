vec3 applyDarktableLook(vec3 rgb) {
    rgb = max(rgb * exp2(DT_EXPOSURE), vec3(0.0));
    float y = max(luminocity(rgb), 1e-6);
    float shadowMask = 1.0 - smoothstep(0.0, 0.45, y);
    float highlightMask = smoothstep(0.45, 1.0, y);
    rgb *= max(0.05, 1.0 + DT_SHADOWS * shadowMask - DT_HIGHLIGHTS * highlightMask);
    float peak = max(rgb.r, max(rgb.g, rgb.b));
    vec3 reconstructed = rgb / max(1.0, peak);
    rgb = mix(rgb, reconstructed, smoothstep(0.85, 1.25, peak) * clamp(DT_HIGHLIGHT_RECON, 0.0, 1.0));
    rgb = (rgb - 0.18) * max(DT_FILMIC_CONTRAST, 0.1) + 0.18;
    float local = (y - y * y) * DT_LOCAL_CONTRAST;
    rgb += vec3(local);
    float outY = luminocity(rgb);
    rgb = mix(vec3(outY), rgb, max(0.0, 1.0 + DT_COLORFULNESS));

    // Tone Equalizer: broad, overlapping luminance masks avoid hard zone edges.
    float sMask = 1.0 - smoothstep(0.08, 0.45, outY);
    float hMask = smoothstep(0.45, 0.92, outY);
    float mMask = clamp(1.0 - sMask - hMask, 0.0, 1.0);
    rgb *= exp2(DT_TONE_SHADOWS*sMask + DT_TONE_MIDTONES*mMask + DT_TONE_HIGHLIGHTS*hMask);

    // Color Balance RGB with luminance-preserving opponent-axis shifts.
    vec3 warm = vec3(1.0, 0.22, -0.65);
    rgb += warm * (DT_BALANCE_SHADOWS*sMask + DT_BALANCE_MIDTONES*mMask + DT_BALANCE_HIGHLIGHTS*hMask) * 0.12;

    // Color calibration and three broad hue sectors for Color Equalizer.
    rgb *= vec3(1.0 + 0.12*DT_CALIB_TEMP, 1.0 + 0.08*DT_CALIB_TINT,
                1.0 - 0.12*DT_CALIB_TEMP);
    rgb *= vec3(1.0 + 0.25*DT_COLOR_RED, 1.0 + 0.25*DT_COLOR_GREEN,
                1.0 + 0.25*DT_COLOR_BLUE);

    // Color reconstruction: preserve highlight luminance while borrowing
    // chromaticity from the non-clipped channels.
    float hiPeak = max(rgb.r, max(rgb.g, rgb.b));
    float hiMin = min(rgb.r, min(rgb.g, rgb.b));
    float clipped = smoothstep(0.82, 1.05, hiPeak);
    vec3 neutralHi = vec3((hiPeak + hiMin) * 0.5);
    rgb = mix(rgb, mix(neutralHi, rgb, 0.45), clipped * clamp(DT_COLOR_RECON,0.0,1.0));

    // Haze removal with a bounded black-point estimate.
    float airlight = min(rgb.r, min(rgb.g, rgb.b));
    rgb = max((rgb - airlight*DT_HAZE*0.35) /
              max(0.35, 1.0-airlight*DT_HAZE*0.35), vec3(0.0));

    // Lens vignette correction. Wide-CA strength is intentionally bounded;
    // the spatial channel shift is performed in the sampling section.
    vec2 centered = gl_FragCoord.xy / vec2(INSIZE) - 0.5;
    float r2 = dot(centered, centered);
    rgb *= 1.0 + DT_VIGNETTE * r2 * 1.6;
    return max(rgb, vec3(0.0));
}
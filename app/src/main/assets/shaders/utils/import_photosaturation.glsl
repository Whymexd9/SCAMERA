float reinhard_mono(float v, float max_white) {
    float numerator = v * (float(1.0f) + (v / float(max_white * max_white)));
    return numerator / (float(1.0f) + v);
}

vec3 saturate(vec3 rgb, float sat2, float sat) {
    float r = rgb.r;
    float g = rgb.g;
    float b = rgb.b;
    float br = (r+g+b)/3.0;
    float dfsat = mix(sat2,sat,br*br);
    vec3 hsv = rgb2hsv(vec3(rgb.r,rgb.g,rgb.b));
    /*if(hsv.g < 0.5-0.0){
        hsv.g *= mix(1.0,dfsat,hsv.g/(0.5-0.0));
    } else
    if(hsv.g > 0.5+0.0){
        hsv.g *= mix(dfsat,1.0,(0.7-hsv.g)/(0.5-0.0));
    }
    else
    //hsv.g *= mix(dfsat,1.0,abs(hsv.g-0.5)/0.1);
    hsv.g *= dfsat;*/
    //hsv.g *= dfsat;
    hsv.g = reinhard_mono(hsv.g*dfsat, max(1.0,dfsat*0.7));
    //hsv.g *= SATURATIONC+unscaledGaussian(abs(hsv.g),SATURATIONGAUSS)*(dfsat*1.07-1.0);
    rgb = hsv2rgb(hsv);
    rgb.r = mix((rgb.r+br)/2.0,rgb.r,SATURATIONRED);
    return rgb;
}

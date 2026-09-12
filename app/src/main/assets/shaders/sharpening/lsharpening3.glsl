
precision highp float;
precision mediump sampler2D;
uniform sampler2D InputBuffer;
uniform float size;
uniform float strength;
uniform float lensIterations;
uniform float gaussianRadius;
uniform float gaussianAmount;
uniform float threshold;
uniform float smartThreshold;
uniform float edgeStrength;
uniform float bilateralRadius;
uniform float bilateralStrength;
uniform float colorTolerance;
uniform float localContrast;
uniform float guidedRadius;
uniform float guidedEpsilon;
uniform float textureRestore;
uniform float lumaGrain;
uniform float rlAmount;
uniform float rlRadius;
uniform float rlIterations;
uniform float damping;
uniform float shadowProtection;
uniform float highlightProtection;
uniform float haloControl;
out vec3 Output;
#define INSIZE 1,1
#define SHARPSIZE 1.25
#define SHIFT 0.5
#define SHARPMAX 1.0
#define SHARPMIN 0.5
#define NOISEO 0.0
#define NOISES 0.0
#define INTENSE 1.0
#import coords
#import gaussian
float pdfSharp(float i, float sig) {
    i/=sig;
    return 1.0/(1.0+i*i*i*i);
}
void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    vec3 center = texelFetch(InputBuffer, (xy), 0).rgb;
    float centerY = dot(center, vec3(0.2126, 0.7152, 0.0722));
    vec3 gaussianSum = vec3(0.0);
    vec3 unsharpSum = vec3(0.0);
    vec3 bilateralSum = vec3(0.0);
    float gaussianWeight = 0.0;
    float unsharpWeight = 0.0;
    float bilateralWeight = 0.0;
    float minY = centerY;
    float maxY = centerY;
    for (int i = -2; i <= 2; i++) {
        for (int j = -2; j <= 2; j++) {
            vec3 sampleRgb = texelFetch(InputBuffer, mirrorCoords2(xy + ivec2(i,j), ivec2(INSIZE)), 0).rgb;
            float sampleY = dot(sampleRgb, vec3(0.2126, 0.7152, 0.0722));
            float spatial = exp(-float(i*i+j*j) / max(2.0*max(size, 0.05)*max(size, 0.05), 0.01));
            float bilateralSpatial = exp(-float(i*i+j*j) /
                max(2.0*max(bilateralRadius, 0.05)*max(bilateralRadius, 0.05), 0.01));
            float unsharpSpatial = exp(-float(i*i+j*j) /
                max(2.0*max(gaussianRadius, 0.05)*max(gaussianRadius, 0.05), 0.01));
            float range = exp(-abs(sampleY-centerY) / colorTolerance);
            gaussianSum += sampleRgb * spatial;
            gaussianWeight += spatial;
            unsharpSum += sampleRgb * unsharpSpatial;
            unsharpWeight += unsharpSpatial;
            bilateralSum += sampleRgb * bilateralSpatial * range;
            bilateralWeight += bilateralSpatial * range;
            minY = min(minY, sampleY);
            maxY = max(maxY, sampleY);
        }
    }
    vec3 gaussianBlur = gaussianSum / max(gaussianWeight, 0.0001);
    vec3 unsharpBlur = unsharpSum / max(unsharpWeight, 0.0001);
    vec3 bilateralBlur = bilateralSum / max(bilateralWeight, 0.0001);
    float detail = centerY - dot(gaussianBlur, vec3(0.2126, 0.7152, 0.0722));
    float unsharpDetail = centerY - dot(unsharpBlur, vec3(0.2126, 0.7152, 0.0722));
    float bilateralDetail = centerY - dot(bilateralBlur, vec3(0.2126, 0.7152, 0.0722));

    float leftY = dot(texelFetch(InputBuffer, mirrorCoords2(xy+ivec2(-1,0), ivec2(INSIZE)), 0).rgb, vec3(0.2126,0.7152,0.0722));
    float rightY = dot(texelFetch(InputBuffer, mirrorCoords2(xy+ivec2(1,0), ivec2(INSIZE)), 0).rgb, vec3(0.2126,0.7152,0.0722));
    float downY = dot(texelFetch(InputBuffer, mirrorCoords2(xy+ivec2(0,-1), ivec2(INSIZE)), 0).rgb, vec3(0.2126,0.7152,0.0722));
    float upY = dot(texelFetch(InputBuffer, mirrorCoords2(xy+ivec2(0,1), ivec2(INSIZE)), 0).rgb, vec3(0.2126,0.7152,0.0722));
    float sobel = length(vec2(rightY-leftY, upY-downY));
    float edgeMask = smoothstep(smartThreshold, smartThreshold + max(guidedEpsilon, 0.001), sobel);
    float noise = sqrt(max(centerY*NOISES*INTENSE + NOISEO*INTENSE, 0.0)) + damping*0.02;
    float wiener = detail*detail / (detail*detail + noise*noise + guidedEpsilon*guidedEpsilon);

    float shadowFade = mix(1.0, smoothstep(0.02, 0.30, centerY), shadowProtection);
    float highlightFade = mix(1.0, 1.0-smoothstep(0.70, 0.99, centerY), highlightProtection);
    float tonalFade = shadowFade * highlightFade;
    float shock = sign(detail) * min(abs(detail), maxY-minY) * textureRestore;
    float lensGain = strength * min(lensIterations, 10.0) / 3.0;
    float sharpenLuma = detail * lensGain * wiener;
    // Soft gate. step() switches on or off at exactly the threshold, so the
    // unsharp term appears abruptly across whatever contour the threshold happens
    // to fall on, and the image breaks into flat regions either side of it - the
    // posterisation in the comparison crops. Fading in over a small band keeps the
    // noise floor protected without drawing a contour through the picture.
    float gaussianGate = smoothstep(threshold, threshold + max(threshold, 0.004), abs(unsharpDetail));
    sharpenLuma += unsharpDetail * gaussianAmount * gaussianGate;
    sharpenLuma += sobel * edgeStrength * edgeMask * sign(detail);
    sharpenLuma += bilateralDetail * bilateralStrength;
    sharpenLuma += detail * localContrast * (1.0 + guidedRadius / 64.0) /
        (1.0 + guidedEpsilon*50.0);
    // Richardson-Lucy is performed by ExperimentalCaptureSharpening.  The
    // former detail-gain approximation here caused a second, non-RL sharpen.
    sharpenLuma += shock;
    float haloLimit = mix(0.25, max((maxY-minY)*0.5, 0.005), haloControl);
    sharpenLuma = clamp(sharpenLuma * tonalFade, -haloLimit, haloLimit);

    float randomGrain = fract(sin(dot(vec2(xy), vec2(12.9898,78.233))) * 43758.5453) - 0.5;
    float newY = clamp(centerY + sharpenLuma + randomGrain*lumaGrain*0.01*tonalFade, 0.0, 1.0);
    Output = clamp(center * (newY / max(centerY, 0.0001)), 0.0, 1.0);
}

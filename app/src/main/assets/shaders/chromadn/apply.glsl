precision highp float;
precision highp sampler2D;
// Colour noise removal, step 3: the change the half-resolution filter made to the colour
// ratios is added to the full-resolution pixel (its own luminance and fine colour detail stay).
uniform sampler2D InputBuffer;
uniform sampler2D Before;    // half resolution, unfiltered
uniform sampler2D After;     // half resolution, filtered
out vec4 Output;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 c = max(texelFetch(InputBuffer, p, 0).rgb, vec3(0.0));
    vec2 uv = (vec2(p) + 0.5) / vec2(textureSize(InputBuffer, 0));
    vec3 b = texture(Before, uv).rgb;
    vec3 a = texture(After, uv).rgb;
    float yb = max(dot(b, vec3(1.0 / 3.0)), 1.0e-6);
    vec3 dq = (a - b) / yb;
    float y = dot(c, vec3(1.0 / 3.0));
    Output = vec4(max(c + y * dq, vec3(0.0)), 1.0);
}

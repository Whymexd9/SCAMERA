#version 300 es
layout(location = 0) in vec2 vPosition;
layout(location = 1) in vec2 vTexCoord;
uniform mat4 texRotate;
out vec2 texCoord;
void main() {
    // Match the ISP preview's sensor orientation. Rotate clip-space around its centre.
    texCoord = vec2(1.0 - vTexCoord.y, vTexCoord.x);
    gl_Position = texRotate * vec4(vPosition, 0.0, 1.0);
}

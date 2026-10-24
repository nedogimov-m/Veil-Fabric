#version 330
#extension GL_ARB_separate_shader_objects : require

#include veil:common

uniform samplerCube DiffuseSampler0;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 outColor;

void main() {
    float phi = texCoord.s * TWO_PI;
    float theta = (0.5 - texCoord.t) * PI;

    vec3 direction = vec3(cos(phi) * cos(theta), sin(theta), sin(phi) * cos(theta));
    outColor = texture(DiffuseSampler0, direction);
}
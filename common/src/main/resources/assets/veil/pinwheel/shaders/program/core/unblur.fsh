#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D DiffuseSampler0;
layout(std140) uniform PostUniforms { vec2 InSize; vec2 OutSize; };

layout(location=0) in vec2 texCoord;

layout(location=0) out vec4 fragColor;

const float offset = 3;

void main() {
    vec2 halfpixel = 0.5 / OutSize;

    vec4 sum = texture(DiffuseSampler0, texCoord + vec2(-halfpixel.x * 2.0, 0.0) * offset);
    sum += texture(DiffuseSampler0, texCoord + vec2(-halfpixel.x, halfpixel.y) * offset) * 2.0;
    sum += texture(DiffuseSampler0, texCoord + vec2(0.0, halfpixel.y * 2.0) * offset);
    sum += texture(DiffuseSampler0, texCoord + vec2(halfpixel.x, halfpixel.y) * offset) * 2.0;
    sum += texture(DiffuseSampler0, texCoord + vec2(halfpixel.x * 2.0, 0.0) * offset);
    sum += texture(DiffuseSampler0, texCoord + vec2(halfpixel.x, -halfpixel.y) * offset) * 2.0;
    sum += texture(DiffuseSampler0, texCoord + vec2(0.0, -halfpixel.y * 2.0) * offset);
    sum += texture(DiffuseSampler0, texCoord + vec2(-halfpixel.x, -halfpixel.y) * offset) * 2.0;
    fragColor = sum / 12.0;
}
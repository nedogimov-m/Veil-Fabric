#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2DArray DiffuseSampler0;
uniform int Index;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 outColor;

void main() {
    outColor = texture(DiffuseSampler0, vec3(texCoord, float(Index)));
}
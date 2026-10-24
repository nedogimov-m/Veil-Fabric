#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D AutoSampler;
uniform vec4 Tint;
layout(location = 0) out vec4 fragColor;
void main() { fragColor = texelFetch(AutoSampler, ivec2(0), 0) * Tint; }

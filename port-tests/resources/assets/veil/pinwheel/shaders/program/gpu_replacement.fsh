#version 330
#extension GL_ARB_separate_shader_objects : require
layout(std140) uniform ReplacementTint { vec4 Tint; };
uniform sampler2D ReplacementInput;
layout(location = 0) out vec4 fragColor;
void main() { fragColor = texture(ReplacementInput, vec2(0.5)) * Tint * GAIN; }

#version 330
#extension GL_ARB_separate_shader_objects : require
layout(std140) uniform ReplacementTint { vec4 Tint; };
layout(std140) uniform NativeDraw { vec4 NativeColor; };
uniform sampler2D Sampler0;
layout(location = 0) out vec4 fragColor;
void main() { fragColor = texture(Sampler0, vec2(0.5)) * Tint * NativeColor * GAIN; }

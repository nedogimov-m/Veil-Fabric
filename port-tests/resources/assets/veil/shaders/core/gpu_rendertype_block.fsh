#version 330
#extension GL_ARB_separate_shader_objects : require
layout(std140) uniform SnapshotSettings { vec4 Tint; };
layout(location = 0) out vec4 fragColor;
void main() { fragColor = Tint; }

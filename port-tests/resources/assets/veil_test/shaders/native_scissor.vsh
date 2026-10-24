#version 450 core
layout(push_constant) uniform Rectangle { vec4 Bounds; };
const vec2 POSITIONS[6] = vec2[](vec2(0, 0), vec2(1, 0), vec2(1, 1), vec2(0, 0), vec2(1, 1), vec2(0, 1));
void main() { gl_Position = vec4(Bounds.xy + POSITIONS[gl_VertexIndex] * Bounds.zw, 0, 1); }

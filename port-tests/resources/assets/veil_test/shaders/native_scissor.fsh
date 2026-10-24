#version 450 core
layout(std140) uniform ScissorColor { vec4 Value; };
layout(location=0) out vec4 fragColor;
void main() { fragColor = Value; }

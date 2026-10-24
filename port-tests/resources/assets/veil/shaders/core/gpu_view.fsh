#version 330
#extension GL_ARB_separate_shader_objects : require
#veil:buffer veil_test:view
layout(location = 0) out vec4 fragColor;
void main() { fragColor = Value; }

#version 330
#extension GL_ARB_separate_shader_objects : require
layout(location = 0) out vec4 fragColor;
void main() {
    // #veil:debug
    vec4 debugValue = vec4(0.25, 0.5, 0.75, 1.0);
    fragColor = vec4(0.2, 0.4, 0.6, 1.0);
}

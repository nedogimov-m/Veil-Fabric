#version 330
#extension GL_ARB_separate_shader_objects : require
#include <veil:gpu_source_blocks>
layout(location = 0) out vec4 fragColor;
void main() {
    // #veil:debug
    vec4 debugValue = sourceBlockTint();
    fragColor = debugValue;
}

#version 330
#extension GL_ARB_separate_shader_objects : require
#moj_import <veil:gpu_processor_base.glsl>
layout(location = 0) out vec4 fragColor;
void main() { fragColor = vec4(processorTint(), processorAlpha()); }

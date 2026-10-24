#version 330
#extension GL_ARB_separate_shader_objects : require
layout(location = 0) in vec3 vertexNormal;
layout(location = 0) out vec4 fragColor;
void main() {
    fragColor = vec4(abs(vertexNormal), 1.0);
}

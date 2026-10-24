#version 330
#extension GL_ARB_separate_shader_objects : require

layout(std140) uniform VeilUniforms {
    vec3 BaseColor;
    float Scale;
    mat3 Transform;
    float Weights[2];
    ivec2 Flags;
    mat4 Project;
};
layout(location = 0) out vec4 fragColor;

void main() {
    vec3 color = Transform * BaseColor * Scale * (Weights[0] + Weights[1]);
    color.z *= float(Flags.y);
    fragColor = vec4(color, float(Flags.x) * Project[3][3]);
}

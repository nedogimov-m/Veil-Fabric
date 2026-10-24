#version 330
#extension GL_ARB_separate_shader_objects : require
layout(std140) uniform SerializedSettings {
    vec3 BaseColor;
    float Scale;
    mat3 Transform;
    vec2 Offset;
    int Mode;
};
layout(location = 0) out vec4 fragColor;
void main() {
    vec3 color = Transform * BaseColor * Scale;
    fragColor = vec4(color + vec3(Offset, 0), float(Mode) / 5.0);
}

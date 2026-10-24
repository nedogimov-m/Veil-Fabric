#version 330
#extension GL_ARB_separate_shader_objects : require
layout(std140) uniform FieldSettings {
    bool Flag;
    float Gain;
    ivec2 Indices;
    uint Unsigned;
    float Samples[2];
    mat2x3 Transform;
    vec4 Tint;
};
layout(location = 0) out vec4 fragColor;
void main() {
    vec3 color = (Transform * vec2(1, 2)) * Tint.rgb * Gain;
    color.r += Samples[0] * 0.4;
    color.g += float(Indices.x + Indices.y) * 0.1;
    fragColor = vec4(color, Flag && Unsigned == 255u ? Samples[1] : 0);
}

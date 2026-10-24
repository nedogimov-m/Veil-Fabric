#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D DiffuseSampler0;
layout(std140) uniform PostUniforms {
    vec2 InSize;
    vec2 OutSize;
    vec4 TintColor;
};
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texture(DiffuseSampler0, texCoord) * TintColor;
    gl_FragDepth = 0.5;
}

#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D DiffuseDepthSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 OutColor;

void main() {
    gl_FragDepth = texture(DiffuseDepthSampler, texCoord).r;
    OutColor = vec4(0.0);
}

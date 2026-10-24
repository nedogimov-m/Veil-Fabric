#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D Payload;
uniform sampler2D PackedW;
layout(location = 0) out vec4 fragColor;
void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    fragColor = vec4(texelFetch(Payload, pixel, 0).xyz, texelFetch(PackedW, pixel, 0)[VEIL_CAPTURE_W_CHANNEL]);
}

#version 330
#extension GL_ARB_separate_shader_objects : require

#include <veil:color_utilities>
#define SMOOTHING 0.3

uniform sampler2D DiffuseSampler0;
uniform sampler2D BloomSampler;
uniform sampler2D BlurFinal;

layout(location=0) in vec2 texCoord;

layout(location=0) out vec4 fragColor;

void main() {
    fragColor = texture(DiffuseSampler0, texCoord);

    vec4 bloomBase = texture(BloomSampler, texCoord);
    vec4 bloomBlur = texture(BlurFinal, texCoord) * vec4(vec3(0.8), 1.0);

    float factor = step(0.9, bloomBase.a);
    fragColor.rgb += acesToneMapping(reverseAces(fragColor.rgb) * factor + bloomBlur.rgb);
}
#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:oit.glsl>
uniform sampler2D OpaqueProperty;
uniform sampler2D PropertyAccum;
uniform sampler2D PropertyWeights;
uniform sampler2D VanillaAccum;
layout(location = 0) out vec4 fragColor;
void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 property = texelFetch(PropertyAccum, pixel, 0);
    float nativeWeight = texelFetch(VanillaAccum, pixel, 0).a;
    float transmittance = sampleTransmittance(pixel, 100000.0, 1.0);
    float coverage = 1.0 - (transmittance < OIT_FULLY_OPAQUE_TOTAL_TRANSMITTANCE ? 0.0 : transmittance);
    float normalization = nativeWeight > 0.00001 ? coverage / nativeWeight : 1.0;
    float producedWeight = texelFetch(PropertyWeights, pixel, 0)[VEIL_OIT_WEIGHT_CHANNEL];
    float producedCoverage = clamp(producedWeight * normalization, 0.0, 1.0);
    fragColor = property * normalization + texelFetch(OpaqueProperty, pixel, 0) * (1.0 - producedCoverage);
}

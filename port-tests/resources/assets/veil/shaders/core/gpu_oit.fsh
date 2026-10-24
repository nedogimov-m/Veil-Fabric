#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:oit.glsl>
layout(std140) uniform Surface { vec4 Color; vec4 Payload; float DeviceDepth; };
#ifdef OIT_ACCUMULATE
layout(location = 0) out vec4 fragColor;
#endif
void main() {
#ifdef OIT_ACCUMULATE
#ifndef NO_PROPERTIES
    // #veil:albedo
    vec4 albedoValue = vec4(0.25,0.5,0.75,0.25);
    // #veil:normal
    vec4 normalValue = vec4(-0.5,0.25,0.5,-0.25);
    // #veil:light_uv
    vec4 lightUvValue = vec4(0.75,0.25,0.0,1.0);
    // #veil:light_color
    vec4 lightValue = vec4(0.75,0.5,0.25,0.5);
    // #veil:debug
    vec4 debugValue = Payload;
#endif
    fragColor = sampleColorForAccumulation(Color);
#else
    executeAlphaOnlyPhase(gl_FragCoord.z, Color.a);
#endif
}

#version 330
#extension GL_ARB_separate_shader_objects : require
layout(std140) uniform Properties {
    vec4 Primary;
    vec4 Albedo;
    vec4 Normal;
    vec4 LightUV;
    vec4 LightColor;
    vec4 Debug;
};
layout(location = 0) out vec4 ordinaryColor;
void main() {
#ifndef SKIP_ALBEDO
    // #veil:albedo
    vec4 albedoValue = Albedo;
#endif
    // #veil:normal
    vec4 normalValue = Normal;
    // #veil:light_uv
    vec4 lightUvValue = LightUV;
    // #veil:light_color
    vec4 lightColorValue = LightColor;
    // #veil:debug
    vec4 debugValue = Debug;
    ordinaryColor = Primary;
}

#version 330
#extension GL_ARB_separate_shader_objects : require
uniform vec4 SharedTint;
uniform vec3 RGBScale;
uniform float Gain;
uniform float Alphas[2];
uniform bool Enabled;
uniform ivec2 Flags;
uniform mat3 AlphaMatrix;
layout(location = 0) in vec4 stageTint;
layout(location = 0) out vec4 fragColor;
void main() {
    // #veil:debug
    vec4 debugValue = vec4(stageTint.xyz, Alphas[0]);
    fragColor = vec4(stageTint.xyz * SharedTint.xyz * RGBScale * Gain,
        Enabled && Flags.x == 2 && Flags.y == 3 ? Alphas[1] * AlphaMatrix[2][2] : 0.0);
}

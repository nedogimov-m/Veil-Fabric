#version 330
#extension GL_ARB_separate_shader_objects : require
#ifdef VEIL_ALBEDO
#include veil:gpu_managed_replay_material
#endif
layout(location = 0) out vec4 fragColor;
void main() {
#ifdef VEIL_DEBUG
    // #veil:debug
    vec4 debugValue = vec4(0.25, 0.5, 0.75, 0.125);
#endif
#ifdef VEIL_ALBEDO
    // #veil:albedo
    vec4 materialValue = managedReplayMaterial();
#endif
    fragColor = NATIVE_TINT;
}

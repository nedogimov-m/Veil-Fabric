#version 450 core
uniform float VeilRenderTime;
uniform float VeilBlockFaceBrightness[6];
#ifdef MANAGED_DEFAULTS
uniform float UserGain;
#else
#define UserGain 1.0
#endif
layout(location=0) in vec2 ProofNormal;
layout(location=0) out vec4 fragColor;
void main() {
    fragColor = vec4(ProofNormal, VeilBlockFaceBrightness[2], VeilRenderTime / 3600.0) * UserGain;
}

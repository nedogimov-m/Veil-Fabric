#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D Input0;
uniform sampler2D NativeColor;
layout(std140) uniform CoverageInit { float UseClearAlpha; float ClearAlpha; };
layout(location = 0) out vec4 Payload0;
#if VEIL_CAPTURE_COUNT > 1
uniform sampler2D Input1;
layout(location = 1) out vec4 Payload1;
#endif
#if VEIL_CAPTURE_COUNT > 2
uniform sampler2D Input2;
layout(location = 2) out vec4 Payload2;
#endif
#if VEIL_CAPTURE_COUNT > 3
uniform sampler2D Input3;
layout(location = 3) out vec4 Payload3;
#endif
#if VEIL_CAPTURE_COUNT > 4
uniform sampler2D Input4;
layout(location = 4) out vec4 Payload4;
#endif
layout(location = VEIL_CAPTURE_COUNT) out vec4 Packed0;
#if VEIL_CAPTURE_COUNT > 3
layout(location = VEIL_CAPTURE_SECOND_LOCATION) out vec4 Packed1;
#endif
void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float coverage = UseClearAlpha > 0.5 ? ClearAlpha : texelFetch(NativeColor, pixel, 0).a;
    vec4 p0 = texelFetch(Input0, pixel, 0);
    Payload0 = vec4(p0.xyz, coverage); Packed0 = vec4(p0.w, 0.0, 0.0, coverage);
#if VEIL_CAPTURE_COUNT > 1
    vec4 p1 = texelFetch(Input1, pixel, 0);
    Payload1 = vec4(p1.xyz, coverage); Packed0.y = p1.w;
#endif
#if VEIL_CAPTURE_COUNT > 2
    vec4 p2 = texelFetch(Input2, pixel, 0);
    Payload2 = vec4(p2.xyz, coverage); Packed0.z = p2.w;
#endif
#if VEIL_CAPTURE_COUNT > 3
    vec4 p3 = texelFetch(Input3, pixel, 0);
    Payload3 = vec4(p3.xyz, coverage); Packed1 = vec4(p3.w, 0.0, 0.0, coverage);
#endif
#if VEIL_CAPTURE_COUNT > 4
    vec4 p4 = texelFetch(Input4, pixel, 0);
    Payload4 = vec4(p4.xyz, coverage); Packed1.y = p4.w;
#endif
}

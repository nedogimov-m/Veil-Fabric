#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:fog.glsl>
layout(location=0) in vec3 Position;
layout(location=1) in vec3 Normal;
layout(location=2) in mat4 ParticleMat;
layout(location=6) in float Scale;
layout(location=7) in vec2 UV0Min;
layout(location=8) in vec2 UV0Max;
layout(location=9) in vec4 Color;
layout(location=10) in ivec2 UV2;
uniform sampler2D Sampler2;
layout(location=0) out float sphericalVertexDistance;
layout(location=1) out float cylindricalVertexDistance;
layout(location=2) out vec2 texCoord0;
layout(location=3) out vec4 vertexColor;
// #veil:light_color
layout(location=4) out vec4 lightmapColor;
void main() {
    vec4 worldPosition=ParticleMat*vec4(Position*Scale,1.0);
    gl_Position=ProjMat*ModelViewMat*worldPosition;
    sphericalVertexDistance=fog_spherical_distance(worldPosition.xyz);
    cylindricalVertexDistance=fog_cylindrical_distance(worldPosition.xyz);
    vec2 uvs[4];uvs[0]=UV0Min;uvs[1]=vec2(UV0Min.x,UV0Max.y);uvs[2]=UV0Max;uvs[3]=vec2(UV0Max.x,UV0Min.y);
    texCoord0=uvs[gl_VertexIndex%4];
    vertexColor=Color;
    lightmapColor=texelFetch(Sampler2,UV2/16,0);
#ifdef VEIL_NORMAL
    // #veil:normal
    vec3 normal=transpose(inverse(mat3(ModelViewMat)))*Normal;
#endif
}

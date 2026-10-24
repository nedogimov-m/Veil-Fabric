#version 330
#extension GL_ARB_separate_shader_objects : require
layout(std140) uniform Surface { vec4 Color; vec4 Payload; float DeviceDepth; };
void main() {
    vec2 positions[3] = vec2[](vec2(-1.0,-1.0), vec2(3.0,-1.0), vec2(-1.0,3.0));
    gl_Position = vec4(positions[gl_VertexIndex], DeviceDepth, 1.0);
}

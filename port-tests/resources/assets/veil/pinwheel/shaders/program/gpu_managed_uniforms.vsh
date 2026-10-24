#version 330
#extension GL_ARB_separate_shader_objects : require
uniform mat4 Transform;
uniform vec4 SharedTint;
uniform uint UseVertex;
layout(location = 0) out vec4 stageTint;
void main() {
    vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = Transform * vec4(uv * 2.0 - 1.0, 0.5, 1.0);
    stageTint = SharedTint * float(UseVertex);
}

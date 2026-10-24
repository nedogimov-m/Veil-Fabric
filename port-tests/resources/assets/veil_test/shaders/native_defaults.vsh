#version 450 core
uniform mat3 NormalMat;
layout(location=0) out vec2 ProofNormal;
void main() {
    vec2 position = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(position * 2.0 - 1.0, 0.0, 1.0);
    ProofNormal = normalize(NormalMat * vec3(1.0, 1.0, 0.0)).xy * 0.5 + 0.5;
}

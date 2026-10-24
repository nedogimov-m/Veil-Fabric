// Reusable three-vertex fullscreen triangle for Veil postprocessing pipelines.
vec2 veil_fullscreen_uv() {
    return vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
}

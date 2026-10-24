#include veil:voxel_shadow
layout(location=0) out vec4 fragColor;
void main() {
    vec3 a=GridOrigin+vec3(1.5,10.5,40.5);
    vec3 b=GridOrigin+vec3(20.5,10.5,40.5);
    vec3 c=GridOrigin+vec3(1.5,11.5,40.5);
    vec3 d=GridOrigin+vec3(20.5,11.5,40.5);
    fragColor=vec4(voxelshadowVisibility(a,b),voxelshadowVisibility(c,d),voxelshadowVisibility(b,a),1);
}

#version 330
#extension GL_ARB_separate_shader_objects : require
layout(location=0) in uvec2 a_Position;
layout(location=4) in vec4 a_VeilNormal;
layout(location=0) out vec3 normalValue;
void main(){uvec3 hi=(uvec3(a_Position.x)>>uvec3(0u,10u,20u))&1023u;uvec3 lo=(uvec3(a_Position.y)>>uvec3(0u,10u,20u))&1023u;vec3 p=vec3((hi<<10u)|lo)*(32.0/1048576.0)-8.0;gl_Position=vec4(p,1.0);normalValue=a_VeilNormal.xyz;}

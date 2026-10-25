#version 450
layout(location=0) out vec4 fragColor;
void main(){gl_FragDepth=gl_FragCoord.x<8.0?0.25:0.9;fragColor=vec4(0.5,0.125,0.0625,1);}

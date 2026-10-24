#veil:buffer veil:camera VeilCamera

layout (location = 0) in vec3 Position;
layout (location = 1) in mat4 LightMatrix;
layout (location = 5) in vec3 Color;
layout (location = 6) in vec2 Size;
layout (location = 7) in float NormalizedAngle;
layout (location = 8) in float Distance;
layout (location = 9) in float Occluded;

layout(location=0) out mat4 lightMat;
layout(location=4) out vec3 lightColor;
layout(location=5) out vec2 size;
layout(location=6) out float maxAngle;
layout(location=7) out float maxDistance;
layout(location=8) out float occluded;

void main() {
    vec3 vertexPos = Position;
    float Angle = NormalizedAngle * 6.28318530718;
    vertexPos.z = clamp(vertexPos.z, min(cos(Angle), 0), 1);
    float angleTerm = sin(min(Angle, 1.57079633)) * Distance;
    vertexPos *= vec3(Size.x + angleTerm, Size.y + angleTerm, Distance);

    // awful fix but not sure why just multiplying the matrix doesnt work? it does what it should in
    // all the second calculations. really weird!
    vec3 lightPos = LightMatrix[3].xyz;
    mat3 rotationMatrix = mat3(LightMatrix);
    lightPos = inverse(rotationMatrix) * lightPos;
    vertexPos = inverse(rotationMatrix) * vertexPos;
    vertexPos += lightPos;
    gl_Position = VeilCamera.ProjMat * VeilCamera.ViewMat * vec4(vertexPos - VeilCamera.CameraPosition, 1.0);

    lightMat = LightMatrix;
    lightColor = Color;
    size = Size;
    maxAngle = Angle;
    maxDistance = Distance;
    occluded = Occluded;
}

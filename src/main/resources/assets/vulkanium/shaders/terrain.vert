#version 450

// Vulkanium Terrain Vertex Shader
// 32-byte vertex format

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec4 inColor;
layout(location = 2) in vec2 inTexCoord;
layout(location = 3) in vec4 inNormal;
layout(location = 4) in vec4 inTangent;
layout(location = 5) in uint inLightCoord;
layout(location = 6) in uint inEntityId;

// UBO set 0 binding 0: Camera
layout(set = 0, binding = 0) uniform CameraUBO {
    mat4 projectionMatrix;
    mat4 viewMatrix;
    mat4 modelViewMatrix;
    mat4 projectionViewMatrix;
    vec3 cameraPosition;
    float gameTime;
} camera;

// Outputs to fragment shader
layout(location = 0) out vec4 fragColor;
layout(location = 1) out vec2 fragTexCoord;
layout(location = 2) out vec2 fragLightCoord;
layout(location = 3) out vec3 fragNormal;
layout(location = 4) out vec3 fragTangent;
layout(location = 5) out vec3 fragWorldPos;
layout(location = 6) flat out uint fragEntityId;

void main() {
    vec4 worldPos = vec4(inPosition, 1.0);
    gl_Position = camera.projectionViewMatrix * worldPos;

    fragColor = inColor;
    fragTexCoord = inTexCoord;

    // Unpack light coordinate (sky << 8 | block)
    float blockLight = float(inLightCoord & 0xFFu) / 255.0;
    float skyLight = float((inLightCoord >> 8u) & 0xFFu) / 255.0;
    fragLightCoord = vec2(blockLight, skyLight);

    fragNormal = inNormal.xyz;
    fragTangent = inTangent.xyz;
    fragWorldPos = inPosition - camera.cameraPosition;
    fragEntityId = inEntityId;
}

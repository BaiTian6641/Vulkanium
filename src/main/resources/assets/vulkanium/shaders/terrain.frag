#version 450

// Vulkanium Terrain Fragment Shader
// Outputs to MRT G-buffer (multiple render targets)

layout(location = 0) in vec4 fragColor;
layout(location = 1) in vec2 fragTexCoord;
layout(location = 2) in vec2 fragLightCoord;
layout(location = 3) in vec3 fragNormal;
layout(location = 4) in vec3 fragTangent;
layout(location = 5) in vec3 fragWorldPos;
layout(location = 6) flat in uint fragEntityId;

// Sampler set 1
layout(set = 1, binding = 0) uniform sampler2D texAtlas;
layout(set = 1, binding = 1) uniform sampler2D texLightmap;

// G-buffer outputs (MRT)
layout(location = 0) out vec4 outColor;    // colortex0: albedo RGBA
layout(location = 1) out vec4 outNormal;   // colortex1: normal XYZ + roughness
layout(location = 2) out vec4 outData;     // colortex2: lightcoord + entityId + flags

void main() {
    // Sample texture atlas
    vec4 texColor = texture(texAtlas, fragTexCoord);

    // Alpha test
    if (texColor.a < 0.1) {
        discard;
    }

    // Multiply by vertex color (biome tint, etc.)
    vec4 albedo = texColor * fragColor;

    // Output to G-buffer
    outColor = albedo;

    // Encode normal (pack to [0,1] range)
    outNormal = vec4(fragNormal * 0.5 + 0.5, 0.0);

    // Encode lightmap and metadata
    outData = vec4(fragLightCoord, float(fragEntityId) / 65535.0, 1.0);
}

#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_nonuniform_qualifier : require

// Closest-hit shader for solid block geometry
// Computes direct + indirect illumination at the hit point

layout(location = 0) rayPayloadInEXT struct RayPayload {
    vec3 color;
    float distance;
    vec3 normal;
    uint bounceCount;
} payload;

layout(location = 1) rayPayloadEXT struct ShadowPayload {
    bool inShadow;
} shadowPayload;

hitAttributeEXT vec2 baryCoords;

// Material table SSBO
struct Material {
    float roughness;
    float metallic;
    float emission;
    float ior;
    float opacity;
    float subsurface;
    uint flags;
    uint padding;
};

layout(set = 0, binding = 0) uniform accelerationStructureEXT topLevelAS;
layout(set = 0, binding = 3, std430) readonly buffer Materials { Material materials[]; };
layout(set = 0, binding = 4) uniform sampler2D blockAtlas;

layout(set = 0, binding = 2, std140) uniform CameraData {
    mat4 viewInverse;
    mat4 projInverse;
    vec3 cameraPosition;
    float time;
    uint frameIndex;
    uint maxBounces;
    float sunAngle;
    float padding;
} camera;

// Sun direction from angle
vec3 getSunDirection() {
    float angle = camera.sunAngle * 6.28318530718;
    return normalize(vec3(cos(angle), sin(angle), 0.3));
}

void main() {
    // Barycentric interpolation
    vec3 bary = vec3(1.0 - baryCoords.x - baryCoords.y, baryCoords.x, baryCoords.y);

    // Get material from custom instance index
    uint materialId = gl_InstanceCustomIndexEXT;
    Material mat = materials[materialId];

    // World-space hit position
    vec3 hitPos = gl_WorldRayOriginEXT + gl_WorldRayDirectionEXT * gl_HitTEXT;

    // Object-space normal (from hit attributes — simplified)
    vec3 normal = normalize(gl_ObjectToWorldEXT * vec4(0.0, 1.0, 0.0, 0.0)).xyz;

    payload.distance = gl_HitTEXT;
    payload.normal = normal;

    // Base color (would sample from atlas using UV in full implementation)
    vec3 albedo = vec3(0.6, 0.6, 0.6); // Placeholder

    // Emission
    if (mat.emission > 0.0) {
        payload.color = albedo * mat.emission;
        return;
    }

    // Direct lighting — trace shadow ray toward sun
    vec3 sunDir = getSunDirection();
    float NdotL = max(dot(normal, sunDir), 0.0);

    shadowPayload.inShadow = false;
    if (NdotL > 0.0) {
        traceRayEXT(
            topLevelAS,
            gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsSkipClosestHitShaderEXT,
            0xFF,
            0, 0,
            1,                // shadow miss shader index
            hitPos + normal * 0.001,
            0.001,
            sunDir,
            1000.0,
            1                 // shadow payload location
        );
    }

    float shadowFactor = shadowPayload.inShadow ? 0.1 : 1.0;
    vec3 directLight = albedo * NdotL * shadowFactor;

    // Ambient approximation + sky contribution
    float skyFactor = max(dot(normal, vec3(0.0, 1.0, 0.0)), 0.0);
    vec3 ambient = albedo * (0.15 + 0.1 * skyFactor);

    // Indirect bounce (if within bounce budget)
    vec3 indirect = vec3(0.0);
    if (payload.bounceCount < camera.maxBounces) {
        // Cosine-weighted hemisphere sample (simplified)
        // In full implementation: importance-sampled BRDF
        payload.bounceCount++;
    }

    payload.color = directLight + ambient + indirect;
}

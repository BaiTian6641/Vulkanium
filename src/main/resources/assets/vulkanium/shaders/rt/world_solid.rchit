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
    float visibility;
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

const uint FLAG_EMISSIVE = 1u;
const uint FLAG_WATER = 2u;
const uint FLAG_GLASS = 4u;
const uint FLAG_LEAF = 8u;
const uint FLAG_ICE = 16u;
const uint FLAG_METAL = 32u;
const uint FLAG_SUBSURFACE = 64u;
const uint FLAG_CUTOUT = 128u;
const uint FLAG_TRANSLUCENT = 256u;
const uint FLAG_FOLIAGE = 512u;

// Sun direction from angle
vec3 getSunDirection() {
    float angle = camera.sunAngle * 6.28318530718;
    return normalize(vec3(cos(angle), sin(angle), 0.3));
}

float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

vec3 fresnelSchlick(float cosTheta, vec3 F0) {
    return F0 + (1.0 - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

float distributionGGX(vec3 N, vec3 H, float roughness) {
    float a = max(0.03, roughness * roughness);
    float a2 = a * a;
    float NdotH = max(dot(N, H), 0.0);
    float NdotH2 = NdotH * NdotH;

    float denom = (NdotH2 * (a2 - 1.0) + 1.0);
    return a2 / max(3.14159265359 * denom * denom, 1e-4);
}

float geometrySchlickGGX(float NdotV, float roughness) {
    float r = roughness + 1.0;
    float k = (r * r) / 8.0;
    return NdotV / max(NdotV * (1.0 - k) + k, 1e-4);
}

float geometrySmith(vec3 N, vec3 V, vec3 L, float roughness) {
    float ggx1 = geometrySchlickGGX(max(dot(N, V), 0.0), roughness);
    float ggx2 = geometrySchlickGGX(max(dot(N, L), 0.0), roughness);
    return ggx1 * ggx2;
}

float traceShadowVisibility(vec3 hitPos, vec3 normal, vec3 sunDir, float penumbraRadius) {
    const int SHADOW_SAMPLES = 4;
    vec3 up = abs(normal.y) < 0.95 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 tangent = normalize(cross(sunDir, up));
    vec3 bitangent = normalize(cross(sunDir, tangent));

    float visibilityAccum = 0.0;
    for (int i = 0; i < SHADOW_SAMPLES; i++) {
        float angle = 6.28318530718 * hash13(hitPos + vec3(float(i), camera.frameIndex * 0.07, 0.0));
        float radius = penumbraRadius * sqrt(hash13(hitPos + vec3(0.0, float(i), camera.time)));
        vec2 disk = vec2(cos(angle), sin(angle)) * radius;
        vec3 jitteredDir = normalize(sunDir + tangent * disk.x + bitangent * disk.y);

        shadowPayload.visibility = 1.0;
        traceRayEXT(
            topLevelAS,
            gl_RayFlagsTerminateOnFirstHitEXT,
            0xFF,
            0, 0,
            1,
            hitPos + normal * 0.002,
            0.002,
            jitteredDir,
            1000.0,
            1
        );

        visibilityAccum += clamp(shadowPayload.visibility, 0.0, 1.0);
    }

    return visibilityAccum / float(SHADOW_SAMPLES);
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

    // Direct lighting — trace shadow ray toward sun
    vec3 sunDir = getSunDirection();
    float NdotL = max(dot(normal, sunDir), 0.0);

    float shadowFactor = 1.0;
    if (NdotL > 0.0) {
        float penumbra = (mat.flags & FLAG_FOLIAGE) != 0u ? 0.05 : 0.02;
        shadowFactor = traceShadowVisibility(hitPos, normal, sunDir, penumbra);
    }

    if ((mat.flags & FLAG_TRANSLUCENT) != 0u || mat.opacity < 0.99) {
        shadowFactor = max(shadowFactor, 0.25 + (1.0 - mat.opacity) * 0.5);
    }

    if ((mat.flags & FLAG_LEAF) != 0u || (mat.flags & FLAG_CUTOUT) != 0u) {
        shadowFactor = mix(shadowFactor, 1.0, 0.2 * mat.subsurface);
    }

    vec3 V = normalize(camera.cameraPosition - hitPos);
    vec3 L = sunDir;
    vec3 H = normalize(V + L);

    float NdotV = max(dot(normal, V), 0.0);
    float NdotH = max(dot(normal, H), 0.0);
    float VdotH = max(dot(V, H), 0.0);

    float roughness = clamp(mat.roughness, 0.03, 1.0);
    float metallic = clamp(mat.metallic, 0.0, 1.0);
    float iorF0 = pow((max(mat.ior, 1.0) - 1.0) / max(max(mat.ior, 1.0) + 1.0, 1e-4), 2.0);
    vec3 F0 = mix(vec3(clamp(iorF0, 0.02, 0.9)), albedo, metallic);

    float D = distributionGGX(normal, H, roughness);
    float G = geometrySmith(normal, V, L, roughness);
    vec3 F = fresnelSchlick(VdotH, F0);

    vec3 numerator = D * G * F;
    float denominator = max(4.0 * NdotV * NdotL, 1e-4);
    vec3 specular = numerator / denominator;

    vec3 kS = F;
    vec3 kD = (vec3(1.0) - kS) * (1.0 - metallic);

    vec3 sunRadiance = vec3(1.0, 0.98, 0.92) * 3.0;
    vec3 directLight = (kD * albedo / 3.14159265359 + specular) * sunRadiance * NdotL * shadowFactor;

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

    vec3 emissive = vec3(0.0);
    if (mat.emission > 0.0 || (mat.flags & FLAG_EMISSIVE) != 0u) {
        float emissionStrength = max(mat.emission, 1.0);
        emissive = albedo * (emissionStrength * 0.08);
    }

    payload.color = directLight + ambient + indirect + emissive;
}

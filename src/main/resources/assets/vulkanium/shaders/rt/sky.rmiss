#version 460
#extension GL_EXT_ray_tracing : require

// Sky miss shader — called when a primary ray hits nothing
// Computes sky color based on ray direction and sun position

layout(location = 0) rayPayloadInEXT struct RayPayload {
    vec3 color;
    float distance;
    vec3 normal;
    uint bounceCount;
} payload;

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

vec3 getSunDirection() {
    float angle = camera.sunAngle * 6.28318530718;
    return normalize(vec3(cos(angle), sin(angle), 0.3));
}

void main() {
    vec3 dir = normalize(gl_WorldRayDirectionEXT);
    vec3 sunDir = getSunDirection();

    // Simple procedural sky
    float sunDot = max(dot(dir, sunDir), 0.0);

    // Rayleigh scattering approximation
    float height = max(dir.y, 0.0);
    vec3 skyBlue = vec3(0.3, 0.5, 0.9);
    vec3 horizon = vec3(0.8, 0.85, 0.95);
    vec3 skyColor = mix(horizon, skyBlue, pow(height, 0.4));

    // Sun disc
    float sunIntensity = pow(sunDot, 512.0) * 10.0;
    vec3 sunColor = vec3(1.0, 0.95, 0.8) * sunIntensity;

    // Sun glow (corona)
    float glowIntensity = pow(sunDot, 8.0) * 0.3;
    vec3 glowColor = vec3(1.0, 0.8, 0.5) * glowIntensity;

    // Below horizon — show MC-like distant terrain fog color rather than void black
    if (dir.y < 0.0) {
        vec3 groundColor = vec3(0.55, 0.50, 0.45); // warm earth / distant fog
        skyColor = mix(horizon * 0.7, groundColor, min(-dir.y * 4.0, 1.0));
        sunColor = vec3(0.0);
        glowColor = vec3(0.0);
    }

    payload.color = skyColor + sunColor + glowColor;
    payload.distance = -1.0; // No hit
    payload.normal = vec3(0.0);
}

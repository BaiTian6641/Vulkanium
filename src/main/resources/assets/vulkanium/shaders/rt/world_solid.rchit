#version 460
#extension GL_EXT_ray_tracing : require

// Closest-hit stub for the SBT hit-group requirement.
// In hybrid-shadow mode the ray-gen shader never fires primary rays, so
// this shader is NEVER invoked at runtime.  It is kept so the SBT hit
// group table remains valid.

layout(location = 0) rayPayloadInEXT struct RayPayload {
    vec3  color;
    float distance;
    vec3  normal;
    uint  bounceCount;
} payload;



void main() {

    // Stub: hybrid shadow mode never invokes this shader.
    // world.rgen does not shoot primary rays, and shadow rays use
    // gl_RayFlagsSkipClosestHitShaderEXT, so this entry point is unreachable.
    payload.color    = vec3(0.5);
    payload.distance = gl_HitTEXT;
    payload.normal   = vec3(0.0, 1.0, 0.0);
}

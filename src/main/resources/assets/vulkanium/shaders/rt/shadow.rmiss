#version 460
#extension GL_EXT_ray_tracing : require

// Shadow miss shader — called when shadow ray hits nothing (not in shadow)
// If the closest-hit shader is invoked instead, the shadow payload stays true

layout(location = 1) rayPayloadInEXT struct ShadowPayload {
    bool inShadow;
} payload;

void main() {
    // Ray reached the light source without obstruction
    payload.inShadow = false;
}

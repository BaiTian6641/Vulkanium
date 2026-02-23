#version 460
#extension GL_EXT_ray_tracing : require

// Shadow miss shader — called when shadow ray hits nothing (not in shadow)
// If the closest-hit shader is invoked instead, the shadow payload stays true

layout(location = 1) rayPayloadInEXT struct ShadowPayload {
    float visibility;
} payload;

void main() {
    payload.visibility = 1.0;
}

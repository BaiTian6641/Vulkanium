package net.vulkanium.render.vertex.views;

/**
 * Read-only view of a polygon's vertex positions and texture coordinates.
 * Used by {@link net.vulkanium.render.vertex.NormalHelper} to compute face
 * normals and tangents from buffered vertex data.
 */
public interface PolygonView {
    float x(int index);
    float y(int index);
    float z(int index);
    float u(int index);
    float v(int index);
}

package net.vulkanium.render.vertex;

import net.vulkanium.render.vertex.views.QuadView;

import java.nio.ByteBuffer;

/**
 * Reads back vertex positions and UVs from a ByteBuffer that contains recently
 * written quad/tri vertices.  Used by {@link NormalHelper} to compute face
 * normals, tangents, and mid-texture coordinates after a full polygon has been
 * emitted.
 *
 * <p>The view interprets vertices in reverse order from the current write pointer:
 * vertex 0 is the oldest (farthest back), vertex {@code vertexAmount-1} is the
 * newest (closest to {@code writePointer}).  This matches how Minecraft's
 * {@code BufferBuilder} lays out consecutive vertices.</p>
 *
 * <p>Position is always at offset 0 within each vertex stride, UV0 at offset 16
 * (matching {@code DefaultVertexFormat.BLOCK} and the extended TERRAIN format).</p>
 */
public class BufferBuilderPolygonView implements QuadView {
    private ByteBuffer buffer;
    private int writePointer;
    private int stride;
    private int vertexAmount;

    public void setup(ByteBuffer buffer, int writePointer, int stride, int vertexAmount) {
        this.buffer = buffer;
        this.writePointer = writePointer;
        this.stride = stride;
        this.vertexAmount = vertexAmount;
    }

    @Override
    public float x(int index) {
        return buffer.getFloat(writePointer - stride * (vertexAmount - index));
    }

    @Override
    public float y(int index) {
        return buffer.getFloat(writePointer + 4 - stride * (vertexAmount - index));
    }

    @Override
    public float z(int index) {
        return buffer.getFloat(writePointer + 8 - stride * (vertexAmount - index));
    }

    @Override
    public float u(int index) {
        return buffer.getFloat(writePointer + 16 - stride * (vertexAmount - index));
    }

    @Override
    public float v(int index) {
        return buffer.getFloat(writePointer + 20 - stride * (vertexAmount - index));
    }
}

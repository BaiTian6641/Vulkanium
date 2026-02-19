package net.vulkanium.render.vertex;

import org.lwjgl.system.MemoryUtil;

/**
 * Vulkan-backed vertex builder that replaces Minecraft's BufferBuilder.
 *
 * <p>Provides the same consumer API (vertex → color → uv → normal → end)
 * but writes directly into a Vulkanium vertex format buffer for Vulkan rendering.</p>
 *
 * <p>Vertex format: 32 bytes per vertex (see {@link net.vulkanium.vulkan.VertexFormats}):</p>
 * <pre>
 *   Position:  3 × float  (12 bytes, offset 0)
 *   Color:     1 × uint32 (4 bytes, offset 12) ABGR packed
 *   TexCoord:  2 × half   (4 bytes, offset 16)
 *   Normal:    1 × uint32 (4 bytes, offset 20) packed 10_10_10_2
 *   Tangent:   1 × uint32 (4 bytes, offset 24) packed 10_10_10_2
 *   Light:     1 × uint16 (2 bytes, offset 28) block<<4 | sky
 *   EntityId:  1 × uint16 (2 bytes, offset 30)
 * </pre>
 */
public class VulkanVertexBuilder {

    /** Default buffer size: 256KB (grows as needed) */
    private static final int DEFAULT_SIZE = 256 * 1024;

    /** Vertex stride */
    public static final int STRIDE = 32;

    /** Native memory buffer pointer */
    private long bufferPtr;

    /** Current buffer size in bytes */
    private long bufferSize;

    /** Write cursor (byte offset) */
    private long writeOffset;

    /** Number of vertices written */
    private int vertexCount;

    /** Whether we're currently building a vertex (between begin and end_vertex) */
    private boolean building;

    /** Draw mode: 4 = QUADS, 7 = TRIANGLES */
    private int drawMode;

    /** Current vertex temporary state */
    private float posX, posY, posZ;
    private int color = 0xFFFFFFFF;    // ABGR packed, default white
    private float u, v;
    private int packedNormal;
    private int packedTangent;
    private int packedLight;
    private short entityId;

    public VulkanVertexBuilder() {
        this(DEFAULT_SIZE);
    }

    public VulkanVertexBuilder(int initialSize) {
        this.bufferSize = initialSize;
        this.bufferPtr = MemoryUtil.nmemAlloc(initialSize);
        if (bufferPtr == 0) {
            throw new OutOfMemoryError("Failed to allocate vertex builder: " + initialSize + " bytes");
        }
    }

    // ─── Builder API (matches MC's VertexConsumer) ─────────────────────

    /**
     * Begin building. Resets state.
     *
     * @param drawMode 4 = QUADS, 7 = TRIANGLES
     */
    public void begin(int drawMode) {
        this.drawMode = drawMode;
        this.writeOffset = 0;
        this.vertexCount = 0;
        this.building = true;
    }

    /** Set position for the current vertex. */
    public VulkanVertexBuilder vertex(float x, float y, float z) {
        this.posX = x;
        this.posY = y;
        this.posZ = z;
        return this;
    }

    /** Set color (RGBA 0-255). */
    public VulkanVertexBuilder color(int r, int g, int b, int a) {
        this.color = (a << 24) | (b << 16) | (g << 8) | r; // ABGR packing
        return this;
    }

    /** Set color from packed ARGB int. */
    public VulkanVertexBuilder color(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        this.color = (a << 24) | (b << 16) | (g << 8) | r;
        return this;
    }

    /** Set texture coordinates. */
    public VulkanVertexBuilder uv(float u, float v) {
        this.u = u;
        this.v = v;
        return this;
    }

    /** Set light coordinates (block light, sky light, 0-15 each). */
    public VulkanVertexBuilder light(int blockLight, int skyLight) {
        this.packedLight = (short) ((blockLight & 0xF) << 4 | (skyLight & 0xF));
        return this;
    }

    /** Set light from packed lightmap coord (as MC uses it: block<<20 | sky<<4). */
    public VulkanVertexBuilder lightPacked(int packedLightCoords) {
        int block = (packedLightCoords >> 20) & 0xF;
        int sky = (packedLightCoords >> 4) & 0xF;
        this.packedLight = (short) ((block << 4) | sky);
        return this;
    }

    /** Set normal vector. */
    public VulkanVertexBuilder normal(float x, float y, float z) {
        this.packedNormal = packNormal(x, y, z);
        return this;
    }

    /** Set tangent vector. */
    public VulkanVertexBuilder tangent(float x, float y, float z, float w) {
        this.packedTangent = packNormal(x, y, z); // w sign packed in bit 31
        if (w < 0) this.packedTangent |= 0x80000000;
        return this;
    }

    /** Set entity ID. */
    public VulkanVertexBuilder entityId(int id) {
        this.entityId = (short) id;
        return this;
    }

    /** Finish the current vertex and write it to the buffer. */
    public void endVertex() {
        ensureCapacity(STRIDE);

        long ptr = bufferPtr + writeOffset;
        MemoryUtil.memPutFloat(ptr, posX);
        MemoryUtil.memPutFloat(ptr + 4, posY);
        MemoryUtil.memPutFloat(ptr + 8, posZ);
        MemoryUtil.memPutInt(ptr + 12, color);

        // Half-float UVs
        MemoryUtil.memPutShort(ptr + 16, toHalf(u));
        MemoryUtil.memPutShort(ptr + 18, toHalf(v));

        MemoryUtil.memPutInt(ptr + 20, packedNormal);
        MemoryUtil.memPutInt(ptr + 24, packedTangent);
        MemoryUtil.memPutShort(ptr + 28, (short) packedLight);
        MemoryUtil.memPutShort(ptr + 30, entityId);

        writeOffset += STRIDE;
        vertexCount++;

        // Reset per-vertex state
        color = 0xFFFFFFFF;
        packedNormal = 0;
        packedTangent = 0;
        packedLight = 0;
        entityId = 0;
    }

    /** End building. Returns true if any geometry was produced. */
    public boolean end() {
        building = false;
        return vertexCount > 0;
    }

    // ─── Convenience methods for common quad patterns ──────────────────

    /**
     * Emit a full-face quad (4 vertices). Caller provides positions.
     */
    public void quad(float x0, float y0, float z0,
                     float x1, float y1, float z1,
                     float x2, float y2, float z2,
                     float x3, float y3, float z3,
                     float u0, float v0, float u1, float v1,
                     float nx, float ny, float nz,
                     int colorPacked, int lightPacked) {
        int n = packNormal(nx, ny, nz);

        // Vertex 0
        writeDirect(x0, y0, z0, colorPacked, u0, v0, n, 0, (short) lightPacked, (short) 0);
        // Vertex 1
        writeDirect(x1, y1, z1, colorPacked, u1, v0, n, 0, (short) lightPacked, (short) 0);
        // Vertex 2
        writeDirect(x2, y2, z2, colorPacked, u1, v1, n, 0, (short) lightPacked, (short) 0);
        // Vertex 3
        writeDirect(x3, y3, z3, colorPacked, u0, v1, n, 0, (short) lightPacked, (short) 0);
    }

    private void writeDirect(float x, float y, float z, int color,
                             float u, float v, int normal, int tangent,
                             short light, short entity) {
        ensureCapacity(STRIDE);
        long ptr = bufferPtr + writeOffset;
        MemoryUtil.memPutFloat(ptr, x);
        MemoryUtil.memPutFloat(ptr + 4, y);
        MemoryUtil.memPutFloat(ptr + 8, z);
        MemoryUtil.memPutInt(ptr + 12, color);
        MemoryUtil.memPutShort(ptr + 16, toHalf(u));
        MemoryUtil.memPutShort(ptr + 18, toHalf(v));
        MemoryUtil.memPutInt(ptr + 20, normal);
        MemoryUtil.memPutInt(ptr + 24, tangent);
        MemoryUtil.memPutShort(ptr + 28, light);
        MemoryUtil.memPutShort(ptr + 30, entity);
        writeOffset += STRIDE;
        vertexCount++;
    }

    // ─── Buffer management ─────────────────────────────────────────────

    private void ensureCapacity(int additionalBytes) {
        long needed = writeOffset + additionalBytes;
        if (needed <= bufferSize) return;

        // Double the buffer
        long newSize = Math.max(bufferSize * 2, needed);
        long newPtr = MemoryUtil.nmemRealloc(bufferPtr, newSize);
        if (newPtr == 0) {
            throw new OutOfMemoryError("Failed to grow vertex builder to " + newSize + " bytes");
        }
        bufferPtr = newPtr;
        bufferSize = newSize;
    }

    /** Get the native pointer to the start of the buffer. */
    public long getBufferPtr() { return bufferPtr; }

    /** Get the number of bytes written. */
    public long getByteSize() { return writeOffset; }

    /** Get the number of vertices written. */
    public int getVertexCount() { return vertexCount; }

    /** Get the draw mode. */
    public int getDrawMode() { return drawMode; }

    /** Check if currently building. */
    public boolean isBuilding() { return building; }

    /** Free native memory. Call when done. */
    public void free() {
        if (bufferPtr != 0) {
            MemoryUtil.nmemFree(bufferPtr);
            bufferPtr = 0;
        }
    }

    // ─── Utility ───────────────────────────────────────────────────────

    /** Pack a normalized float3 into a 10_10_10_2 uint32. */
    public static int packNormal(float x, float y, float z) {
        int ix = Math.round(x * 511.0f) & 0x3FF;
        int iy = Math.round(y * 511.0f) & 0x3FF;
        int iz = Math.round(z * 511.0f) & 0x3FF;
        return ix | (iy << 10) | (iz << 20);
    }

    /** Convert float to IEEE 754 half-float (16-bit). */
    public static short toHalf(float value) {
        int fbits = Float.floatToIntBits(value);
        int sign = (fbits >>> 16) & 0x8000;
        int val = (fbits & 0x7FFFFFFF) + 0x1000;

        if (val >= 0x47800000) {
            return (short) (sign | 0x7C00); // Infinity
        }
        if (val < 0x38800000) {
            return (short) sign; // Underflow to zero
        }

        int exp = ((val >> 23) - 112) << 10;
        int man = (val >> 13) & 0x3FF;
        return (short) (sign | exp | man);
    }
}

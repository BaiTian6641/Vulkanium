package net.vulkanium.render.terrain.section;

import net.vulkanium.render.terrain.pass.TerrainPassType;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Holds the mesh data (vertex + index buffers) for a single chunk section.
 *
 * <p>This is the output of {@code ChunkMeshBuilder} and the input to the GPU upload system.
 * Data is stored in off-heap memory (direct ByteBuffers) for zero-copy staging upload.</p>
 *
 * <p>Each section can have separate mesh data per terrain pass (SOLID, CUTOUT, TRANSLUCENT, etc.).
 * Empty passes have null data, meaning no geometry for that pass type.</p>
 *
 * <h3>Memory Management</h3>
 * <p>Mesh data is allocated off-heap via {@link MemoryUtil} for direct VMA staging upload.
 * Callers MUST call {@link #free()} when the data is no longer needed (after GPU upload).</p>
 *
 * <h3>Inspired by Sodium's {@code ChunkBuildOutput}</h3>
 * <p>Sodium stores built mesh data in off-heap arenas per region. Vulkanium follows the same
 * pattern but uses per-section data that gets concatenated into region buffers on upload.</p>
 */
public class SectionData {

    /** Per-pass mesh data. Null entries mean no geometry for that pass. */
    private final PassMeshData[] passMeshes;

    /** Number of non-empty block states encountered during meshing. */
    private int nonEmptyBlockCount;

    /** True if this section contains any translucent geometry. */
    private boolean hasTranslucents;

    /** True if any mesh data has been written. */
    private boolean isEmpty = true;

    public SectionData() {
        this.passMeshes = new PassMeshData[TerrainPassType.count()];
    }

    /**
     * Sets the mesh data for a specific render pass.
     *
     * @param pass       The terrain pass type
     * @param vertexData Off-heap vertex data (32-byte stride per vertex)
     * @param indexData  Off-heap index data (uint32 indices)
     * @param vertexCount Number of vertices
     * @param indexCount  Number of indices (triangles * 3)
     */
    public void setPassData(TerrainPassType pass, ByteBuffer vertexData, ByteBuffer indexData,
                            int vertexCount, int indexCount) {
        if (passMeshes[pass.ordinal()] != null) {
            passMeshes[pass.ordinal()].free(); // Release previous data
        }
        passMeshes[pass.ordinal()] = new PassMeshData(vertexData, indexData, vertexCount, indexCount);
        isEmpty = false;

        if (pass.blend) {
            hasTranslucents = true;
        }
    }

    /**
     * Returns mesh data for a specific pass, or null if the pass has no geometry.
     */
    public PassMeshData getPassData(TerrainPassType pass) {
        return passMeshes[pass.ordinal()];
    }

    /**
     * Returns whether this section has any renderable geometry at all.
     */
    public boolean isEmpty() {
        return isEmpty;
    }

    /**
     * Returns whether this section contains translucent geometry that needs sorting.
     */
    public boolean hasTranslucents() {
        return hasTranslucents;
    }

    public int getNonEmptyBlockCount() {
        return nonEmptyBlockCount;
    }

    public void setNonEmptyBlockCount(int count) {
        this.nonEmptyBlockCount = count;
    }

    /**
     * Computes total vertex data size across all passes (bytes).
     */
    public long totalVertexBytes() {
        long total = 0;
        for (PassMeshData mesh : passMeshes) {
            if (mesh != null) {
                total += mesh.vertexData().remaining();
            }
        }
        return total;
    }

    /**
     * Computes total index data size across all passes (bytes).
     */
    public long totalIndexBytes() {
        long total = 0;
        for (PassMeshData mesh : passMeshes) {
            if (mesh != null) {
                total += mesh.indexData().remaining();
            }
        }
        return total;
    }

    /**
     * Releases all off-heap memory. Must be called after GPU upload or when discarding.
     */
    public void free() {
        for (int i = 0; i < passMeshes.length; i++) {
            if (passMeshes[i] != null) {
                passMeshes[i].free();
                passMeshes[i] = null;
            }
        }
        isEmpty = true;
        hasTranslucents = false;
    }

    /**
     * Mesh data for a single pass within a section.
     */
    public static final class PassMeshData {
        private ByteBuffer vertexData;
        private ByteBuffer indexData;
        private final int vertexCount;
        private final int indexCount;

        public PassMeshData(ByteBuffer vertexData, ByteBuffer indexData,
                            int vertexCount, int indexCount) {
            this.vertexData = vertexData;
            this.indexData = indexData;
            this.vertexCount = vertexCount;
            this.indexCount = indexCount;
        }

        public ByteBuffer vertexData() { return vertexData; }
        public ByteBuffer indexData() { return indexData; }
        public int vertexCount() { return vertexCount; }
        public int indexCount() { return indexCount; }

        /**
         * Releases the off-heap ByteBuffer memory.
         */
        void free() {
            if (vertexData != null) {
                MemoryUtil.memFree(vertexData);
                vertexData = null;
            }
            if (indexData != null) {
                MemoryUtil.memFree(indexData);
                indexData = null;
            }
        }
    }
}

package net.vulkanium.render.vertex;

/**
 * Interface implemented by BufferBuilder when Vulkanium extends terrain vertex
 * format.  The chunk rebuild mixin calls {@link #beginBlock} before each block's
 * rendering and {@link #endBlock} afterwards so the per-vertex mc_Entity field
 * is written with the correct shaderpack-defined block material ID.
 */
public interface BlockSensitiveBufferBuilder {

    /**
     * Begin rendering a block — sets per-vertex block properties.
     *
     * @param blockId    shaderpack material ID from block.properties (-1 if unmapped)
     * @param renderType -1 for blocks, 1 for fluids
     * @param localPosX  block X within section (0..15)
     * @param localPosY  block Y within section (0..15)
     * @param localPosZ  block Z within section (0..15)
     */
    void beginBlock(short blockId, short renderType, int localPosX, int localPosY, int localPosZ);

    /** End block rendering — resets per-vertex block data to defaults. */
    void endBlock();
}

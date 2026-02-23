package net.vulkanium.render.vertex;

/**
 * Utilities for computing block-center offsets (at_midBlock).
 *
 * <p>at_midBlock = (blockLocalPos + 0.5 - vertexPos) × 64, packed as 3 signed
 * bytes into a 32-bit int.  Shaderpacks use this to determine the center of the
 * block that a fragment belongs to, even when the vertex position has sub-block
 * precision (e.g. slabs, stairs).</p>
 *
 * <p>Ported from Iris ExtendedDataHelper.</p>
 */
public final class ExtendedDataHelper {
    /** shaderpack render type for solid blocks */
    public static final short BLOCK_RENDER_TYPE = -1;
    /** shaderpack render type for fluids (matches Minecraft 1.7 convention) */
    public static final short FLUID_RENDER_TYPE = 1;

    private ExtendedDataHelper() {}

    /**
     * Pack three midBlock offsets into a 32-bit int (3 bytes, high byte unused).
     */
    public static int packMidBlock(float x, float y, float z) {
        return ((int) (x * 64) & 0xFF)
             | (((int) (y * 64) & 0xFF) << 8)
             | (((int) (z * 64) & 0xFF) << 16);
    }

    /**
     * Compute at_midBlock for a vertex at world position (x,y,z) belonging to
     * the block at local section position (localPosX, localPosY, localPosZ).
     */
    public static int computeMidBlock(float x, float y, float z,
                                       int localPosX, int localPosY, int localPosZ) {
        return packMidBlock(
                localPosX + 0.5f - x,
                localPosY + 0.5f - y,
                localPosZ + 0.5f - z
        );
    }
}

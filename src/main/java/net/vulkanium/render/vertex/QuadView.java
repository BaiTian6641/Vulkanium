package net.vulkanium.render.vertex;

/**
 * Read-only view into a baked quad's vertex data.
 *
 * <p>Used during chunk meshing to access pre-baked model quads without
 * allocating objects. Provides access to position, color, UV, normal,
 * and light data for each of the 4 vertices.</p>
 *
 * <p>Modeled after Sodium's {@code ModelQuadView}.</p>
 */
public class QuadView {

    /** Raw vertex data backing this view (4 vertices × stride) */
    private int[] data;

    /** Offset into the data array */
    private int baseOffset;

    /** Integers per vertex in MC's baked quad format */
    private static final int MC_VERTEX_INTS = 8;

    /** Offsets within MC's baked quad vertex format */
    private static final int POS_OFFSET = 0;   // 3 floats (as int bits)
    private static final int COLOR_OFFSET = 3;  // 1 int (ARGB)
    private static final int UV_OFFSET = 4;     // 2 floats
    private static final int LIGHT_OFFSET = 6;  // 1 int (packed light)
    private static final int NORMAL_OFFSET = 7; // 1 int (packed normal)

    public QuadView() {}

    /**
     * Set the backing data. Called when iterating over baked model quads.
     */
    public void setData(int[] data, int offset) {
        this.data = data;
        this.baseOffset = offset;
    }

    // ─── Per-vertex accessors ──────────────────────────────────────────

    public float getX(int vertexIndex) {
        return Float.intBitsToFloat(data[baseOffset + vertexIndex * MC_VERTEX_INTS + POS_OFFSET]);
    }

    public float getY(int vertexIndex) {
        return Float.intBitsToFloat(data[baseOffset + vertexIndex * MC_VERTEX_INTS + POS_OFFSET + 1]);
    }

    public float getZ(int vertexIndex) {
        return Float.intBitsToFloat(data[baseOffset + vertexIndex * MC_VERTEX_INTS + POS_OFFSET + 2]);
    }

    public int getColor(int vertexIndex) {
        return data[baseOffset + vertexIndex * MC_VERTEX_INTS + COLOR_OFFSET];
    }

    public float getU(int vertexIndex) {
        return Float.intBitsToFloat(data[baseOffset + vertexIndex * MC_VERTEX_INTS + UV_OFFSET]);
    }

    public float getV(int vertexIndex) {
        return Float.intBitsToFloat(data[baseOffset + vertexIndex * MC_VERTEX_INTS + UV_OFFSET + 1]);
    }

    public int getLight(int vertexIndex) {
        return data[baseOffset + vertexIndex * MC_VERTEX_INTS + LIGHT_OFFSET];
    }

    public int getNormal(int vertexIndex) {
        return data[baseOffset + vertexIndex * MC_VERTEX_INTS + NORMAL_OFFSET];
    }

    // ─── Quad-level accessors ──────────────────────────────────────────

    /**
     * Get the face normal of the quad (from vertex 0's packed normal).
     */
    public int getFaceNormal() {
        return getNormal(0);
    }

    /**
     * Get the unpacked face normal X component.
     */
    public float getNormalX() {
        int packed = getFaceNormal();
        int x = (byte) (packed & 0xFF);
        return x / 127.0f;
    }

    public float getNormalY() {
        int packed = getFaceNormal();
        int y = (byte) ((packed >> 8) & 0xFF);
        return y / 127.0f;
    }

    public float getNormalZ() {
        int packed = getFaceNormal();
        int z = (byte) ((packed >> 16) & 0xFF);
        return z / 127.0f;
    }

    /**
     * Check if all four vertices have the same color.
     */
    public boolean hasUniformColor() {
        int c0 = getColor(0);
        return c0 == getColor(1) && c0 == getColor(2) && c0 == getColor(3);
    }

    /**
     * Check if the quad has lightmap data.
     */
    public boolean hasLightmap() {
        return getLight(0) != 0 || getLight(1) != 0 || getLight(2) != 0 || getLight(3) != 0;
    }

    /**
     * Determine which face direction this quad points towards.
     * Returns: 0=down, 1=up, 2=north, 3=south, 4=west, 5=east, -1=unknown
     */
    public int getFaceDirection() {
        float nx = getNormalX();
        float ny = getNormalY();
        float nz = getNormalZ();

        float abx = Math.abs(nx), aby = Math.abs(ny), abz = Math.abs(nz);

        if (aby > abx && aby > abz) {
            return ny < 0 ? 0 : 1; // DOWN or UP
        }
        if (abz > abx) {
            return nz < 0 ? 2 : 3; // NORTH or SOUTH
        }
        if (abx > 0.5f) {
            return nx < 0 ? 4 : 5; // WEST or EAST
        }
        return -1;
    }
}

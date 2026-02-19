package net.vulkanium.render.model;

import net.vulkanium.world.BiomeColorSource;

/**
 * Lighting pipeline for chunk block faces.
 *
 * <p>Computes per-vertex lighting using either:</p>
 * <ul>
 *   <li><b>Flat lighting:</b> uniform light for the entire face, from the adjacent block's light level</li>
 *   <li><b>Smooth lighting (AO):</b> per-vertex light interpolated from surrounding blocks</li>
 * </ul>
 *
 * <p>Modeled after Sodium's and VulkanMod's light pipeline implementations.</p>
 */
public class LightPipeline {

    /** Result holder for per-vertex lighting */
    public static class LightResult {
        /** Per-vertex packed light values (4 vertices) */
        public final int[] light = new int[4];

        /** Per-vertex brightness multipliers for AO (0.0 - 1.0, 4 vertices) */
        public final float[] ao = new float[4];

        /** Whether this result uses smooth lighting */
        public boolean smooth;
    }

    /** AO brightness values for different corner configruations */
    private static final float[] AO_VALUES = {0.2f, 0.4f, 0.6f, 1.0f};

    /** Face direction offsets: down, up, north, south, west, east */
    private static final int[][] FACE_OFFSETS = {
        {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
    };

    /** Per-vertex neighbor offsets for AO computation, per face direction.
     *  Each face has 4 vertices, each vertex samples 4 neighbors. */
    // (simplified — real implementation would have 6 × 4 × 4 offsets)

    private LightPipeline() {}

    /**
     * Compute flat lighting for a quad.
     *
     * @param face     face direction (0-5: down, up, north, south, west, east)
     * @param blockX   block position X (local to section)
     * @param blockY   block position Y
     * @param blockZ   block position Z
     * @param blockLight  block light at the face position
     * @param skyLight    sky light at the face position
     * @param result   output light result
     */
    public static void computeFlat(int face, int blockX, int blockY, int blockZ,
                                   int blockLight, int skyLight, LightResult result) {
        int packed = (blockLight << 20) | (skyLight << 4);
        result.light[0] = packed;
        result.light[1] = packed;
        result.light[2] = packed;
        result.light[3] = packed;

        result.ao[0] = 1.0f;
        result.ao[1] = 1.0f;
        result.ao[2] = 1.0f;
        result.ao[3] = 1.0f;

        result.smooth = false;
    }

    /**
     * Compute smooth (ambient occlusion) lighting for a quad.
     *
     * <p>For each vertex, samples the light and opacity of 4 neighboring blocks
     * to determine AO and interpolated lighting.</p>
     *
     * @param face     face direction (0-5)
     * @param blockX   block position X
     * @param blockY   block position Y
     * @param blockZ   block position Z
     * @param neighbors a lookup function for block opacity and light at a position
     * @param result   output light result
     */
    public static void computeSmooth(int face, int blockX, int blockY, int blockZ,
                                     NeighborLookup neighbors, LightResult result) {
        result.smooth = true;

        // The face-adjacent block position
        int offX = FACE_OFFSETS[face][0];
        int offY = FACE_OFFSETS[face][1];
        int offZ = FACE_OFFSETS[face][2];

        int adjX = blockX + offX;
        int adjY = blockY + offY;
        int adjZ = blockZ + offZ;

        // For each of the 4 vertices of the face:
        for (int v = 0; v < 4; v++) {
            // Determine the 3 corner-sharing neighbor offsets for this vertex
            // (depends on face direction and vertex index)
            int[][] cornerOffsets = getCornerOffsets(face, v);

            int side1Light = neighbors.getLight(adjX + cornerOffsets[0][0],
                                                 adjY + cornerOffsets[0][1],
                                                 adjZ + cornerOffsets[0][2]);
            boolean side1Opaque = neighbors.isOpaque(adjX + cornerOffsets[0][0],
                                                      adjY + cornerOffsets[0][1],
                                                      adjZ + cornerOffsets[0][2]);

            int side2Light = neighbors.getLight(adjX + cornerOffsets[1][0],
                                                 adjY + cornerOffsets[1][1],
                                                 adjZ + cornerOffsets[1][2]);
            boolean side2Opaque = neighbors.isOpaque(adjX + cornerOffsets[1][0],
                                                      adjY + cornerOffsets[1][1],
                                                      adjZ + cornerOffsets[1][2]);

            int cornerLight;
            boolean cornerOpaque;
            if (side1Opaque && side2Opaque) {
                // Both sides opaque → corner is fully occluded
                cornerLight = 0;
                cornerOpaque = true;
            } else {
                cornerLight = neighbors.getLight(adjX + cornerOffsets[2][0],
                                                  adjY + cornerOffsets[2][1],
                                                  adjZ + cornerOffsets[2][2]);
                cornerOpaque = neighbors.isOpaque(adjX + cornerOffsets[2][0],
                                                    adjY + cornerOffsets[2][1],
                                                    adjZ + cornerOffsets[2][2]);
            }

            int centerLight = neighbors.getLight(adjX, adjY, adjZ);

            // AO level: count opaque neighbors (0-3)
            int aoLevel = 3;
            if (side1Opaque) aoLevel--;
            if (side2Opaque) aoLevel--;
            if (cornerOpaque) aoLevel--;

            result.ao[v] = AO_VALUES[aoLevel];

            // Average light from center + 3 neighbors
            int avgBlock = ((centerLight >> 20) & 0xF) + ((side1Light >> 20) & 0xF)
                         + ((side2Light >> 20) & 0xF) + ((cornerLight >> 20) & 0xF);
            int avgSky = ((centerLight >> 4) & 0xF) + ((side1Light >> 4) & 0xF)
                       + ((side2Light >> 4) & 0xF) + ((cornerLight >> 4) & 0xF);

            avgBlock = (avgBlock + 2) / 4; // Round
            avgSky = (avgSky + 2) / 4;

            result.light[v] = (avgBlock << 20) | (avgSky << 4);
        }
    }

    /**
     * Get the 3 corner-sharing offsets for a vertex of a face.
     * Returns {{side1}, {side2}, {corner}} offsets relative to the face-adjacent block.
     *
     * <p>For smooth AO, each vertex of a face quad samples 3 neighbors around
     * the face-adjacent block. The configuration depends on which face direction
     * we're looking at and which of the 4 vertices we're computing.</p>
     *
     * <p>The lookup table below covers all 6 face directions × 4 vertices.
     * For each face, the two tangent axes determine the neighbor offsets.</p>
     */
    private static int[][] getCornerOffsets(int face, int vertex) {
        return CORNER_OFFSET_TABLE[face * 4 + vertex];
    }

    /**
     * Full AO corner offset lookup: [face * 4 + vertex][side1/side2/corner][x,y,z]
     *
     * Face tangent axes:
     *   DOWN  (-Y): tangent1 = +X, tangent2 = +Z
     *   UP   (+Y): tangent1 = +X, tangent2 = +Z
     *   NORTH (-Z): tangent1 = -X, tangent2 = +Y
     *   SOUTH (+Z): tangent1 = +X, tangent2 = +Y
     *   WEST  (-X): tangent1 = +Z, tangent2 = +Y
     *   EAST  (+X): tangent1 = -Z, tangent2 = +Y
     *
     * For each vertex, we sample the two adjacent edges and the diagonal corner
     * in the tangent plane of the face.
     */
    private static final int[][][] CORNER_OFFSET_TABLE = {
        // FACE 0: DOWN (-Y) — tangent1 = +X, tangent2 = +Z
        // vertex 0 (0,0,1): side1 = -X, side2 = +Z, corner = (-X,+Z)
        {{-1, 0, 0}, {0, 0, 1}, {-1, 0, 1}},
        // vertex 1 (1,0,1): side1 = +X, side2 = +Z, corner = (+X,+Z)
        {{1, 0, 0}, {0, 0, 1}, {1, 0, 1}},
        // vertex 2 (1,0,0): side1 = +X, side2 = -Z, corner = (+X,-Z)
        {{1, 0, 0}, {0, 0, -1}, {1, 0, -1}},
        // vertex 3 (0,0,0): side1 = -X, side2 = -Z, corner = (-X,-Z)
        {{-1, 0, 0}, {0, 0, -1}, {-1, 0, -1}},

        // FACE 1: UP (+Y) — tangent1 = +X, tangent2 = +Z
        // vertex 0 (0,1,0): side1 = -X, side2 = -Z, corner
        {{-1, 0, 0}, {0, 0, -1}, {-1, 0, -1}},
        // vertex 1 (1,1,0): side1 = +X, side2 = -Z
        {{1, 0, 0}, {0, 0, -1}, {1, 0, -1}},
        // vertex 2 (1,1,1): side1 = +X, side2 = +Z
        {{1, 0, 0}, {0, 0, 1}, {1, 0, 1}},
        // vertex 3 (0,1,1): side1 = -X, side2 = +Z
        {{-1, 0, 0}, {0, 0, 1}, {-1, 0, 1}},

        // FACE 2: NORTH (-Z) — tangent1 = -X, tangent2 = +Y
        // vertex 0 (1,1,0): side1 = +X, side2 = +Y (note: -X tangent means +X for this vertex)
        {{1, 0, 0}, {0, 1, 0}, {1, 1, 0}},
        // vertex 1 (0,1,0): side1 = -X, side2 = +Y
        {{-1, 0, 0}, {0, 1, 0}, {-1, 1, 0}},
        // vertex 2 (0,0,0): side1 = -X, side2 = -Y
        {{-1, 0, 0}, {0, -1, 0}, {-1, -1, 0}},
        // vertex 3 (1,0,0): side1 = +X, side2 = -Y
        {{1, 0, 0}, {0, -1, 0}, {1, -1, 0}},

        // FACE 3: SOUTH (+Z) — tangent1 = +X, tangent2 = +Y
        // vertex 0 (0,1,1): side1 = -X, side2 = +Y
        {{-1, 0, 0}, {0, 1, 0}, {-1, 1, 0}},
        // vertex 1 (1,1,1): side1 = +X, side2 = +Y
        {{1, 0, 0}, {0, 1, 0}, {1, 1, 0}},
        // vertex 2 (1,0,1): side1 = +X, side2 = -Y
        {{1, 0, 0}, {0, -1, 0}, {1, -1, 0}},
        // vertex 3 (0,0,1): side1 = -X, side2 = -Y
        {{-1, 0, 0}, {0, -1, 0}, {-1, -1, 0}},

        // FACE 4: WEST (-X) — tangent1 = +Z, tangent2 = +Y
        // vertex 0 (0,1,0): side1 = -Z, side2 = +Y
        {{0, 0, -1}, {0, 1, 0}, {0, 1, -1}},
        // vertex 1 (0,1,1): side1 = +Z, side2 = +Y
        {{0, 0, 1}, {0, 1, 0}, {0, 1, 1}},
        // vertex 2 (0,0,1): side1 = +Z, side2 = -Y
        {{0, 0, 1}, {0, -1, 0}, {0, -1, 1}},
        // vertex 3 (0,0,0): side1 = -Z, side2 = -Y
        {{0, 0, -1}, {0, -1, 0}, {0, -1, -1}},

        // FACE 5: EAST (+X) — tangent1 = -Z, tangent2 = +Y
        // vertex 0 (1,1,1): side1 = +Z, side2 = +Y
        {{0, 0, 1}, {0, 1, 0}, {0, 1, 1}},
        // vertex 1 (1,1,0): side1 = -Z, side2 = +Y
        {{0, 0, -1}, {0, 1, 0}, {0, 1, -1}},
        // vertex 2 (1,0,0): side1 = -Z, side2 = -Y
        {{0, 0, -1}, {0, -1, 0}, {0, -1, -1}},
        // vertex 3 (1,0,1): side1 = +Z, side2 = -Y
        {{0, 0, 1}, {0, -1, 0}, {0, -1, 1}},
    };

    // ─── Callback interface ──────────────────────────────────────────

    @FunctionalInterface
    public interface NeighborLookup {
        /** Get packed light at a position */
        int getLight(int x, int y, int z);

        /** Check if block at position is opaque */
        default boolean isOpaque(int x, int y, int z) {
            return false;
        }
    }
}

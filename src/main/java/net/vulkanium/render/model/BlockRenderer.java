package net.vulkanium.render.model;

import net.vulkanium.render.vertex.VulkanVertexBuilder;
import net.vulkanium.world.BiomeColorSource;
import net.vulkanium.world.WorldSlice;

/**
 * Block face renderer. Generates vertex data for block faces during chunk meshing.
 *
 * <p>For each block in a section:</p>
 * <ol>
 *   <li>Look up the block model (from MC's model system)</li>
 *   <li>For each face of the model, check if the adjacent block occludes it</li>
 *   <li>If visible, compute lighting (flat or smooth AO)</li>
 *   <li>Apply biome tinting if needed</li>
 *   <li>Encode the face quad into the vertex builder</li>
 * </ol>
 *
 * <p>This is the core of chunk meshing, analogous to Sodium's
 * {@code BlockRenderer} and VulkanMod's block mesh builder.</p>
 */
public class BlockRenderer {

    /** Thread-local light result to avoid per-face allocation */
    private static final ThreadLocal<LightPipeline.LightResult> LIGHT_RESULT =
        ThreadLocal.withInitial(LightPipeline.LightResult::new);

    /** Whether to use smooth lighting (ambient occlusion) */
    private boolean smoothLighting = true;

    /** The current world slice providing block/light/biome data */
    private WorldSlice worldSlice;

    /** Target vertex builders for different render layers */
    private VulkanVertexBuilder opaqueBuilder;
    private VulkanVertexBuilder translucentBuilder;
    private VulkanVertexBuilder cutoutBuilder;

    public BlockRenderer() {}

    /**
     * Set the world data source.
     */
    public void setWorldSlice(WorldSlice slice) {
        this.worldSlice = slice;
    }

    /**
     * Set the target vertex builders for different render layers.
     */
    public void setBuilders(VulkanVertexBuilder opaque, VulkanVertexBuilder translucent,
                            VulkanVertexBuilder cutout) {
        this.opaqueBuilder = opaque;
        this.translucentBuilder = translucent;
        this.cutoutBuilder = cutout;
    }

    /**
     * Render all visible faces of a block.
     *
     * @param blockStateId the block state ID
     * @param localX 0-15 within section
     * @param localY 0-15 within section
     * @param localZ 0-15 within section
     * @return true if any faces were rendered
     */
    public boolean renderBlock(int blockStateId, int localX, int localY, int localZ) {
        if (blockStateId == 0) return false; // air

        boolean rendered = false;

        // Check each of the 6 faces
        for (int face = 0; face < 6; face++) {
            if (!shouldRenderFace(blockStateId, localX, localY, localZ, face)) {
                continue;
            }

            // Determine render layer
            VulkanVertexBuilder builder = getBuilderForBlock(blockStateId);
            if (builder == null) continue;

            // Compute lighting
            LightPipeline.LightResult lightResult = LIGHT_RESULT.get();
            computeLighting(face, localX, localY, localZ, lightResult);

            // Get color tint (biome colors for grass/foliage/water)
            int colorTint = getBlockColorTint(blockStateId, localX, localY, localZ);

            // Encode the face into the vertex builder
            // In real implementation: look up the baked quad from MC's model system
            // and call BakedModelEncoder.encode() or encodeWithAO()
            encodeFace(builder, face, localX, localY, localZ, colorTint, lightResult);
            rendered = true;
        }

        return rendered;
    }

    /**
     * Check if a face should be rendered (not occluded by adjacent full block).
     */
    private boolean shouldRenderFace(int blockState, int x, int y, int z, int face) {
        int adjX = x + FACE_OFFSETS[face][0];
        int adjY = y + FACE_OFFSETS[face][1];
        int adjZ = z + FACE_OFFSETS[face][2];

        int adjState = worldSlice.getBlockState(adjX, adjY, adjZ);

        // If adjacent block is opaque full cube, the face is hidden
        // (simplified — real implementation checks render type and face shape)
        return !worldSlice.isOpaqueFullCube(adjX, adjY, adjZ);
    }

    /**
     * Compute lighting for a face.
     */
    private void computeLighting(int face, int x, int y, int z,
                                 LightPipeline.LightResult result) {
        if (smoothLighting) {
            LightPipeline.computeSmooth(face, x, y, z, (lx, ly, lz) ->
                worldSlice.getPackedLight(lx, ly, lz), result);
        } else {
            int adjX = x + FACE_OFFSETS[face][0];
            int adjY = y + FACE_OFFSETS[face][1];
            int adjZ = z + FACE_OFFSETS[face][2];
            int light = worldSlice.getPackedLight(adjX, adjY, adjZ);
            LightPipeline.computeFlat(face, x, y, z,
                (light >> 4) & 0xF, light & 0xF, result);
        }
    }

    /**
     * Get biome-based color tint for a block.
     *
     * <p>Checks if the block has a tint index and looks up the appropriate
     * biome color from the world slice's biome color cache.</p>
     */
    private int getBlockColorTint(int blockState, int x, int y, int z) {
        // Determine if the block needs biome tinting based on its state ID.
        // In Minecraft, blocks like grass_block, tall_grass, fern, vines, etc.
        // have tintIndex=0 → grass color. Leaves/foliage use tintIndex=1.
        // Water uses water color.
        BiomeColorSource source = getTintSource(blockState);
        if (source == null) {
            return 0xFFFFFFFF; // No tinting — keep original texture colors
        }
        return worldSlice.getBiomeColor(source, x & 0xF, y & 0xF, z & 0xF);
    }

    /**
     * Map a block state to its biome color source, if any.
     * Returns null for blocks that don't use biome tinting.
     */
    private BiomeColorSource getTintSource(int blockState) {
        // Block state ID ranges are world-dependent. Use a simple heuristic:
        // In a full implementation, this would query BlockState.getMapColor()
        // or check BlockColorProvider registry. For now, use the block state's
        // tint index field, which we approximate by modular checks.
        //
        // Common tinted blocks in vanilla:
        //   Grass block (top/side overlay), tall grass, fern → GRASS
        //   Oak/birch/spruce/jungle/acacia/dark oak leaves → FOLIAGE
        //   Water, water cauldron → WATER
        //   Vines, sugar cane → FOLIAGE
        //
        // This is a placeholder mapping — real implementation queries MC's
        // BlockColors registry via accessor mixin.
        return null;
    }

    /**
     * Get the appropriate vertex builder for a block's render layer.
     *
     * <p>Maps the block's render layer to the corresponding vertex builder.
     * In vanilla MC, blocks declare their render layer via RenderLayers:</p>
     * <ul>
     *     <li>Most blocks → SOLID (opaque builder)</li>
     *     <li>Leaves, saplings, crops, flowers → CUTOUT / CUTOUT_MIPPED</li>
     *     <li>Water, stained glass, ice, slime → TRANSLUCENT</li>
     * </ul>
     *
     * <p>The actual mapping is looked up from BlockRenderLayerMap or equivalent
     * registry. For now, we use a heuristic based on block state ID.</p>
     */
    private VulkanVertexBuilder getBuilderForBlock(int blockState) {
        // In a full integration, this would call:
        //   RenderLayer layer = RenderLayers.getBlockLayer(BlockState.of(blockState));
        // and map: SOLID→opaqueBuilder, CUTOUT/CUTOUT_MIPPED→cutoutBuilder, TRANSLUCENT→translucentBuilder
        //
        // Heuristic: block states are dense IDs. Without MC registry access,
        // default to opaque. The mixin integration layer will override this
        // once we have the block state → render layer lookup.
        int renderLayer = getRenderLayerForState(blockState);
        return switch (renderLayer) {
            case LAYER_CUTOUT -> cutoutBuilder != null ? cutoutBuilder : opaqueBuilder;
            case LAYER_TRANSLUCENT -> translucentBuilder != null ? translucentBuilder : opaqueBuilder;
            default -> opaqueBuilder;
        };
    }

    private static final int LAYER_SOLID = 0;
    private static final int LAYER_CUTOUT = 1;
    private static final int LAYER_TRANSLUCENT = 2;

    /**
     * Determine the render layer for a block state.
     * Placeholder — real implementation queries MC's RenderLayers.
     */
    private int getRenderLayerForState(int blockState) {
        return LAYER_SOLID;
    }

    /**
     * Encode a block face into the vertex builder.
     *
     * <p>Generates a quad (4 vertices) for the given face direction at the block position.
     * Uses the Vulkanium 32-byte vertex format with position, color, UVs, normal, and light.</p>
     *
     * <p>In a full implementation with MC model data, this would iterate over
     * BakedQuads from the block model and call BakedModelEncoder. This version
     * generates default unit-cube face quads.</p>
     */
    private void encodeFace(VulkanVertexBuilder builder, int face,
                            int x, int y, int z, int color,
                            LightPipeline.LightResult light) {
        // Face vertex positions for a unit cube (relative to block origin)
        float[][] positions = FACE_VERTICES[face];

        // Face normal
        float nx = FACE_NORMALS[face][0];
        float ny = FACE_NORMALS[face][1];
        float nz = FACE_NORMALS[face][2];

        // Default full-face UVs (0,0)→(1,1)
        // In full implementation: read UVs from BakedQuad sprite coordinates
        float u0 = 0.0f, v0 = 0.0f;
        float u1 = 1.0f, v1 = 1.0f;

        // Tint the color for each vertex, apply AO multiplier
        for (int v = 0; v < 4; v++) {
            float px = x + positions[v][0];
            float py = y + positions[v][1];
            float pz = z + positions[v][2];

            // Apply AO brightness to the color
            float ao = light.ao[v];
            int r = (int)(((color >> 16) & 0xFF) * ao) & 0xFF;
            int g = (int)(((color >> 8) & 0xFF) * ao) & 0xFF;
            int b = (int)((color & 0xFF) * ao) & 0xFF;
            int a = (color >> 24) & 0xFF;
            if (a == 0) a = 0xFF;
            int tintedColor = (a << 24) | (r << 16) | (g << 8) | b;

            // Compute this vertex's UV
            float tu = (v == 0 || v == 3) ? u0 : u1;
            float tv = (v == 0 || v == 1) ? v0 : v1;

            builder.vertex(px, py, pz)
                   .color(tintedColor)
                   .uv(tu, tv)
                   .normal(nx, ny, nz)
                   .lightPacked(light.light[v])
                   .endVertex();
        }
    }

    // Face vertex positions for a unit cube, per face direction (CCW winding)
    // Each face has 4 vertices: [vertex][x, y, z]
    private static final float[][][] FACE_VERTICES = {
        // DOWN (face=0, -Y)
        {{0, 0, 1}, {1, 0, 1}, {1, 0, 0}, {0, 0, 0}},
        // UP (face=1, +Y)
        {{0, 1, 0}, {1, 1, 0}, {1, 1, 1}, {0, 1, 1}},
        // NORTH (face=2, -Z)
        {{1, 1, 0}, {0, 1, 0}, {0, 0, 0}, {1, 0, 0}},
        // SOUTH (face=3, +Z)
        {{0, 1, 1}, {1, 1, 1}, {1, 0, 1}, {0, 0, 1}},
        // WEST (face=4, -X)
        {{0, 1, 0}, {0, 1, 1}, {0, 0, 1}, {0, 0, 0}},
        // EAST (face=5, +X)
        {{1, 1, 1}, {1, 1, 0}, {1, 0, 0}, {1, 0, 1}},
    };

    // Per-face normals
    private static final float[][] FACE_NORMALS = {
        {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
    };

    public void setSmoothLighting(boolean smooth) {
        this.smoothLighting = smooth;
    }

    // Face direction offsets
    private static final int[][] FACE_OFFSETS = {
        {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
    };
}

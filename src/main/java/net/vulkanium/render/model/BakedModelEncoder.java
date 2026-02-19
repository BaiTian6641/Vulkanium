package net.vulkanium.render.model;

import net.vulkanium.render.vertex.VulkanVertexBuilder;
import net.vulkanium.render.vertex.QuadView;

/**
 * Encodes baked model quads into Vulkanium's vertex format.
 *
 * <p>Takes Minecraft's BakedQuad data (MC vertex format: 32 bytes/vertex,
 * 8 ints/vertex) and writes it into the Vulkanium vertex buffer format
 * (32 bytes/vertex with half-float UVs, packed normals, etc.).</p>
 *
 * <p>This is the bridge between Minecraft's model system and Vulkanium's
 * Vulkan vertex buffers. Modeled after Sodium's {@code BakedModelEncoder}.</p>
 */
public class BakedModelEncoder {

    /** Reusable quad view for reading MC model data */
    private static final ThreadLocal<QuadView> QUAD_VIEW = ThreadLocal.withInitial(QuadView::new);

    private BakedModelEncoder() {}

    /**
     * Encode a baked quad into the Vulkanium vertex builder.
     *
     * @param builder   target vertex builder
     * @param quadData  MC baked quad vertex data (32 ints for 4 vertices)
     * @param colorTint biome/tint color to multiply (0xFFFFFFFF for no tint)
     * @param offsetX   world-space X offset (section origin)
     * @param offsetY   world-space Y offset
     * @param offsetZ   world-space Z offset
     * @param light     packed light value override, or -1 to use quad's light
     */
    public static void encode(VulkanVertexBuilder builder, int[] quadData,
                               int colorTint, float offsetX, float offsetY, float offsetZ,
                               int light) {
        QuadView quad = QUAD_VIEW.get();
        quad.setData(quadData, 0);

        for (int i = 0; i < 4; i++) {
            float x = quad.getX(i) + offsetX;
            float y = quad.getY(i) + offsetY;
            float z = quad.getZ(i) + offsetZ;

            // Apply color tint
            int vertexColor = quad.getColor(i);
            if (colorTint != 0xFFFFFFFF) {
                vertexColor = multiplyColor(vertexColor, colorTint);
            }

            // UV (atlas coordinates)
            float u = quad.getU(i);
            float v = quad.getV(i);

            // Light: use override or per-vertex
            int vertexLight = light >= 0 ? light : quad.getLight(i);

            // Normal (from quad face)
            int packedNormal = quad.getNormal(i);
            float nx = (byte) (packedNormal & 0xFF) / 127.0f;
            float ny = (byte) ((packedNormal >> 8) & 0xFF) / 127.0f;
            float nz = (byte) ((packedNormal >> 16) & 0xFF) / 127.0f;

            builder.vertex(x, y, z)
                   .color(vertexColor)
                   .uv(u, v)
                   .normal(nx, ny, nz)
                   .lightPacked(vertexLight)
                   .endVertex();
        }
    }

    /**
     * Encode with ambient occlusion colors and smooth lighting.
     *
     * @param aoColors per-vertex AO-adjusted colors (4 entries)
     * @param aoLight  per-vertex AO-adjusted light values (4 entries)
     */
    public static void encodeWithAO(VulkanVertexBuilder builder, int[] quadData,
                                     int[] aoColors, int[] aoLight,
                                     float offsetX, float offsetY, float offsetZ) {
        QuadView quad = QUAD_VIEW.get();
        quad.setData(quadData, 0);

        for (int i = 0; i < 4; i++) {
            float x = quad.getX(i) + offsetX;
            float y = quad.getY(i) + offsetY;
            float z = quad.getZ(i) + offsetZ;

            float u = quad.getU(i);
            float v = quad.getV(i);

            int packedNormal = quad.getNormal(i);
            float nx = (byte) (packedNormal & 0xFF) / 127.0f;
            float ny = (byte) ((packedNormal >> 8) & 0xFF) / 127.0f;
            float nz = (byte) ((packedNormal >> 16) & 0xFF) / 127.0f;

            builder.vertex(x, y, z)
                   .color(aoColors[i])
                   .uv(u, v)
                   .normal(nx, ny, nz)
                   .lightPacked(aoLight[i])
                   .endVertex();
        }
    }

    // ─── Color utilities ───────────────────────────────────────────────

    /**
     * Multiply two ARGB colors component-wise.
     */
    public static int multiplyColor(int color1, int color2) {
        int a = ((color1 >> 24) & 0xFF) * ((color2 >> 24) & 0xFF) / 255;
        int r = ((color1 >> 16) & 0xFF) * ((color2 >> 16) & 0xFF) / 255;
        int g = ((color1 >> 8) & 0xFF) * ((color2 >> 8) & 0xFF) / 255;
        int b = (color1 & 0xFF) * (color2 & 0xFF) / 255;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Get the maximum light from two packed light values.
     */
    public static int maxLight(int a, int b) {
        int blockA = (a >> 20) & 0xF, blockB = (b >> 20) & 0xF;
        int skyA = (a >> 4) & 0xF, skyB = (b >> 4) & 0xF;
        return (Math.max(blockA, blockB) << 20) | (Math.max(skyA, skyB) << 4);
    }
}

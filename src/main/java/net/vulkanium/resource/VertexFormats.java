package net.vulkanium.resource;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Vertex format definitions for Vulkanium's rendering pipelines.
 *
 * <h3>Terrain Vertex (32 bytes)</h3>
 * <pre>
 * Offset  Size  Type      Attribute
 * 0       12    float3    position (x, y, z)
 * 12      4     uint8x4   color (r, g, b, a)
 * 16      4     float16x2 texCoord (u, v)
 * 20      4     uint8x4   normal (nx, ny, nz, padding)
 * 24      4     uint8x4   tangent (tx, ty, tz, sign)
 * 28      2     uint16    lightCoord (packed sky << 8 | block)
 * 30      2     uint16    entityId / blockId
 * </pre>
 *
 * <p>This is 32 bytes vs VulkanMod's 24 bytes, adding normals, tangents, and
 * entity IDs needed for shader pack compatibility (OptiFine's at_tangent,
 * mc_Entity, etc.).</p>
 */
public final class VertexFormats {

    private VertexFormats() {}

    // ==================== Terrain (32 bytes) ====================

    public static final int TERRAIN_STRIDE = 32;

    public static VkVertexInputBindingDescription.Buffer terrainBindingDescription(MemoryStack stack) {
        return VkVertexInputBindingDescription.calloc(1, stack)
                .binding(0)
                .stride(TERRAIN_STRIDE)
                .inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
    }

    public static VkVertexInputAttributeDescription.Buffer terrainAttributeDescriptions(MemoryStack stack) {
        VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(7, stack);

        // location 0: position (float3)
        attrs.get(0).binding(0).location(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);

        // location 1: color (uint8x4 normalized)
        attrs.get(1).binding(0).location(1).format(VK_FORMAT_R8G8B8A8_UNORM).offset(12);

        // location 2: texCoord (float16x2)
        attrs.get(2).binding(0).location(2).format(VK_FORMAT_R16G16_SFLOAT).offset(16);

        // location 3: normal (int8x4 normalized → snorm)
        attrs.get(3).binding(0).location(3).format(VK_FORMAT_R8G8B8A8_SNORM).offset(20);

        // location 4: tangent (int8x4 normalized → snorm)
        attrs.get(4).binding(0).location(4).format(VK_FORMAT_R8G8B8A8_SNORM).offset(24);

        // location 5: lightCoord (uint16x2 → 2 packed values in 4 bytes)
        attrs.get(5).binding(0).location(5).format(VK_FORMAT_R16_UINT).offset(28);

        // location 6: entityId (uint16)
        attrs.get(6).binding(0).location(6).format(VK_FORMAT_R16_UINT).offset(30);

        return attrs;
    }

    // ==================== Entity (40 bytes) ====================

    public static final int ENTITY_STRIDE = 40;

    public static VkVertexInputBindingDescription.Buffer entityBindingDescription(MemoryStack stack) {
        return VkVertexInputBindingDescription.calloc(1, stack)
                .binding(0)
                .stride(ENTITY_STRIDE)
                .inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
    }

    public static VkVertexInputAttributeDescription.Buffer entityAttributeDescriptions(MemoryStack stack) {
        VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(6, stack);

        // location 0: position (float3) — 12 bytes
        attrs.get(0).binding(0).location(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);

        // location 1: color (uint8x4 normalized) — 4 bytes
        attrs.get(1).binding(0).location(1).format(VK_FORMAT_R8G8B8A8_UNORM).offset(12);

        // location 2: texCoord (float2) — 8 bytes
        attrs.get(2).binding(0).location(2).format(VK_FORMAT_R32G32_SFLOAT).offset(16);

        // location 3: normal (int8x4 snorm) — 4 bytes
        attrs.get(3).binding(0).location(3).format(VK_FORMAT_R8G8B8A8_SNORM).offset(24);

        // location 4: overlay (uint8x4 normalized) — 4 bytes
        attrs.get(4).binding(0).location(4).format(VK_FORMAT_R8G8B8A8_UNORM).offset(28);

        // location 5: lightCoord (uint16x2) — 4 bytes
        attrs.get(5).binding(0).location(5).format(VK_FORMAT_R16G16_UINT).offset(32);

        // Remaining 4 bytes: padding for alignment (entityId/boneIndex in future)

        return attrs;
    }

    // ==================== GUI / Blit (20 bytes) ====================

    public static final int GUI_STRIDE = 20;

    public static VkVertexInputBindingDescription.Buffer guiBindingDescription(MemoryStack stack) {
        return VkVertexInputBindingDescription.calloc(1, stack)
                .binding(0)
                .stride(GUI_STRIDE)
                .inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
    }

    public static VkVertexInputAttributeDescription.Buffer guiAttributeDescriptions(MemoryStack stack) {
        VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(3, stack);

        // location 0: position (float2) — 8 bytes
        attrs.get(0).binding(0).location(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0);

        // location 1: texCoord (float2) — 8 bytes
        attrs.get(1).binding(0).location(1).format(VK_FORMAT_R32G32_SFLOAT).offset(8);

        // location 2: color (uint8x4 normalized) — 4 bytes
        attrs.get(2).binding(0).location(2).format(VK_FORMAT_R8G8B8A8_UNORM).offset(16);

        return attrs;
    }

    // ==================== Fullscreen Triangle (no vertex input) ====================

    /**
     * For fullscreen passes (composite, blit), use gl_VertexIndex in the shader
     * to generate positions — no vertex buffer needed.
     */
    public static final int FULLSCREEN_STRIDE = 0;
}

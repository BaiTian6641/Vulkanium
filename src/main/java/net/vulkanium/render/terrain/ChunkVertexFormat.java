package net.vulkanium.render.terrain;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Extended 32-byte terrain vertex format for Vulkanium.
 *
 * <p>This format provides all data that OptiFine/Iris shader packs need while remaining
 * compact. Compared to VulkanMod's 24-byte format (missing normals/tangents/entityId)
 * and Sodium's 20-byte compact format, this format includes everything shader packs
 * require for proper rendering.</p>
 *
 * <h3>Layout (32 bytes per vertex)</h3>
 * <pre>
 * Offset  Size  Format           Location  GLSL Attribute      Description
 * ──────  ────  ──────           ────────  ──────────────      ───────────
 *  0       12   R32G32B32_SFLOAT    0      a_Position          Block-relative position (float3)
 * 12        4   R8G8B8A8_UNORM      1      a_Color             Vertex color (RGBA8)
 * 16        4   R16G16_SFLOAT       2      a_TexCoord          Atlas UV (half2)
 * 20        4   R8G8B8A8_SNORM      3      a_Normal            Octahedral normal (snorm8x4)
 * 24        4   R8G8B8A8_SNORM      4      a_Tangent           Octahedral tangent + sign (snorm8x4)
 * 28        2   R8G8_UINT           5      a_LightCoord        Lightmap (block, sky)
 * 30        2   R16_UINT            6      a_EntityId          Block state ID / mc_Entity
 * </pre>
 *
 * <h3>Shader Pack Mapping</h3>
 * <ul>
 *   <li>{@code gl_Vertex}           → decode from a_Position + region offset push constant</li>
 *   <li>{@code gl_Color}            → a_Color</li>
 *   <li>{@code gl_Normal}           → octahedral decode a_Normal.xy → vec3</li>
 *   <li>{@code gl_MultiTexCoord0}   → a_TexCoord (half-float → float)</li>
 *   <li>{@code gl_MultiTexCoord1}   → a_LightCoord (uint8 → float / 256.0)</li>
 *   <li>{@code at_tangent}          → octahedral decode a_Tangent.xy → vec3, sign = a_Tangent.w</li>
 *   <li>{@code mc_Entity}           → a_EntityId</li>
 *   <li>{@code at_midBlock}         → derived from vertex position relative to block center</li>
 * </ul>
 *
 * <h3>Octahedral Normal Encoding</h3>
 * <p>Normals and tangents are encoded using octahedral mapping, which compresses a unit
 * vector into 2 bytes (2×snorm8) with ~1° angular precision — sufficient for block lighting.
 * Decoding in the vertex shader:</p>
 * <pre>
 * vec3 decodeOctahedral(vec2 e) {
 *     vec3 v = vec3(e.xy, 1.0 - abs(e.x) - abs(e.y));
 *     if (v.z < 0) v.xy = (1.0 - abs(v.yx)) * sign(v.xy);
 *     return normalize(v);
 * }
 * </pre>
 */
public final class ChunkVertexFormat {

    private ChunkVertexFormat() {}

    /** Total bytes per terrain vertex. */
    public static final int STRIDE = 32;

    /** Number of vertex attributes in this format. */
    public static final int ATTRIBUTE_COUNT = 7;

    // Offsets for manual vertex writes
    public static final int OFFSET_POSITION   = 0;
    public static final int OFFSET_COLOR      = 12;
    public static final int OFFSET_TEXCOORD   = 16;
    public static final int OFFSET_NORMAL     = 20;
    public static final int OFFSET_TANGENT    = 24;
    public static final int OFFSET_LIGHTCOORD = 28;
    public static final int OFFSET_ENTITY_ID  = 30;

    /**
     * Creates VkVertexInputBindingDescription for single-buffer terrain vertex input.
     * Binding 0, per-vertex input rate.
     */
    public static VkVertexInputBindingDescription.Buffer bindingDescription(MemoryStack stack) {
        return VkVertexInputBindingDescription.calloc(1, stack)
                .binding(0)
                .stride(STRIDE)
                .inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
    }

    /**
     * Creates VkVertexInputAttributeDescription array for all 7 attributes.
     * Locations 0-6 correspond to the vertex shader layout qualifiers.
     */
    public static VkVertexInputAttributeDescription.Buffer attributeDescriptions(MemoryStack stack) {
        VkVertexInputAttributeDescription.Buffer attrs =
                VkVertexInputAttributeDescription.calloc(ATTRIBUTE_COUNT, stack);

        // location 0: position (float3, 12 bytes)
        attrs.get(0)
                .binding(0).location(0)
                .format(VK_FORMAT_R32G32B32_SFLOAT)
                .offset(OFFSET_POSITION);

        // location 1: color (uint8x4 normalized, 4 bytes)
        attrs.get(1)
                .binding(0).location(1)
                .format(VK_FORMAT_R8G8B8A8_UNORM)
                .offset(OFFSET_COLOR);

        // location 2: texCoord (float16x2, 4 bytes)
        attrs.get(2)
                .binding(0).location(2)
                .format(VK_FORMAT_R16G16_SFLOAT)
                .offset(OFFSET_TEXCOORD);

        // location 3: normal (snorm8x4 — only xy used for octahedral, zw padding)
        attrs.get(3)
                .binding(0).location(3)
                .format(VK_FORMAT_R8G8B8A8_SNORM)
                .offset(OFFSET_NORMAL);

        // location 4: tangent (snorm8x4 — xy for octahedral, w for handedness sign)
        attrs.get(4)
                .binding(0).location(4)
                .format(VK_FORMAT_R8G8B8A8_SNORM)
                .offset(OFFSET_TANGENT);

        // location 5: lightCoord (uint8x2 — block light, sky light)
        attrs.get(5)
                .binding(0).location(5)
                .format(VK_FORMAT_R8G8_UINT)
                .offset(OFFSET_LIGHTCOORD);

        // location 6: entityId (uint16 — block state ID for mc_Entity)
        attrs.get(6)
                .binding(0).location(6)
                .format(VK_FORMAT_R16_UINT)
                .offset(OFFSET_ENTITY_ID);

        return attrs;
    }

    // =========================================================
    // Encoding helpers — used by ChunkMeshBuilder when writing vertices
    // =========================================================

    /**
     * Encodes a unit normal vector into 2-byte octahedral representation.
     *
     * @param nx X component of unit normal
     * @param ny Y component of unit normal
     * @param nz Z component of unit normal
     * @return packed snorm8x2 suitable for writing at OFFSET_NORMAL
     */
    public static short encodeOctahedralNormal(float nx, float ny, float nz) {
        // Project onto octahedron
        float invL1 = 1.0f / (Math.abs(nx) + Math.abs(ny) + Math.abs(nz));
        float ox = nx * invL1;
        float oy = ny * invL1;

        // Reflect lower hemisphere
        if (nz < 0.0f) {
            float tmpX = (1.0f - Math.abs(oy)) * Math.signum(ox);
            float tmpY = (1.0f - Math.abs(ox)) * Math.signum(oy);
            ox = tmpX;
            oy = tmpY;
        }

        // Quantize to snorm8
        int sx = Math.max(-127, Math.min(127, Math.round(ox * 127.0f)));
        int sy = Math.max(-127, Math.min(127, Math.round(oy * 127.0f)));

        return (short) ((sx & 0xFF) | ((sy & 0xFF) << 8));
    }

    /**
     * Encodes a tangent vector + handedness sign into 4-byte octahedral representation.
     *
     * @param tx   X component of unit tangent
     * @param ty   Y component of unit tangent
     * @param tz   Z component of unit tangent
     * @param sign Handedness: +1.0 or -1.0
     * @return packed snorm8x4 suitable for writing at OFFSET_TANGENT
     */
    public static int encodeOctahedralTangent(float tx, float ty, float tz, float sign) {
        short encodedDir = encodeOctahedralNormal(tx, ty, tz);
        int signByte = sign >= 0.0f ? 127 : -127;
        // xy = octahedral direction, z = 0, w = sign
        return (encodedDir & 0xFFFF) | (0x00 << 16) | ((signByte & 0xFF) << 24);
    }

    /**
     * Packs block light and sky light into 2 bytes (uint8x2).
     *
     * @param blockLight 0-15 block light level
     * @param skyLight   0-15 sky light level
     * @return packed uint8x2 suitable for writing at OFFSET_LIGHTCOORD
     */
    public static short packLightCoord(int blockLight, int skyLight) {
        // Scale from 0-15 to 0-255 for shader interpolation
        int b = (blockLight & 0xF) * 17; // 15 * 17 = 255
        int s = (skyLight & 0xF) * 17;
        return (short) ((b & 0xFF) | ((s & 0xFF) << 8));
    }
}

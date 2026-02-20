package net.vulkanium.compat;

/**
 * Immutable snapshot of GL-style pipeline state captured from {@link VRenderSystem}.
 *
 * <p>Used as a key for pipeline cache lookups. Two draw calls with identical
 * VulkanPipelineState can share the same VkPipeline.</p>
 *
 * <p>Fields map to Vulkan pipeline create info structures:</p>
 * <ul>
 *   <li>Blend → VkPipelineColorBlendAttachmentState</li>
 *   <li>Depth → VkPipelineDepthStencilStateCreateInfo</li>
 *   <li>Cull → VkPipelineRasterizationStateCreateInfo.cullMode</li>
 *   <li>PolygonOffset → depthBias* dynamic state or pipeline fields</li>
 *   <li>ColorMask → VkPipelineColorBlendAttachmentState.colorWriteMask</li>
 * </ul>
 */
public record VulkanPipelineState(
        // Blend
        boolean blendEnabled,
        int blendSrcRGB,
        int blendDstRGB,
        int blendSrcAlpha,
        int blendDstAlpha,

        // Depth
        boolean depthTestEnabled,
        boolean depthWriteEnabled,
        int depthFunc,

        // Rasterization
        boolean cullEnabled,

        // Polygon offset
        boolean polygonOffsetEnabled,
        float polygonOffsetFactor,
        float polygonOffsetUnits,

        // Color write mask
        boolean colorMaskR,
        boolean colorMaskG,
        boolean colorMaskB,
        boolean colorMaskA
) {
    /**
     * Convert GL depth function to Vulkan VkCompareOp.
     * GL: NEVER=512, LESS=513, EQUAL=514, LEQUAL=515, GREATER=516, NOTEQUAL=517, GEQUAL=518, ALWAYS=519
     * VK: NEVER=0, LESS=1, EQUAL=2, LESS_OR_EQUAL=3, GREATER=4, NOT_EQUAL=5, GREATER_OR_EQUAL=6, ALWAYS=7
     */
    public int getVkCompareOp() {
        return depthFunc - 512; // GL enum values map directly with offset
    }

    /**
     * Convert GL blend factor to Vulkan VkBlendFactor.
     */
    public static int glBlendToVk(int glFactor) {
        // Must match VRenderSystem.glToVkBlendFactor() — GL and VK enum ordinals
        // do NOT correspond directly for SRC_ALPHA..DST_COLOR range.
        // Reference: Iris IrisRenderSystem blend conversions.
        return switch (glFactor) {
            case 0      -> 0;   // GL_ZERO              → VK_BLEND_FACTOR_ZERO
            case 1      -> 1;   // GL_ONE               → VK_BLEND_FACTOR_ONE
            case 0x0300 -> 2;   // GL_SRC_COLOR         → VK_BLEND_FACTOR_SRC_COLOR
            case 0x0301 -> 3;   // GL_ONE_MINUS_SRC_COLOR → VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR
            case 0x0302 -> 6;   // GL_SRC_ALPHA         → VK_BLEND_FACTOR_SRC_ALPHA
            case 0x0303 -> 7;   // GL_ONE_MINUS_SRC_ALPHA → VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
            case 0x0304 -> 8;   // GL_DST_ALPHA         → VK_BLEND_FACTOR_DST_ALPHA
            case 0x0305 -> 9;   // GL_ONE_MINUS_DST_ALPHA → VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA
            case 0x0306 -> 4;   // GL_DST_COLOR         → VK_BLEND_FACTOR_DST_COLOR
            case 0x0307 -> 5;   // GL_ONE_MINUS_DST_COLOR → VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR
            case 0x0308 -> 14;  // GL_SRC_ALPHA_SATURATE → VK_BLEND_FACTOR_SRC_ALPHA_SATURATE
            default -> 0;
        };
    }

    /**
     * Compute Vulkan color write mask from individual channel flags.
     * VK_COLOR_COMPONENT_R_BIT=1, G=2, B=4, A=8
     */
    public int getVkColorWriteMask() {
        int mask = 0;
        if (colorMaskR) mask |= 1;
        if (colorMaskG) mask |= 2;
        if (colorMaskB) mask |= 4;
        if (colorMaskA) mask |= 8;
        return mask;
    }

    /**
     * Vulkan cull mode from GL cull state.
     * VK_CULL_MODE_NONE=0, FRONT=1, BACK=2, FRONT_AND_BACK=3
     */
    public int getVkCullMode() {
        return cullEnabled ? 2 : 0; // BACK or NONE
    }

    /**
     * Packed hash for fast pipeline cache lookup.
     */
    public long packHash() {
        long h = 0;
        h |= (blendEnabled ? 1L : 0L);
        h |= ((long) blendSrcRGB) << 1;
        h |= ((long) blendDstRGB) << 5;
        h |= ((long) blendSrcAlpha) << 9;
        h |= ((long) blendDstAlpha) << 13;
        h |= (depthTestEnabled ? 1L : 0L) << 17;
        h |= (depthWriteEnabled ? 1L : 0L) << 18;
        h |= ((long) (depthFunc & 0x7)) << 19;
        h |= (cullEnabled ? 1L : 0L) << 22;
        h |= (polygonOffsetEnabled ? 1L : 0L) << 23;
        h |= ((long) getVkColorWriteMask()) << 24;
        return h;
    }
}

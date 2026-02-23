package net.vulkanium.render.gbuffer;

import net.vulkanium.resource.VulkaniumGraphicsPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Graphics pipeline specialized for MRT (Multiple Render Target) rendering.
 *
 * <p>Extends the base pipeline with N color blend attachment states — one per
 * active color target. Inactive targets (not in the DRAWBUFFERS/RENDERTARGETS
 * list) have their write mask set to 0.</p>
 *
 * <h3>Blend Configuration</h3>
 * <p>By default, all color targets use standard alpha blending:</p>
 * <pre>
 *   srcColorBlendFactor = SRC_ALPHA
 *   dstColorBlendFactor = ONE_MINUS_SRC_ALPHA
 *   srcAlphaBlendFactor = ONE
 *   dstAlphaBlendFactor = ZERO
 * </pre>
 *
 * <p>Shader packs can override blend per-buffer via {@code blendFunc} directives.</p>
 *
 * <h3>Integer Format Handling</h3>
 * <p>Targets with integer formats (R8UI, RGBA16I, etc.) must have blending
 * disabled. This is handled automatically based on {@link RenderTargetSettings}.</p>
 *
 * <h3>Depth/Stencil Configuration</h3>
 * <ul>
 *   <li>G-buffer fill: depth test=LESS_OR_EQUAL, depth write=true</li>
 *   <li>Composite: depth test=disabled, depth write=false</li>
 *   <li>Shadow: depth test=LESS_OR_EQUAL, depth write=true, bias enabled</li>
 * </ul>
 */
public class MRTGraphicsPipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/MRTPipeline");

    // ── Blend Mode ──

    /** Per-buffer blend override from shader packs */
    public record BlendOverride(
            int srcColor,
            int dstColor,
            int colorOp,
            int srcAlpha,
            int dstAlpha,
            int alphaOp
    ) {
        /** Standard alpha blend */
        public static final BlendOverride ALPHA = new BlendOverride(
                VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_OP_ADD,
                VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO, VK_BLEND_OP_ADD);

        /** Additive blend */
        public static final BlendOverride ADDITIVE = new BlendOverride(
                VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE, VK_BLEND_OP_ADD,
                VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO, VK_BLEND_OP_ADD);

        /** No blend (overwrite) */
        public static final BlendOverride NONE = new BlendOverride(
                VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO, VK_BLEND_OP_ADD,
                VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO, VK_BLEND_OP_ADD);
    }

    /**
     * Describes the complete pipeline state for an MRT pass.
     */
    public static class PipelineConfig {
        /** Draw buffers from RENDERTARGETS directive (indices of active targets) */
        public int[] drawBuffers = {0};

        /** Total number of color attachments in the render pass */
        public int totalColorAttachments = 1;

        /** Whether blending is enabled per attachment */
        public boolean[] blendEnabled;

        /** Per-attachment blend mode overrides (null entries = default alpha blend) */
        public BlendOverride[] blendOverrides;

        /** Whether integer targets exist (disables blend for those) */
        public boolean hasIntegerTargets = false;

        /** Depth test config */
        public boolean depthTestEnabled = true;
        public boolean depthWriteEnabled = true;
        public int depthCompareOp = VK_COMPARE_OP_LESS_OR_EQUAL;

        /** Depth bias for shadow maps */
        public boolean depthBiasEnabled = false;
        public float depthBiasConstant = 0.0f;
        public float depthBiasSlope = 0.0f;

        /** Face culling */
        public int cullMode = VK_CULL_MODE_BACK_BIT;
        // Positive-height viewport: OpenGL CCW triangles keep CCW signed area
        // in Vulkan, so VK_FRONT_FACE_COUNTER_CLOCKWISE is correct.
        // (Matches the 4 CCW sites already set in VulkanShaderpackPipeline.)
        public int frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;

        /** Polygon mode (for wireframe debug) */
        public int polygonMode = VK_POLYGON_MODE_FILL;

        /** Alpha test reference (from pack's const float alphaTestRef) */
        public float alphaTestRef = 0.1f;

        /**
         * Configures for a G-buffer fill pass.
         */
        public static PipelineConfig gbufferFill(int[] drawBuffers, int totalAttachments) {
            PipelineConfig config = new PipelineConfig();
            config.drawBuffers = drawBuffers;
            config.totalColorAttachments = totalAttachments;
            config.blendEnabled = new boolean[totalAttachments];
            config.blendOverrides = new BlendOverride[totalAttachments];
            // Enable blend only for active draw buffer targets
            for (int db : drawBuffers) {
                if (db < totalAttachments) {
                    config.blendEnabled[db] = true;
                    config.blendOverrides[db] = BlendOverride.ALPHA;
                }
            }
            config.depthTestEnabled = true;
            config.depthWriteEnabled = true;
            return config;
        }

        /**
         * Configures for a composite (fullscreen) pass.
         */
        public static PipelineConfig composite(int[] drawBuffers, int totalAttachments) {
            PipelineConfig config = new PipelineConfig();
            config.drawBuffers = drawBuffers;
            config.totalColorAttachments = totalAttachments;
            config.blendEnabled = new boolean[totalAttachments];
            config.blendOverrides = new BlendOverride[totalAttachments];
            for (int db : drawBuffers) {
                if (db < totalAttachments) {
                    config.blendEnabled[db] = false; // Composites usually overwrite
                    config.blendOverrides[db] = BlendOverride.NONE;
                }
            }
            config.depthTestEnabled = false;
            config.depthWriteEnabled = false;
            config.cullMode = VK_CULL_MODE_NONE;
            return config;
        }

        /**
         * Configures for a shadow map pass.
         */
        public static PipelineConfig shadow(int colorCount) {
            PipelineConfig config = new PipelineConfig();
            config.drawBuffers = new int[]{0};
            config.totalColorAttachments = colorCount;
            config.blendEnabled = new boolean[colorCount];
            config.blendOverrides = new BlendOverride[colorCount];
            config.depthTestEnabled = true;
            config.depthWriteEnabled = true;
            config.depthCompareOp = VK_COMPARE_OP_LESS_OR_EQUAL;
            config.depthBiasEnabled = true;
            config.depthBiasConstant = 4.0f;
            config.depthBiasSlope = 4.0f;
            config.cullMode = VK_CULL_MODE_NONE; // Render both sides for shadows
            return config;
        }

        /**
         * Computes the write mask for an attachment. Returns 0xF for active
         * draw buffer targets, 0 for inactive ones.
         */
        public int getWriteMask(int attachmentIndex) {
            for (int db : drawBuffers) {
                if (db == attachmentIndex) {
                    return VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                           VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
                }
            }
            return 0; // Inactive target — no writes
        }
    }

    /**
     * Parses a blend function string from shader pack properties.
     * Format: "srcRGB dstRGB srcAlpha dstAlpha" (GL constants).
     *
     * @param blendStr Blend function string (e.g., "GL_SRC_ALPHA GL_ONE_MINUS_SRC_ALPHA GL_ONE GL_ZERO")
     * @return BlendOverride, or null if parsing fails
     */
    public static BlendOverride parseBlendOverride(String blendStr) {
        if (blendStr == null || blendStr.isBlank()) return null;

        String[] parts = blendStr.trim().split("\\s+");
        if (parts.length < 2) return null;

        int srcColor = glBlendToVk(parts[0]);
        int dstColor = glBlendToVk(parts[1]);
        int srcAlpha = parts.length >= 3 ? glBlendToVk(parts[2]) : srcColor;
        int dstAlpha = parts.length >= 4 ? glBlendToVk(parts[3]) : dstColor;

        return new BlendOverride(srcColor, dstColor, VK_BLEND_OP_ADD, srcAlpha, dstAlpha, VK_BLEND_OP_ADD);
    }

    /**
     * Converts an OpenGL blend factor name to a Vulkan blend factor.
     */
    private static int glBlendToVk(String gl) {
        return switch (gl.toUpperCase().replace("GL_", "")) {
            case "ZERO", "0" -> VK_BLEND_FACTOR_ZERO;
            case "ONE", "1" -> VK_BLEND_FACTOR_ONE;
            case "SRC_COLOR" -> VK_BLEND_FACTOR_SRC_COLOR;
            case "ONE_MINUS_SRC_COLOR" -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case "DST_COLOR" -> VK_BLEND_FACTOR_DST_COLOR;
            case "ONE_MINUS_DST_COLOR" -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            case "SRC_ALPHA" -> VK_BLEND_FACTOR_SRC_ALPHA;
            case "ONE_MINUS_SRC_ALPHA" -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case "DST_ALPHA" -> VK_BLEND_FACTOR_DST_ALPHA;
            case "ONE_MINUS_DST_ALPHA" -> VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            default -> VK_BLEND_FACTOR_ONE;
        };
    }
}

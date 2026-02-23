package net.vulkanium.render.vertex;

import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

/**
 * Extended vertex formats for Vulkanium shaderpack terrain rendering.
 *
 * <p>MC's {@link DefaultVertexFormat#BLOCK} is 32 bytes/vertex.  Shaderpacks
 * need per-vertex extended data: {@code mc_Entity}, {@code mc_midTexCoord},
 * {@code at_tangent}, and {@code at_midBlock}.  {@link #TERRAIN} appends
 * these elements for a total of 52 bytes/vertex, matching the Iris layout.</p>
 *
 * <p>This class must NOT be loaded before the
 * {@link net.vulkanium.mixin.render.MixinVertexFormatElement} mixin is applied,
 * because the GENERIC-usage elements require the mixin to pass the
 * {@code supportsUsage} check.</p>
 */
public final class VulkaniumVertexFormats {

    /**
     * mc_Entity element — two shorts (blockId, renderType).
     * Uses GENERIC usage with index 11 (Iris-compatible).
     */
    public static final VertexFormatElement ENTITY_ELEMENT =
            new VertexFormatElement(11, VertexFormatElement.Type.SHORT,
                    VertexFormatElement.Usage.GENERIC, 2); // 4 bytes

    /**
     * mc_midTexCoord element — two floats (mid U, mid V).
     * Average of the quad's four corner texture coordinates.
     * GENERIC index 12.
     */
    public static final VertexFormatElement MID_TEXTURE_ELEMENT =
            new VertexFormatElement(12, VertexFormatElement.Type.FLOAT,
                    VertexFormatElement.Usage.GENERIC, 2); // 8 bytes

    /**
     * at_tangent element — four signed bytes (Tx, Ty, Tz, handedness).
     * Tangent vector computed from UV gradients; W = ±1 for TBN handedness.
     * GENERIC index 13.
     */
    public static final VertexFormatElement TANGENT_ELEMENT =
            new VertexFormatElement(13, VertexFormatElement.Type.BYTE,
                    VertexFormatElement.Usage.GENERIC, 4); // 4 bytes

    /**
     * at_midBlock element — three signed bytes (offset from block center × 64).
     * GENERIC index 14.  Padded to 4 bytes by the trailing Padding2 element.
     */
    public static final VertexFormatElement MID_BLOCK_ELEMENT =
            new VertexFormatElement(14, VertexFormatElement.Type.BYTE,
                    VertexFormatElement.Usage.GENERIC, 3); // 3 bytes

    /**
     * Extended terrain vertex format: BLOCK + extended shaderpack data = 52 bytes/vertex.
     *
     * <pre>
     * Offset  Size  Element
     *   0     12    Position       (FLOAT×3)
     *  12      4    Color          (UBYTE×4)
     *  16      8    UV0            (FLOAT×2)
     *  24      4    UV2            (SHORT×2)  lightmap
     *  28      3    Normal         (BYTE×3)
     *  31      1    Padding        (BYTE×1)
     *  32      4    mc_Entity      (SHORT×2)  block/fluid ID + render type
     *  36      8    mc_midTexCoord (FLOAT×2)  average quad UV
     *  44      4    at_tangent     (BYTE×4)   tangent + handedness
     *  48      3    at_midBlock    (BYTE×3)   block center offset
     *  51      1    Padding2       (BYTE×1)
     *  ──────────
     *  52 bytes total
     * </pre>
     */
    public static final VertexFormat TERRAIN = new VertexFormat(
            ImmutableMap.<String, VertexFormatElement>builder()
                    .put("Position",       DefaultVertexFormat.ELEMENT_POSITION)
                    .put("Color",          DefaultVertexFormat.ELEMENT_COLOR)
                    .put("UV0",            DefaultVertexFormat.ELEMENT_UV0)
                    .put("UV2",            DefaultVertexFormat.ELEMENT_UV2)
                    .put("Normal",         DefaultVertexFormat.ELEMENT_NORMAL)
                    .put("Padding",        DefaultVertexFormat.ELEMENT_PADDING)
                    .put("mc_Entity",      ENTITY_ELEMENT)
                    .put("mc_midTexCoord", MID_TEXTURE_ELEMENT)
                    .put("at_tangent",     TANGENT_ELEMENT)
                    .put("at_midBlock",    MID_BLOCK_ELEMENT)
                    .put("Padding2",       DefaultVertexFormat.ELEMENT_PADDING)
                    .build());

    private VulkaniumVertexFormats() {}
}

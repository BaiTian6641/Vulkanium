package net.vulkanium.render.vertex;

import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

/**
 * Extended vertex formats for Vulkanium shaderpack terrain rendering.
 *
 * <p>MC's {@link DefaultVertexFormat#BLOCK} is 32 bytes/vertex.  Shaderpacks
 * need {@code mc_Entity.x} (the block material ID from {@code block.properties})
 * per vertex.  {@link #TERRAIN} appends a SHORT×2 element (mc_Entity) for
 * 36 bytes/vertex total.</p>
 *
 * <p>This class must NOT be loaded before the
 * {@link net.vulkanium.mixin.render.MixinVertexFormatElement} mixin is applied,
 * because {@link #ENTITY_ELEMENT} uses {@link VertexFormatElement.Usage#GENERIC}
 * which requires the mixin to pass the {@code supportsUsage} check.</p>
 */
public final class VulkaniumVertexFormats {

    /**
     * mc_Entity element — two shorts (blockId, renderType).
     * Uses GENERIC usage with index 11 (Iris-compatible) so it doesn't collide
     * with any standard MC element.
     */
    public static final VertexFormatElement ENTITY_ELEMENT =
            new VertexFormatElement(11, VertexFormatElement.Type.SHORT,
                    VertexFormatElement.Usage.GENERIC, 2); // 4 bytes

    /**
     * Extended terrain vertex format: BLOCK + mc_Entity = 36 bytes/vertex.
     *
     * <pre>
     * Offset  Size  Element
     *   0     12    Position   (FLOAT×3)
     *  12      4    Color      (UBYTE×4)
     *  16      8    UV0        (FLOAT×2)
     *  24      4    UV2        (SHORT×2)  lightmap
     *  28      3    Normal     (BYTE×3)
     *  31      1    Padding    (BYTE×1)
     *  32      4    mc_Entity  (SHORT×2)  ← NEW
     *  ──────────
     *  36 bytes total
     * </pre>
     */
    public static final VertexFormat TERRAIN = new VertexFormat(
            ImmutableMap.<String, VertexFormatElement>builder()
                    .put("Position", DefaultVertexFormat.ELEMENT_POSITION)
                    .put("Color",    DefaultVertexFormat.ELEMENT_COLOR)
                    .put("UV0",      DefaultVertexFormat.ELEMENT_UV0)
                    .put("UV2",      DefaultVertexFormat.ELEMENT_UV2)
                    .put("Normal",   DefaultVertexFormat.ELEMENT_NORMAL)
                    .put("Padding",  DefaultVertexFormat.ELEMENT_PADDING)
                    .put("mc_Entity", ENTITY_ELEMENT)
                    .build());

    private VulkaniumVertexFormats() {}
}

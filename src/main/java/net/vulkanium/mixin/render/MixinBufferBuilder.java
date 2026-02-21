package net.vulkanium.mixin.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferVertexConsumer;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.DefaultedVertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.vulkanium.Vulkanium;
import net.vulkanium.render.vertex.BlockSensitiveBufferBuilder;
import net.vulkanium.render.vertex.VulkaniumVertexFormats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/**
 * Extends terrain vertex format with mc_Entity (block material ID) when a
 * shaderpack is active.
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>{@code begin()} — swaps {@link DefaultVertexFormat#BLOCK} (32 B) for
 *       {@link VulkaniumVertexFormats#TERRAIN} (36 B).  This disables MC's
 *       {@code fastFormat} flag, forcing the slow element-by-element path.</li>
 *   <li>{@code vertex(14 params)} — fast-path override.  When extending, we
 *       write all 36 bytes at hardcoded offsets and cancel the original
 *       (which would be a no-op since fastFormat is false).</li>
 *   <li>{@code endVertex()} — for the slow (chain) path, writes mc_Entity
 *       into the current element slot and calls nextElement() to wrap
 *       elementIndex back to 0 before vanilla checks it.</li>
 * </ol>
 *
 * <h3>Block ID injection</h3>
 * {@link MixinBlockRenderDispatcher} calls {@link #beginBlock}/{@link #endBlock}
 * around each {@code renderBatched} / {@code renderLiquid} call, providing the
 * per-block material ID resolved from {@code block.properties}.
 */
@Mixin(BufferBuilder.class)
public abstract class MixinBufferBuilder extends DefaultedVertexConsumer
        implements BufferVertexConsumer, BlockSensitiveBufferBuilder {

    // ── Shadowed MC fields ──
    @Shadow private ByteBuffer buffer;
    @Shadow private int nextElementByte;
    @Shadow private int elementIndex;
    @Shadow private VertexFormatElement currentElement;
    @Shadow private VertexFormat format;
    @Shadow private boolean building;

    @Shadow public abstract void nextElement();

    /** Inline replacement for BufferBuilder.normalIntValue — avoids @Shadow remap issues. */
    @Unique
    private static byte vulkanium$normalInt(float v) {
        return (byte) ((int) (net.minecraft.util.Mth.clamp(v, -1.0f, 1.0f) * 127.0f) & 0xFF);
    }

    // ── Vulkanium extension state ──
    @Unique private boolean extending = false;
    @Unique private short currentBlock = -1;
    @Unique private short currentRenderType = -1;
    @Unique private boolean fastPathUsed = false;

    // ═══════════════════════════════════════════════════════════════
    //  BlockSensitiveBufferBuilder interface
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void beginBlock(short blockId, short renderType,
                           int localPosX, int localPosY, int localPosZ) {
        this.currentBlock = blockId;
        this.currentRenderType = renderType;
    }

    @Override
    public void endBlock() {
        this.currentBlock = -1;
        this.currentRenderType = -1;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Format swap on begin()
    // ═══════════════════════════════════════════════════════════════

    @ModifyVariable(method = "begin", at = @At("HEAD"), argsOnly = true)
    private VertexFormat vulkanium$extendFormat(VertexFormat fmt) {
        extending = false;
        currentBlock = -1;
        currentRenderType = -1;
        fastPathUsed = false;

        if (!Vulkanium.isShaderpackPipelineActive()) return fmt;

        if (fmt == DefaultVertexFormat.BLOCK) {
            extending = true;
            return VulkaniumVertexFormats.TERRAIN;
        }
        return fmt;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Bulk vertex() fast-path redirect
    // ═══════════════════════════════════════════════════════════════

    @Inject(method = "vertex(FFFFFFFFFIIFFF)V", at = @At("HEAD"), cancellable = true)
    private void vulkanium$bulkVertex(float x, float y, float z,
                                       float r, float g, float b, float a,
                                       float u, float v,
                                       int overlay, int light,
                                       float nx, float ny, float nz,
                                       CallbackInfo ci) {
        if (!extending) return;

        int pos = this.nextElementByte;

        // Position (12 bytes @ 0)
        buffer.putFloat(pos,      x);
        buffer.putFloat(pos + 4,  y);
        buffer.putFloat(pos + 8,  z);

        // Color (4 bytes @ 12)
        buffer.put(pos + 12, (byte) ((int) (r * 255.0f)));
        buffer.put(pos + 13, (byte) ((int) (g * 255.0f)));
        buffer.put(pos + 14, (byte) ((int) (b * 255.0f)));
        buffer.put(pos + 15, (byte) ((int) (a * 255.0f)));

        // UV0 (8 bytes @ 16)
        buffer.putFloat(pos + 16, u);
        buffer.putFloat(pos + 20, v);

        // UV2 / lightmap (4 bytes @ 24) — BLOCK format has no UV1 (overlay)
        buffer.putShort(pos + 24, (short) (light & 0xFFFF));
        buffer.putShort(pos + 26, (short) (light >> 16 & 0xFFFF));

        // Normal (3 bytes @ 28)
        buffer.put(pos + 28, vulkanium$normalInt(nx));
        buffer.put(pos + 29, vulkanium$normalInt(ny));
        buffer.put(pos + 30, vulkanium$normalInt(nz));
        // Padding byte 31 — leave as zero

        // mc_Entity (4 bytes @ 32)
        buffer.putShort(pos + 32, currentBlock);
        buffer.putShort(pos + 34, currentRenderType);

        // Advance cursor past entire vertex (36 bytes) and complete
        this.nextElementByte = pos + 36;
        fastPathUsed = true;
        ((BufferBuilder) (Object) this).endVertex();

        ci.cancel();
    }

    // ═══════════════════════════════════════════════════════════════
    //  endVertex injection (slow / chain path)
    // ═══════════════════════════════════════════════════════════════

    @Inject(method = "endVertex", at = @At("HEAD"))
    private void vulkanium$writeEntityBeforeEndVertex(CallbackInfo ci) {
        if (!extending) return;

        if (fastPathUsed) {
            // Bulk path already wrote mc_Entity and advanced the cursor.
            // elementIndex is still 0 (fast path never calls nextElement).
            fastPathUsed = false;
            return;
        }

        // Chain path: vanilla wrote Position/Color/UV0/UV2/Normal.
        // nextElement() auto-skipped Padding, so currentElement is mc_Entity.
        // Write block material ID + render type, then advance to wrap index → 0.
        int base = this.nextElementByte;
        buffer.putShort(base,     currentBlock);
        buffer.putShort(base + 2, currentRenderType);
        this.nextElement(); // wraps elementIndex back to 0
    }
}

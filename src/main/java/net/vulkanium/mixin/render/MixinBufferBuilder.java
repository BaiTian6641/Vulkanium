package net.vulkanium.mixin.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferVertexConsumer;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.DefaultedVertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.vulkanium.Vulkanium;
import net.vulkanium.render.vertex.BlockSensitiveBufferBuilder;
import net.vulkanium.render.vertex.BufferBuilderPolygonView;
import net.vulkanium.render.vertex.ExtendedDataHelper;
import net.vulkanium.render.vertex.NormI8;
import net.vulkanium.render.vertex.NormalHelper;
import net.vulkanium.render.vertex.VulkaniumVertexFormats;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/**
 * Extends terrain vertex format with shaderpack extended data when a
 * shaderpack is active.  The extended format is 52 bytes/vertex (vs vanilla's
 * 32 bytes) and includes:
 * <ul>
 *   <li>{@code mc_Entity} — block material ID + render type (4 bytes)</li>
 *   <li>{@code mc_midTexCoord} — average quad UV (8 bytes)</li>
 *   <li>{@code at_tangent} — UV-gradient tangent + handedness (4 bytes)</li>
 *   <li>{@code at_midBlock} — block-center offset (3 bytes + 1 padding)</li>
 * </ul>
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>{@code begin()} — swaps BLOCK (32B) for TERRAIN (52B); disables
 *       MC's {@code fastFormat} flag.</li>
 *   <li>{@code vertex(14 params)} — fast-path: writes all 52 bytes at
 *       hardcoded offsets, with placeholders for quad-level data.</li>
 *   <li>{@code endVertex()} — slow path: writes per-vertex extended fields
 *       with placeholders, increments vertex counter.</li>
 *   <li>After every 4th vertex (QUADS) or 3rd vertex (TRIANGLES),
 *       {@code fillExtendedData()} reads back the polygon's vertex data and
 *       computes face normal, midTexCoord, tangent, then patches all
 *       vertices of the polygon.</li>
 * </ol>
 *
 * <h3>Block ID injection</h3>
 * {@link MixinBlockRenderDispatcher} calls {@link #beginBlock}/{@link #endBlock}
 * around each {@code renderBatched} / {@code renderLiquid} call, providing the
 * per-block material ID and local position for midBlock computation.
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
    @Shadow private VertexFormat.Mode mode;
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
    @Unique private int vertexCount = 0;
    @Unique private int currentLocalPosX = 0;
    @Unique private int currentLocalPosY = 0;
    @Unique private int currentLocalPosZ = 0;

    // Reusable objects for per-quad computation (avoid allocation in hot path)
    @Unique private final BufferBuilderPolygonView polygon = new BufferBuilderPolygonView();
    @Unique private final Vector3f faceNormal = new Vector3f();

    // ═══════════════════════════════════════════════════════════════
    //  BlockSensitiveBufferBuilder interface
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void beginBlock(short blockId, short renderType,
                           int localPosX, int localPosY, int localPosZ) {
        this.currentBlock = blockId;
        this.currentRenderType = renderType;
        this.currentLocalPosX = localPosX;
        this.currentLocalPosY = localPosY;
        this.currentLocalPosZ = localPosZ;
    }

    @Override
    public void endBlock() {
        this.currentBlock = -1;
        this.currentRenderType = -1;
        this.currentLocalPosX = 0;
        this.currentLocalPosY = 0;
        this.currentLocalPosZ = 0;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Format swap on begin()
    // ═══════════════════════════════════════════════════════════════

    @ModifyVariable(method = "begin", at = @At("HEAD"), argsOnly = true)
    private VertexFormat vulkanium$extendFormat(VertexFormat fmt) {
        extending = false;
        currentBlock = -1;
        currentRenderType = -1;
        currentLocalPosX = 0;
        currentLocalPosY = 0;
        currentLocalPosZ = 0;
        fastPathUsed = false;
        vertexCount = 0;

        if (!Vulkanium.isShaderpackPipelineActive()) return fmt;

        if (fmt == DefaultVertexFormat.BLOCK) {
            extending = true;
            return VulkaniumVertexFormats.TERRAIN;
        }
        return fmt;
    }

    @Inject(method = "reset()V", at = @At("HEAD"))
    private void vulkanium$onReset(CallbackInfo ci) {
        vertexCount = 0;
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

        // mc_midTexCoord placeholder (8 bytes @ 36) — filled by fillExtendedData
        buffer.putFloat(pos + 36, 0.0f);
        buffer.putFloat(pos + 40, 0.0f);

        // at_tangent placeholder (4 bytes @ 44) — filled by fillExtendedData
        buffer.putInt(pos + 44, 0);

        // at_midBlock (4 bytes @ 48) — can compute per-vertex immediately
        buffer.putInt(pos + 48, ExtendedDataHelper.computeMidBlock(
                x, y, z, currentLocalPosX, currentLocalPosY, currentLocalPosZ));

        // Advance cursor past entire vertex (52 bytes) and complete
        this.nextElementByte = pos + 52;
        fastPathUsed = true;
        ((BufferBuilder) (Object) this).endVertex();

        ci.cancel();
    }

    // ═══════════════════════════════════════════════════════════════
    //  endVertex injection (slow / chain path + quad fill trigger)
    // ═══════════════════════════════════════════════════════════════

    @Inject(method = "endVertex", at = @At("HEAD"))
    private void vulkanium$writeExtendedBeforeEndVertex(CallbackInfo ci) {
        if (!extending) return;

        if (fastPathUsed) {
            // Bulk path already wrote all extended fields and advanced the cursor.
            fastPathUsed = false;

            // Count and trigger quad fill
            vertexCount++;
            if (mode == VertexFormat.Mode.QUADS && vertexCount == 4
                    || mode == VertexFormat.Mode.TRIANGLES && vertexCount == 3) {
                fillExtendedData(vertexCount);
            }
            return;
        }

        // Chain path: vanilla wrote Position/Color/UV0/UV2/Normal.
        // nextElement() auto-skipped Padding, so currentElement is mc_Entity.
        int base = this.nextElementByte;

        // mc_Entity (4 bytes)
        buffer.putShort(base,     currentBlock);
        buffer.putShort(base + 2, currentRenderType);
        this.nextElement();

        // mc_midTexCoord placeholder (8 bytes) — filled by fillExtendedData
        buffer.putFloat(this.nextElementByte,     0.0f);
        buffer.putFloat(this.nextElementByte + 4, 0.0f);
        this.nextElement();

        // at_tangent placeholder (4 bytes) — filled by fillExtendedData
        buffer.putInt(this.nextElementByte, 0);
        this.nextElement();

        // at_midBlock (3 bytes) — read back vertex position from buffer
        int posOffset = this.nextElementByte - 48; // 48 bytes back to position start
        float vx = buffer.getFloat(posOffset);
        float vy = buffer.getFloat(posOffset + 4);
        float vz = buffer.getFloat(posOffset + 8);
        buffer.putInt(this.nextElementByte, ExtendedDataHelper.computeMidBlock(
                vx, vy, vz, currentLocalPosX, currentLocalPosY, currentLocalPosZ));
        this.nextElement(); // wraps elementIndex back to 0

        // Count and trigger quad fill
        vertexCount++;
        if (mode == VertexFormat.Mode.QUADS && vertexCount == 4
                || mode == VertexFormat.Mode.TRIANGLES && vertexCount == 3) {
            fillExtendedData(vertexCount);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Per-quad/tri extended data computation
    // ═══════════════════════════════════════════════════════════════

    /**
     * After a complete polygon (3 or 4 vertices) has been written, reads back
     * vertex positions and UVs to compute:
     * <ul>
     *   <li>mc_midTexCoord — average of all corner UVs</li>
     *   <li>Face normal — cross product of diagonals (for quads)</li>
     *   <li>at_tangent — from UV gradients with handedness</li>
     * </ul>
     * Then patches all vertices of the polygon with the computed values.
     *
     * <p>Offsets from end of vertex (52 bytes stride):
     * <pre>
     *   midU:    -16  (52 - 36 = offset of mc_midTexCoord.u from end)
     *   midV:    -12  (52 - 40)
     *   normal:  -24  (52 - 28)
     *   tangent:  -8  (52 - 44)
     * </pre>
     */
    @Unique
    private void fillExtendedData(int vertexAmount) {
        vertexCount = 0;

        int stride = format.getVertexSize();
        polygon.setup(buffer, nextElementByte, stride, vertexAmount);

        // Compute mid-texture coordinates (average of all vertex UVs)
        float midU = 0, midV = 0;
        for (int i = 0; i < vertexAmount; i++) {
            midU += polygon.u(i);
            midV += polygon.v(i);
        }
        midU /= vertexAmount;
        midV /= vertexAmount;

        // Offsets from end of each vertex (negative from nextElementByte - stride*vertex)
        // In our 52-byte TERRAIN format:
        //   mc_midTexCoord.u at byte 36 → from end: 52-36 = 16
        //   mc_midTexCoord.v at byte 40 → from end: 52-40 = 12
        //   normal at byte 28           → from end: 52-28 = 24
        //   at_tangent at byte 44       → from end: 52-44 = 8
        final int midUOff = 16;
        final int midVOff = 12;
        final int normalOff = 24;
        final int tangentOff = 8;

        if (vertexAmount == 3) {
            // Triangles: keep per-vertex normals (smooth shading)
            for (int vertex = 0; vertex < vertexAmount; vertex++) {
                int packedNormal = buffer.getInt(nextElementByte - normalOff - stride * vertex);
                int tangent = NormalHelper.computeTangentSmooth(
                        NormI8.unpackX(packedNormal),
                        NormI8.unpackY(packedNormal),
                        NormI8.unpackZ(packedNormal),
                        polygon);

                buffer.putFloat(nextElementByte - midUOff - stride * vertex, midU);
                buffer.putFloat(nextElementByte - midVOff - stride * vertex, midV);
                buffer.putInt(nextElementByte - tangentOff - stride * vertex, tangent);
            }
        } else {
            // Quads: compute face normal and overwrite all 4 vertices
            NormalHelper.computeFaceNormal(faceNormal, polygon);
            int packedNormal = NormI8.pack(faceNormal.x, faceNormal.y, faceNormal.z, 0.0f);
            int tangent = NormalHelper.computeTangent(faceNormal.x, faceNormal.y, faceNormal.z, polygon);

            for (int vertex = 0; vertex < vertexAmount; vertex++) {
                buffer.putFloat(nextElementByte - midUOff - stride * vertex, midU);
                buffer.putFloat(nextElementByte - midVOff - stride * vertex, midV);
                buffer.putInt(nextElementByte - normalOff - stride * vertex, packedNormal);
                buffer.putInt(nextElementByte - tangentOff - stride * vertex, tangent);
            }
        }
    }
}

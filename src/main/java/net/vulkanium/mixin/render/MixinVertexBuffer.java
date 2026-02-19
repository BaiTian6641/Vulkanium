package net.vulkanium.mixin.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.vulkanium.Vulkanium;
import net.vulkanium.compat.GlStateInterceptor;
import net.vulkanium.compat.VRenderSystem;
import net.vulkanium.core.VulkaniumMemory;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Intercepts MC's VertexBuffer to replace GL draw calls with Vulkan draws.
 *
 * <p>Uses persistent Vulkan vertex buffers instead of re-uploading vertex data
 * every frame: upload() creates a GPU-side VkBuffer once, and drawWithShader()
 * binds that buffer and issues the draw. This eliminates the per-frame 32 MB
 * streaming-buffer overflow that caused black terrain.</p>
 */
@Mixin(VertexBuffer.class)
public abstract class MixinVertexBuffer {

    @Shadow
    private int vertexBufferId;
    @Shadow
    private int indexBufferId;
    @Shadow
    private int arrayObjectId;
    @Shadow
    private int indexCount;
    @Shadow
    private VertexFormat.IndexType indexType;
    @Shadow
    private VertexFormat format;
    @Shadow
    private VertexFormat.Mode mode;

    /** Persistent Vulkan vertex buffer (allocated once in upload, reused every draw) */
    @Unique
    private long vkVertexBuffer = VK_NULL_HANDLE;

    /** VMA allocation for the persistent buffer */
    @Unique
    private long vkVertexAllocation = 0;

    /** Number of vertices in the persistent buffer */
    @Unique
    private int persistentVertexCount;

    /** Size in bytes of the persistent buffer */
    @Unique
    private int persistentBufferSize;

    /** Persistently mapped host pointer for zero-overhead uploads */
    @Unique
    private long persistentMappedPtr = 0;

    /**
     * Intercept upload() to create a persistent Vulkan vertex buffer.
     * Cancels the vanilla GL upload (glBufferData etc) since there's no GL context.
     * Manually releases the rendered buffer since vanilla's finally block won't run.
     */
    @Inject(method = "upload", at = @At("HEAD"), cancellable = true)
    private void onUpload(BufferBuilder.RenderedBuffer buffer, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;

        try {
            BufferBuilder.DrawState drawState = buffer.drawState();
            this.indexCount = drawState.indexCount();
            this.format = drawState.format();
            this.mode = drawState.mode();
            this.persistentVertexCount = drawState.vertexCount();

            ByteBuffer vtxBuf = buffer.vertexBuffer();
            if (vtxBuf != null && vtxBuf.remaining() > 0) {
                int dataSize = vtxBuf.remaining();

                // ALWAYS defer-free the old buffer and allocate a new one.
                // We must NOT reuse the same VkBuffer even if the size matches,
                // because in-flight command buffers (from previous frames) may still
                // be reading the old vertex data from this buffer. Overwriting a
                // HOST_COHERENT buffer while the GPU reads it causes data races,
                // leading to torn geometry: "some chunks missing, wrong texture,
                // wrong biome color" — especially visible on TRANSLUCENT water.
                if (vkVertexBuffer != VK_NULL_HANDLE) {
                    Vulkanium.deferBufferFree(vkVertexBuffer, vkVertexAllocation,
                            persistentBufferSize, persistentMappedPtr);
                    vkVertexBuffer = VK_NULL_HANDLE;
                    vkVertexAllocation = 0;
                    persistentMappedPtr = 0;
                }

                // Acquire buffer from pool (or allocate new if pool empty)
                // Pool buffers are persistently mapped — no map/unmap needed per upload
                net.vulkanium.core.ChunkBufferPool pool = Vulkanium.getChunkBufferPool();
                if (pool != null && pool.isInitialized()) {
                    net.vulkanium.core.ChunkBufferPool.PooledBuffer pb = pool.acquire(dataSize);
                    vkVertexBuffer = pb.buffer();
                    vkVertexAllocation = pb.allocation();
                    persistentBufferSize = pb.capacity();
                    persistentMappedPtr = pb.mappedPtr();
                } else {
                    // Fallback: direct VMA allocation + map
                    VulkaniumMemory.BufferAllocation alloc = Vulkanium.getVulkanMemory().createBuffer(
                            dataSize,
                            VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                    vkVertexBuffer = alloc.buffer();
                    vkVertexAllocation = alloc.allocation();
                    persistentBufferSize = dataSize;
                    persistentMappedPtr = Vulkanium.getVulkanMemory().map(alloc.allocation());
                }

                // Upload vertex data using the persistently mapped pointer
                // (zero-overhead: no VMA map/unmap per upload)
                int oldPos = vtxBuf.position();
                MemoryUtil.memCopy(MemoryUtil.memAddress(vtxBuf), persistentMappedPtr, dataSize);
                vtxBuf.position(oldPos);

                // Notify RT pipeline about terrain mesh uploads for BLAS construction.
                // Terrain uses BLOCK format (has UV2 lightmap), distinguishing it from
                // clouds (no UV2), GUI, sky, etc.
                if (vulkanium$isTerrainFormat(this.format)) {
                    Vulkanium.notifyChunkMeshUploaded(vkVertexBuffer, persistentVertexCount,
                            this.format.getVertexSize(), dataSize);
                }
            } else {
                persistentVertexCount = 0;
            }
        } finally {
            buffer.release(); // Must release since we're cancelling vanilla's upload_() which does this
        }
        ci.cancel();
    }

    /**
     * Intercept drawWithShader to render the persistent VkBuffer through Vulkan.
     *
     * <p>MC's vanilla drawWithShader sets shader uniforms and calls GL draw.
     * We bypass all of that: bind the persistent VkBuffer and issue a Vulkan draw,
     * temporarily setting the MVP matrices from the method arguments.</p>
     */
    @Inject(method = "drawWithShader", at = @At("HEAD"), cancellable = true)
    private void onDrawWithShader(Matrix4f modelViewMatrix, Matrix4f projectionMatrix, ShaderInstance shader, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;

        if (this.vkVertexBuffer == VK_NULL_HANDLE || this.indexCount <= 0 || this.persistentVertexCount <= 0) {
            ci.cancel();
            return;
        }

        // Save current VRenderSystem matrices so we can restore after this draw
        Matrix4f prevProj = new Matrix4f(VRenderSystem.getProjectionMatrix());
        Matrix4f prevMV = new Matrix4f(VRenderSystem.getModelViewMatrix());

        // Apply the per-draw matrices from the arguments
        // (terrain rendering passes per-chunk modelview; sky passes its own projection)
        VRenderSystem.setModelViewMatrix(modelViewMatrix);
        VRenderSystem.setProjectionMatrix(projectionMatrix, VRenderSystem.getVertexSorting());

        // Set shader name for pipeline selection
        if (shader != null) {
            VRenderSystem.setShader(shader);
        }

        // Issue a persistent-VBO draw (no data copy — binds the already-uploaded buffer)
        Vulkanium.recordDrawPersistent(
                this.vkVertexBuffer, this.persistentVertexCount,
                this.mode, this.format.getVertexSize(), this.format);

        // Restore previous matrices (other draws may depend on them)
        VRenderSystem.setModelViewMatrix(prevMV);
        VRenderSystem.setProjectionMatrix(prevProj, VRenderSystem.getVertexSorting());

        ci.cancel();
    }

    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private void onDraw(CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;

        if (this.vkVertexBuffer != VK_NULL_HANDLE && this.indexCount > 0 && this.persistentVertexCount > 0) {
            // If there's a ChunkOffset (set per-chunk by renderChunkLayer), apply it
            // as a translation to the model-view matrix before drawing
            Matrix4f prevMV = null;
            if (VRenderSystem.hasChunkOffset()) {
                prevMV = new Matrix4f(VRenderSystem.getModelViewMatrix());
                Matrix4f mv = new Matrix4f(prevMV);
                mv.translate(VRenderSystem.getChunkOffsetX(),
                             VRenderSystem.getChunkOffsetY(),
                             VRenderSystem.getChunkOffsetZ());
                VRenderSystem.setModelViewMatrix(mv);
            }

            Vulkanium.recordDrawPersistent(
                    this.vkVertexBuffer, this.persistentVertexCount,
                    this.mode, this.format.getVertexSize(), this.format);

            // Restore model-view if we modified it
            if (prevMV != null) {
                VRenderSystem.setModelViewMatrix(prevMV);
            }
        }
        ci.cancel();
    }

    @Inject(method = "bind", at = @At("HEAD"), cancellable = true)
    private void onBind(CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        // No GL VAO to bind in Vulkan — we draw from shadow data
        ci.cancel();
    }

    @Inject(method = "unbind", at = @At("HEAD"), cancellable = true)
    private static void onUnbind(CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        ci.cancel();
    }

    @Inject(method = "close", at = @At("HEAD"), cancellable = true)
    private void onClose(CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady()) return;
        // Defer-free persistent Vulkan vertex buffer.
        // CRITICAL: Must NOT use freeBufferImmediate here! In-flight command
        // buffers from previous frames may still be reading this buffer.
        // Immediate free causes use-after-free → stretched geometry, wrong
        // textures, and water transparency glitches during player movement.
        if (vkVertexBuffer != VK_NULL_HANDLE) {
            Vulkanium.deferBufferFree(vkVertexBuffer, vkVertexAllocation,
                    persistentBufferSize, persistentMappedPtr);
            vkVertexBuffer = VK_NULL_HANDLE;
            vkVertexAllocation = 0;
            persistentBufferSize = 0;
            persistentMappedPtr = 0;
        }
        persistentVertexCount = 0;
        // Cleanup GL ID tracking
        GlStateInterceptor.onDeleteBuffer(this.vertexBufferId);
        GlStateInterceptor.onDeleteBuffer(this.indexBufferId);
        ci.cancel();
    }

    /**
     * Checks if a vertex format is terrain (BLOCK format with UV2 lightmap).
     * Terrain formats have at least POSITION + UV(0) + COLOR + UV(2) + NORMAL.
     */
    @Unique
    private static boolean vulkanium$isTerrainFormat(VertexFormat format) {
        boolean hasUV2 = false;
        boolean hasNormal = false;
        for (com.mojang.blaze3d.vertex.VertexFormatElement element : format.getElements()) {
            if (element.getUsage() == com.mojang.blaze3d.vertex.VertexFormatElement.Usage.UV
                    && element.getIndex() == 2) {
                hasUV2 = true;
            }
            if (element.getUsage() == com.mojang.blaze3d.vertex.VertexFormatElement.Usage.NORMAL) {
                hasNormal = true;
            }
        }
        return hasUV2 && hasNormal;
    }
}

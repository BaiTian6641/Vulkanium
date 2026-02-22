package net.vulkanium.mixin.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.ShaderInstance;
import net.vulkanium.Vulkanium;
import net.vulkanium.compat.GlStateInterceptor;
import net.vulkanium.compat.VRenderSystem;
import net.vulkanium.core.VulkaniumMemory;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Intercepts MC's VertexBuffer to replace GL draw calls with Vulkan draws.
 *
 * <p>
 * Uses persistent Vulkan vertex buffers instead of re-uploading vertex data
 * every frame: upload() creates a GPU-side VkBuffer once, and drawWithShader()
 * binds that buffer and issues the draw. This eliminates the per-frame 32 MB
 * streaming-buffer overflow that caused black terrain.
 * </p>
 */
@Mixin(VertexBuffer.class)
public abstract class MixinVertexBuffer {

    @Unique
    private static final Logger VULKANIUM$LOGGER = LoggerFactory.getLogger("Vulkanium/TranslucentDebug");

    @Unique
    private static final boolean VULKANIUM$DEBUG_TRANSLUCENT = Boolean.getBoolean("vulkanium.debug.translucent");

    @Unique
    private static final boolean VULKANIUM$DEBUG_WATER = Boolean.getBoolean("vulkanium.debug.water");

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

    /**
     * Persistent Vulkan vertex buffer (allocated once in upload, reused every draw)
     */
    @Unique
    private long vkVertexBuffer = VK_NULL_HANDLE;

    /** VMA allocation for the persistent buffer */
    @Unique
    private long vkVertexAllocation = 0;

    /** Number of vertices in the persistent buffer */
    @Unique
    private int persistentVertexCount;

    /** Persistent Vulkan index buffer (preserves vanilla index ordering/sorting) */
    @Unique
    private long vkIndexBuffer = VK_NULL_HANDLE;

    /** VMA allocation for the persistent index buffer */
    @Unique
    private long vkIndexAllocation = 0;

    /** Number of indices in the persistent index buffer */
    @Unique
    private int persistentIndexCount;

    /** True when current draw state uses sequential (auto-generated) indices. */
    @Unique
    private boolean persistentSequentialIndex;

    /** Size in bytes of the persistent index buffer */
    @Unique
    private int persistentIndexBufferSize;

    /** Vulkan index type for the persistent index buffer */
    @Unique
    private int persistentIndexVkType = VK_INDEX_TYPE_UINT16;

    /** Size in bytes of the persistent buffer */
    @Unique
    private int persistentBufferSize;

    /** Persistently mapped host pointer for zero-overhead uploads */
    @Unique
    private long persistentMappedPtr = 0;

    @Unique
    private static boolean vulkanium$isTranslucentShaderName(String shaderName) {
        if (shaderName == null)
            return false;
        String name = shaderName.toLowerCase(java.util.Locale.ROOT);
        return name.contains("translucent")
                || name.contains("water")
                || name.contains("tripwire")
                || name.contains("cutout");
    }

    @Unique
    private static boolean vulkanium$isWaterShaderName(String shaderName) {
        if (shaderName == null)
            return false;
        String name = shaderName.toLowerCase(java.util.Locale.ROOT);
        return name.contains("water")
                || name.contains("translucent")
                || name.contains("tripwire")
                || name.contains("gbuffers_water")
                || name.contains("hand_water");
    }

    /**
     * Generates CPU-side distance-sorted quad indices for translucent terrain.
     * Mirrors VulkanMod's TerrainBufferBuilder.putSortedQuadIndices():
     * reads quad center positions from vertex data, sorts back-to-front from
     * camera, and generates triangle indices in sorted order.
     *
     * This is a fallback for correct alpha blending of water when vanilla
     * provides sequentialIndex=true (no custom sorted indices).
     */
    @Unique
    private void vulkanium$generateSortedQuadIndices(ByteBuffer vertexData, int vertexCount, int vertexStride) {
        int quadCount = vertexCount / 4;
        if (quadCount <= 0) return;

        // Ensure native byte order for reading floats from vertex data
        // (MC uses native byte order via LWJGL MemoryUtil, but Java ByteBuffer defaults to BIG_ENDIAN)
        ByteBuffer nativeOrder = vertexData.duplicate();
        nativeOrder.order(ByteOrder.nativeOrder());

        // Get camera position in section-local coordinates via chunk offset.
        // Chunk offset = (sectionOrigin - cameraPos), so camera in section-local
        // coords = -chunkOffset.
        float camLocalX = -net.vulkanium.compat.VRenderSystem.getChunkOffsetX();
        float camLocalY = -net.vulkanium.compat.VRenderSystem.getChunkOffsetY();
        float camLocalZ = -net.vulkanium.compat.VRenderSystem.getChunkOffsetZ();

        // If chunk offset is zero (not set yet), use section center as fallback
        // sorting origin - better than no sorting at all
        if (camLocalX == 0.0f && camLocalY == 0.0f && camLocalZ == 0.0f) {
            camLocalX = 8.0f;
            camLocalY = 8.0f;
            camLocalZ = 8.0f;
        }

        // Compute quad center positions and distances (like VulkanMod's makeQuadSortingPoints)
        float[] distances = new float[quadCount];
        int[] sortedIndices = new int[quadCount];
        int quadStride = vertexStride * 4; // bytes per quad (4 vertices)
        int thirdVertexOffset = vertexStride * 2; // offset to vertex[2] for center computation

        for (int q = 0; q < quadCount; q++) {
            int quadByteOffset = q * quadStride;
            // Average of vertex[0] and vertex[2] positions → quad center
            // (same approach as VulkanMod's makeQuadSortingPoints)
            float x0 = nativeOrder.getFloat(quadByteOffset);
            float y0 = nativeOrder.getFloat(quadByteOffset + 4);
            float z0 = nativeOrder.getFloat(quadByteOffset + 8);
            float x2 = nativeOrder.getFloat(quadByteOffset + thirdVertexOffset);
            float y2 = nativeOrder.getFloat(quadByteOffset + thirdVertexOffset + 4);
            float z2 = nativeOrder.getFloat(quadByteOffset + thirdVertexOffset + 8);

            float cx = (x0 + x2) * 0.5f;
            float cy = (y0 + y2) * 0.5f;
            float cz = (z0 + z2) * 0.5f;

            float dx = cx - camLocalX;
            float dy = cy - camLocalY;
            float dz = cz - camLocalZ;
            distances[q] = dx * dx + dy * dy + dz * dz;
            sortedIndices[q] = q;
        }

        // Simple insertion sort (stable, good for small N which chunk sections are)
        // Sort descending by distance (back-to-front for correct alpha blending)
        for (int i = 1; i < quadCount; i++) {
            float keyDist = distances[i];
            int keyIdx = sortedIndices[i];
            int j = i - 1;
            while (j >= 0 && distances[j] < keyDist) {
                distances[j + 1] = distances[j];
                sortedIndices[j + 1] = sortedIndices[j];
                j--;
            }
            distances[j + 1] = keyDist;
            sortedIndices[j + 1] = keyIdx;
        }

        // Generate sorted triangle indices: 6 per quad (0,1,2, 2,3,0 pattern)
        int indexCount = quadCount * 6;
        int indexDataSize = indexCount * 4; // u32 indices

        // Free any existing index buffer
        if (vkIndexBuffer != VK_NULL_HANDLE) {
            Vulkanium.deferBufferFree(vkIndexBuffer, vkIndexAllocation,
                    persistentIndexBufferSize, 0);
            vkIndexBuffer = VK_NULL_HANDLE;
            vkIndexAllocation = 0;
            persistentIndexBufferSize = 0;
            persistentIndexCount = 0;
        }

        VulkaniumMemory.BufferAllocation idxAlloc = Vulkanium.getVulkanMemory().createBuffer(
                indexDataSize,
                VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        vkIndexBuffer = idxAlloc.buffer();
        vkIndexAllocation = idxAlloc.allocation();
        persistentIndexBufferSize = indexDataSize;
        persistentIndexVkType = VK_INDEX_TYPE_UINT32;
        persistentIndexCount = indexCount;

        long ptr = Vulkanium.getVulkanMemory().map(idxAlloc.allocation());
        for (int i = 0; i < quadCount; i++) {
            int base = sortedIndices[i] * 4;
            long offset = (long) i * 6 * 4;
            MemoryUtil.memPutInt(ptr + offset,      base);
            MemoryUtil.memPutInt(ptr + offset + 4,  base + 1);
            MemoryUtil.memPutInt(ptr + offset + 8,  base + 2);
            MemoryUtil.memPutInt(ptr + offset + 12, base + 2);
            MemoryUtil.memPutInt(ptr + offset + 16, base + 3);
            MemoryUtil.memPutInt(ptr + offset + 20, base);
        }
        Vulkanium.getVulkanMemory().unmap(idxAlloc.allocation());

        // Mark as non-sequential since we now have proper sorted indices
        this.persistentSequentialIndex = false;

        if (VULKANIUM$DEBUG_TRANSLUCENT || VULKANIUM$DEBUG_WATER) {
            VULKANIUM$LOGGER.info(
                    "[SORT-GEN] Generated sorted quad indices: quadCount={} idxCount={} camLocal=({},{},{})",
                    quadCount, indexCount, camLocalX, camLocalY, camLocalZ);
        }
    }

    @Unique
    private void vulkanium$uploadPersistentIndex(ByteBuffer indexData) {
        if (indexData == null || indexData.remaining() <= 0 || this.indexCount <= 0)
            return;

        if (vkIndexBuffer != VK_NULL_HANDLE) {
            Vulkanium.deferBufferFree(vkIndexBuffer, vkIndexAllocation,
                    persistentIndexBufferSize, 0);
            vkIndexBuffer = VK_NULL_HANDLE;
            vkIndexAllocation = 0;
            persistentIndexBufferSize = 0;
            persistentIndexCount = 0;
        }

        ByteBuffer upload = indexData.duplicate();
        int indexDataSize = upload.remaining();
        VulkaniumMemory.BufferAllocation idxAlloc = Vulkanium.getVulkanMemory().createBuffer(
                indexDataSize,
                VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        vkIndexBuffer = idxAlloc.buffer();
        vkIndexAllocation = idxAlloc.allocation();
        persistentIndexBufferSize = indexDataSize;
        persistentIndexVkType = (this.indexType == VertexFormat.IndexType.INT)
                ? VK_INDEX_TYPE_UINT32
                : VK_INDEX_TYPE_UINT16;

        int indexElementSize = (persistentIndexVkType == VK_INDEX_TYPE_UINT32) ? 4 : 2;
        int uploadedIndexCount = indexDataSize / indexElementSize;
        persistentIndexCount = Math.min(this.indexCount, uploadedIndexCount);

        long idxPtr = Vulkanium.getVulkanMemory().map(idxAlloc.allocation());
        MemoryUtil.memCopy(MemoryUtil.memAddress(upload), idxPtr, indexDataSize);
        Vulkanium.getVulkanMemory().unmap(idxAlloc.allocation());
    }

    /**
     * Intercept upload() to create a persistent Vulkan vertex buffer.
     * Cancels the vanilla GL upload (glBufferData etc) since there's no GL context.
     * Manually releases the rendered buffer since vanilla's finally block won't
     * run.
     *
     * Upload cases:
     * 1. Full upload (vertex + index data): Initial chunk build with sorted indices.
     *    Both vertex buffer and index buffer are created/replaced.
     * 2. Full upload (vertex only, sequential): Opaque terrain build. Vertex buffer
     *    is created/replaced, auto-index buffers used at draw time.
     * 3. Index-only upload (re-sort): Camera moved, vanilla re-sorted translucent
     *    quads. Only the index buffer is replaced; vertex buffer is kept.
     */
    @Inject(method = "upload", at = @At("HEAD"), cancellable = true)
    private void onUpload(BufferBuilder.RenderedBuffer buffer, CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady())
            return;

        try {
            BufferBuilder.DrawState drawState = buffer.drawState();
            this.indexCount = drawState.indexCount();
            this.format = drawState.format();
            this.mode = drawState.mode();
            this.indexType = drawState.indexType();
            this.persistentSequentialIndex = drawState.sequentialIndex();

            // CRITICAL: Check drawState.indexOnly() to determine if this upload
            // contains new vertex data or is an index-only re-sort.
            //
            // For index-only re-sorts (translucent terrain when camera moves):
            //   - Vanilla's BufferBuilder writes ONLY sorted indices to its buffer
            //   - But vertexBuffer() returns a non-empty ByteBuffer because
            //     DrawState.vertexBufferSize() always returns vertexCount * stride
            //     (it doesn't know about indexOnly). The returned bytes are actually
            //     the sorted index data, NOT vertex positions.
            //   - Vanilla's uploadVertexBuffer explicitly checks drawState.indexOnly()
            //     and SKIPS the GL vertex upload in that case.
            //   - Without this check, we destroy the real vertex buffer and replace
            //     it with index bytes interpreted as vertex positions → stretched
            //     triangles and corruption for water/ice/portals during movement.
            boolean indexOnly = drawState.indexOnly();

            ByteBuffer vtxRaw = buffer.vertexBuffer();
            ByteBuffer idxRaw = buffer.indexBuffer();

            boolean hasVertexData = !indexOnly && vtxRaw != null && vtxRaw.remaining() > 0;
            boolean hasIndexData  = idxRaw != null && idxRaw.remaining() > 0;

            // ── Vertex buffer upload ───────────────────────────────────────
            if (hasVertexData) {
                this.persistentVertexCount = drawState.vertexCount();
                int dataSize = vtxRaw.remaining();

                // ALWAYS defer-free the old buffer and allocate a new one.
                // We must NOT reuse the same VkBuffer even if the size matches,
                // because in-flight command buffers (from previous frames) may still
                // be reading the old vertex data from this buffer.
                if (vkVertexBuffer != VK_NULL_HANDLE) {
                    Vulkanium.deferBufferFree(vkVertexBuffer, vkVertexAllocation,
                            persistentBufferSize, persistentMappedPtr);
                    vkVertexBuffer = VK_NULL_HANDLE;
                    vkVertexAllocation = 0;
                    persistentMappedPtr = 0;
                }

                // Acquire buffer from pool (or allocate new if pool empty)
                net.vulkanium.core.ChunkBufferPool pool = Vulkanium.getChunkBufferPool();
                if (pool != null && pool.isInitialized()) {
                    net.vulkanium.core.ChunkBufferPool.PooledBuffer pb = pool.acquire(dataSize);
                    vkVertexBuffer = pb.buffer();
                    vkVertexAllocation = pb.allocation();
                    persistentBufferSize = pb.capacity();
                    persistentMappedPtr = pb.mappedPtr();
                } else {
                    VulkaniumMemory.BufferAllocation alloc = Vulkanium.getVulkanMemory().createBuffer(
                            dataSize,
                            VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                    vkVertexBuffer = alloc.buffer();
                    vkVertexAllocation = alloc.allocation();
                    persistentBufferSize = dataSize;
                    persistentMappedPtr = Vulkanium.getVulkanMemory().map(alloc.allocation());
                }

                // Upload vertex data via persistently mapped pointer (zero-overhead)
                MemoryUtil.memCopy(MemoryUtil.memAddress(vtxRaw), persistentMappedPtr, dataSize);

                // Notify RT pipeline about terrain mesh uploads for BLAS construction
                if (vulkanium$isTerrainFormat(this.format)) {
                    Vulkanium.notifyChunkMeshUploaded(vkVertexBuffer, persistentVertexCount,
                            this.format.getVertexSize(), dataSize);
                }
            }
            // For index-only uploads (re-sorts), vertex buffer is preserved as-is.

            // ── Index buffer handling ──────────────────────────────────────
            if (hasIndexData && this.indexCount > 0) {
                // Vanilla provided custom sorted indices (initial build or re-sort).
                // Upload them directly — this is the primary path for translucent terrain.
                vulkanium$uploadPersistentIndex(idxRaw);
                this.persistentSequentialIndex = false;
            } else if (this.persistentSequentialIndex) {
                // Sequential indexing (opaque terrain): no custom sorted indices needed.
                // Drop any stale sorted index buffer from a previous translucent upload
                // (can happen if the same VertexBuffer object is reused between render types).
                if (vkIndexBuffer != VK_NULL_HANDLE) {
                    Vulkanium.deferBufferFree(vkIndexBuffer, vkIndexAllocation,
                            persistentIndexBufferSize, 0);
                    vkIndexBuffer = VK_NULL_HANDLE;
                    vkIndexAllocation = 0;
                    persistentIndexBufferSize = 0;
                }
                // Auto-index buffers will be used at draw time
                this.persistentIndexCount = this.indexCount;
            } else if (!hasIndexData && hasVertexData
                    && this.mode == VertexFormat.Mode.QUADS
                    && vulkanium$isTerrainFormat(this.format)
                    && this.vkVertexBuffer != VK_NULL_HANDLE) {
                // Edge case: non-sequential QUADS terrain with no index data provided.
                // This shouldn't normally happen for translucent (vanilla always sorts),
                // but as a safety fallback, generate CPU-sorted indices if we're in
                // a translucent layer, or fall back to auto-index.
                if (Vulkanium.isActiveTerrainLayerTranslucent()) {
                    vtxRaw.rewind();
                    vulkanium$generateSortedQuadIndices(vtxRaw, this.persistentVertexCount,
                            this.format.getVertexSize());
                } else {
                    // Non-translucent, non-sequential, no index data: use auto-index
                    this.persistentIndexCount = this.indexCount;
                    this.persistentSequentialIndex = true;
                }
            }
            // else: keep existing index buffer (e.g., index-only re-sort already handled)

            if (VULKANIUM$DEBUG_TRANSLUCENT && vulkanium$isTerrainFormat(this.format)) {
                VULKANIUM$LOGGER.info(
                        "[UPLOAD] mode={} hasVtx={} hasIdx={} vtxCount={} idxCount(draw={}/persist={}) idxType={} seq={} hasVB={} hasIB={}",
                        this.mode,
                        hasVertexData,
                        hasIndexData,
                        this.persistentVertexCount,
                        this.indexCount,
                        this.persistentIndexCount,
                        (this.persistentIndexVkType == VK_INDEX_TYPE_UINT32 ? "u32" : "u16"),
                        this.persistentSequentialIndex,
                        this.vkVertexBuffer != VK_NULL_HANDLE,
                        this.vkIndexBuffer != VK_NULL_HANDLE);
            }
        } finally {
            buffer.release(); // Must release since we're cancelling vanilla's upload_() which does this
        }
        ci.cancel();
    }

    @Inject(method = "uploadIndexBuffer", at = @At("HEAD"), require = 0)
    private void onUploadIndexBuffer(BufferBuilder.DrawState drawState, ByteBuffer indexBuffer,
            CallbackInfoReturnable<RenderSystem.AutoStorageIndexBuffer> cir) {
        if (!Vulkanium.isVulkanReady())
            return;

        if (drawState != null) {
            this.indexCount = drawState.indexCount();
            this.indexType = drawState.indexType();
        }

        vulkanium$uploadPersistentIndex(indexBuffer);

        if (VULKANIUM$DEBUG_TRANSLUCENT && vulkanium$isTerrainFormat(this.format)) {
            int ibBytes = indexBuffer != null ? indexBuffer.remaining() : 0;
            VULKANIUM$LOGGER.info(
                    "[UPLOAD-IB] terrain=true ibBytes={} idxCount(drawState={}/persistent={}) idxType={} hasIB={}",
                    ibBytes,
                    this.indexCount,
                    this.persistentIndexCount,
                    (this.persistentIndexVkType == VK_INDEX_TYPE_UINT32 ? "u32" : "u16"),
                    this.vkIndexBuffer != VK_NULL_HANDLE);
        }
    }

    @Inject(method = "method_43443", at = @At("HEAD"), require = 0, remap = false)
    private void onUploadIndexBufferObf(BufferBuilder.DrawState drawState, ByteBuffer indexBuffer,
            CallbackInfoReturnable<RenderSystem.AutoStorageIndexBuffer> cir) {
        if (!Vulkanium.isVulkanReady())
            return;

        if (drawState != null) {
            this.indexCount = drawState.indexCount();
            this.indexType = drawState.indexType();
        }

        vulkanium$uploadPersistentIndex(indexBuffer);

        if (VULKANIUM$DEBUG_TRANSLUCENT && vulkanium$isTerrainFormat(this.format)) {
            int ibBytes = indexBuffer != null ? indexBuffer.remaining() : 0;
            VULKANIUM$LOGGER.info(
                    "[UPLOAD-IB] obf=true terrain=true ibBytes={} idxCount(drawState={}/persistent={}) idxType={} hasIB={}",
                    ibBytes,
                    this.indexCount,
                    this.persistentIndexCount,
                    (this.persistentIndexVkType == VK_INDEX_TYPE_UINT32 ? "u32" : "u16"),
                    this.vkIndexBuffer != VK_NULL_HANDLE);
        }
    }

    /**
     * Intercept drawWithShader to render the persistent VkBuffer through Vulkan.
     *
     * <p>
     * MC's vanilla drawWithShader sets shader uniforms and calls GL draw.
     * We bypass all of that: bind the persistent VkBuffer and issue a Vulkan draw,
     * temporarily setting the MVP matrices from the method arguments.
     * </p>
     */
    @Inject(method = "drawWithShader", at = @At("HEAD"), cancellable = true)
    private void onDrawWithShader(Matrix4f modelViewMatrix, Matrix4f projectionMatrix, ShaderInstance shader,
            CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady())
            return;

        if (this.vkVertexBuffer == VK_NULL_HANDLE || this.indexCount <= 0 || this.persistentVertexCount <= 0) {
            ci.cancel();
            return;
        }

        // Apply the per-draw matrices from the arguments.
        // Do NOT save/restore — MC expects the RenderSystem state to persist
        // after VertexBuffer draws, and subsequent immediate draws (sun/moon via
        // BufferUploader) rely on the model-view being the camera rotation set here.
        // Restoring would clobber it back to a stale/identity matrix.
        VRenderSystem.setModelViewMatrix(modelViewMatrix);
        VRenderSystem.setProjectionMatrix(projectionMatrix, VRenderSystem.getVertexSorting());

        // Set shader name for pipeline selection
        if (shader != null) {
            VRenderSystem.setShader(shader);
        }

        float chunkOffsetX = VRenderSystem.getChunkOffsetX();
        float chunkOffsetY = VRenderSystem.getChunkOffsetY();
        float chunkOffsetZ = VRenderSystem.getChunkOffsetZ();
        boolean appliedLocalChunkOffset = false;

        if (vulkanium$isTerrainFormat(this.format)
                && (chunkOffsetX != 0.0f || chunkOffsetY != 0.0f || chunkOffsetZ != 0.0f)) {
            Matrix4f modelViewWithOffset = new Matrix4f(VRenderSystem.getModelViewMatrix())
                    .translate(chunkOffsetX, chunkOffsetY, chunkOffsetZ);
            VRenderSystem.setModelViewMatrix(modelViewWithOffset);
            VRenderSystem.setChunkOffset(0.0f, 0.0f, 0.0f);
            appliedLocalChunkOffset = true;
        }

        if (VULKANIUM$DEBUG_TRANSLUCENT && shader != null && vulkanium$isTranslucentShaderName(shader.getName())) {
            VULKANIUM$LOGGER.info(
                    "[DRAW_WS] shader='{}' mode={} vtxCount={} idxCount={} persistentIdxCount={} idxType={} hasVB={} hasIB={} chunkOffset=({},{},{})",
                    shader.getName(),
                    this.mode,
                    this.persistentVertexCount,
                    this.indexCount,
                    this.persistentIndexCount,
                    (this.persistentIndexVkType == VK_INDEX_TYPE_UINT32 ? "u32" : "u16"),
                    this.vkVertexBuffer != VK_NULL_HANDLE,
                    this.vkIndexBuffer != VK_NULL_HANDLE,
                    VRenderSystem.getChunkOffsetX(),
                    VRenderSystem.getChunkOffsetY(),
                    VRenderSystem.getChunkOffsetZ());
        }

                if ((VULKANIUM$DEBUG_TRANSLUCENT || VULKANIUM$DEBUG_WATER)
                    && shader != null
                    && vulkanium$isWaterShaderName(shader.getName())
                    && vulkanium$isTerrainFormat(this.format)) {
                    VULKANIUM$LOGGER.info(
                        "[WATER-WS] shader='{}' mode={} vtxCount={} drawStateIdxCount={} persistentIdxCount={} idxType={} hasIB={} chunkOffsetBeforeApply=({},{},{}) localOffsetApplied={} blend={} depthTest={} depthWrite={} cull={}",
                        shader.getName(),
                        this.mode,
                        this.persistentVertexCount,
                        this.indexCount,
                        this.persistentIndexCount,
                        (this.persistentIndexVkType == VK_INDEX_TYPE_UINT32 ? "u32" : "u16"),
                        this.vkIndexBuffer != VK_NULL_HANDLE,
                        chunkOffsetX,
                        chunkOffsetY,
                        chunkOffsetZ,
                        appliedLocalChunkOffset,
                        VRenderSystem.isBlendEnabled(),
                        VRenderSystem.isDepthTestEnabled(),
                        VRenderSystem.isDepthWriteEnabled(),
                        VRenderSystem.isCullEnabled());
                }

        // Issue a persistent-VBO draw (no data copy — binds the already-uploaded
        // buffer)
        Vulkanium.recordDrawPersistent(
                this.vkVertexBuffer, this.persistentVertexCount,
                this.mode, this.format.getVertexSize(), this.format,
            this.vkIndexBuffer, this.persistentIndexCount, this.persistentIndexVkType,
            this.persistentSequentialIndex);

        if (appliedLocalChunkOffset) {
            // Restore the terrain chunk offset for subsequent draws.
            // The MV and projection are intentionally NOT restored — MC expects
            // the per-draw argument matrices to persist on RenderSystem state.
            VRenderSystem.setChunkOffset(chunkOffsetX, chunkOffsetY, chunkOffsetZ);
            // Reset MV back to the original argument (without baked chunk offset)
            // so that the VRenderSystem MV reflects the camera rotation,
            // not camera + chunk offset.
            VRenderSystem.setModelViewMatrix(modelViewMatrix);
        }

        ci.cancel();
    }

    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private void onDraw(CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady())
            return;

        if (this.vkVertexBuffer != VK_NULL_HANDLE && this.indexCount > 0 && this.persistentVertexCount > 0) {
            float chunkOffsetX = VRenderSystem.getChunkOffsetX();
            float chunkOffsetY = VRenderSystem.getChunkOffsetY();
            float chunkOffsetZ = VRenderSystem.getChunkOffsetZ();
            boolean appliedLocalChunkOffset = false;
            Matrix4f prevMV = null;

            if (vulkanium$isTerrainFormat(this.format)
                    && (chunkOffsetX != 0.0f || chunkOffsetY != 0.0f || chunkOffsetZ != 0.0f)) {
                prevMV = new Matrix4f(VRenderSystem.getModelViewMatrix());
                Matrix4f modelViewWithOffset = new Matrix4f(VRenderSystem.getModelViewMatrix())
                        .translate(chunkOffsetX, chunkOffsetY, chunkOffsetZ);
                VRenderSystem.setModelViewMatrix(modelViewWithOffset);
                VRenderSystem.setChunkOffset(0.0f, 0.0f, 0.0f);
                appliedLocalChunkOffset = true;
            }

            Vulkanium.recordDrawPersistent(
                    this.vkVertexBuffer, this.persistentVertexCount,
                    this.mode, this.format.getVertexSize(), this.format,
                    this.vkIndexBuffer, this.persistentIndexCount, this.persistentIndexVkType,
                    this.persistentSequentialIndex);

            if (appliedLocalChunkOffset) {
                VRenderSystem.setChunkOffset(chunkOffsetX, chunkOffsetY, chunkOffsetZ);
                if (prevMV != null) {
                    VRenderSystem.setModelViewMatrix(prevMV);
                }
            }
        }
        ci.cancel();
    }

    @Inject(method = "bind", at = @At("HEAD"), cancellable = true)
    private void onBind(CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady())
            return;
        // No GL VAO to bind in Vulkan — we draw from shadow data
        ci.cancel();
    }

    @Inject(method = "unbind", at = @At("HEAD"), cancellable = true)
    private static void onUnbind(CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady())
            return;
        ci.cancel();
    }

    @Inject(method = "close", at = @At("HEAD"), cancellable = true)
    private void onClose(CallbackInfo ci) {
        if (!Vulkanium.isVulkanReady())
            return;
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

        if (vkIndexBuffer != VK_NULL_HANDLE) {
            Vulkanium.deferBufferFree(vkIndexBuffer, vkIndexAllocation,
                    persistentIndexBufferSize, 0);
            vkIndexBuffer = VK_NULL_HANDLE;
            vkIndexAllocation = 0;
            persistentIndexBufferSize = 0;
            persistentIndexCount = 0;
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

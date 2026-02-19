package net.vulkanium.render.pipeline;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.vulkanium.Vulkanium;
import net.vulkanium.compat.VRenderSystem;
import net.vulkanium.core.VulkaniumMemory;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;

import static net.vulkanium.core.VulkaniumInstance.checkResult;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Per-frame draw system: manages vertex buffers, uniform buffers, descriptor sets,
 * and issues draw commands within a render pass.
 *
 * <p>This is the equivalent of VulkanMod's {@code Drawer} class.</p>
 */
public class DrawBatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Draw");

    private static final int VERTEX_BUFFER_SIZE = 64 * 1024 * 1024; // 64 MB per frame (headroom for high chunk counts)
    private static final int UNIFORM_BUFFER_SIZE = 8 * 1024 * 1024;     // 8 MB per frame (2048-byte shaderpack UBO × 4096 draws)
    private static final int MAX_QUADS = 65536;
    private static final int MAX_TEXTURE_BINDINGS = BasicPipeline.getMaxTextureBindings();
    private static final int SHADERPACK_UBO_SIZE = 2048;

    private VkDevice device;
    private int framesInFlight;

    // Per-frame vertex buffers (HOST_VISIBLE for streaming)
    private long[] vertexBuffers;
    private long[] vertexAllocations;
    private long[] vertexMapped;    // Persistently mapped pointers
    private int[] vertexOffsets;    // Current write offset per frame

    // Per-frame uniform buffers
    private long[] uniformBuffers;
    private long[] uniformAllocations;
    private long[] uniformMapped;
    private int[] uniformOffsets;

    // Descriptor pool and sets (per-frame)
    private long descriptorPool = VK_NULL_HANDLE;
    private long[] descriptorSets;

    // Per-draw descriptor set allocation
    private long descriptorSetLayout = VK_NULL_HANDLE;
    private int descriptorSetIndex = 0;     // Next available set this frame
    private static final int MAX_DESCRIPTOR_SETS_PER_FRAME = 4096;

    // Quad index buffer (shared)
    private long quadIndexBuffer = VK_NULL_HANDLE;
    private long quadIndexAllocation;

    // Triangle fan index buffer (shared) — converts TRIANGLE_FAN to TRIANGLE_LIST
    private long fanIndexBuffer = VK_NULL_HANDLE;
    private long fanIndexAllocation;
    private static final int MAX_FAN_VERTICES = 1024;

    // Triangle strip index buffer (shared) — converts TRIANGLE_STRIP to TRIANGLE_LIST
    private long stripIndexBuffer = VK_NULL_HANDLE;
    private long stripIndexAllocation;
    private static final int MAX_STRIP_VERTICES = 8192;

    // Statistics
    private int drawCallsThisFrame = 0;
    private int vertexBytesThisFrame = 0;
    private boolean loggedVertexOverflow = false;
    private boolean loggedUniformOverflow = false;
    private boolean loggedDescriptorOverflow = false;

    public void initialize(VkDevice device, VulkaniumMemory memory, int framesInFlight,
                           long descriptorSetLayout) {
        this.device = device;
        this.framesInFlight = framesInFlight;

        vertexBuffers = new long[framesInFlight];
        vertexAllocations = new long[framesInFlight];
        vertexMapped = new long[framesInFlight];
        vertexOffsets = new int[framesInFlight];

        uniformBuffers = new long[framesInFlight];
        uniformAllocations = new long[framesInFlight];
        uniformMapped = new long[framesInFlight];
        uniformOffsets = new int[framesInFlight];

        for (int i = 0; i < framesInFlight; i++) {
            // Vertex buffer: HOST_VISIBLE + HOST_COHERENT for streaming
            VulkaniumMemory.BufferAllocation vb = memory.createBuffer(
                    VERTEX_BUFFER_SIZE,
                    VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            vertexBuffers[i] = vb.buffer();
            vertexAllocations[i] = vb.allocation();
            vertexMapped[i] = memory.map(vb.allocation());

            // Uniform buffer
            VulkaniumMemory.BufferAllocation ub = memory.createBuffer(
                    UNIFORM_BUFFER_SIZE,
                    VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            uniformBuffers[i] = ub.buffer();
            uniformAllocations[i] = ub.allocation();
            uniformMapped[i] = memory.map(ub.allocation());
        }

        // Create index buffers for all primitive modes
        createQuadIndexBuffer(memory);
        createFanIndexBuffer(memory);
        createStripIndexBuffer(memory);

        // Create descriptor pool and sets
        createDescriptorPool(descriptorSetLayout);

        LOGGER.info("Draw batcher initialized ({} frames, {} MB vertex, {} KB uniform per frame)",
                framesInFlight, VERTEX_BUFFER_SIZE / (1024 * 1024), UNIFORM_BUFFER_SIZE / 1024);
    }

    /**
     * Creates an index buffer with quad indices: 0,1,2,0,2,3, 4,5,6,4,6,7, ...
     */
    private void createQuadIndexBuffer(VulkaniumMemory memory) {
        int indexCount = MAX_QUADS * 6;
        int bufferSize = indexCount * 4; // 32-bit indices

        VulkaniumMemory.BufferAllocation ib = memory.createBuffer(
                bufferSize,
                VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        quadIndexBuffer = ib.buffer();
        quadIndexAllocation = ib.allocation();

        long mapped = memory.map(ib.allocation());
        ByteBuffer buf = MemoryUtil.memByteBuffer(mapped, bufferSize);
        for (int i = 0; i < MAX_QUADS; i++) {
            int base = i * 4;
            buf.putInt(base);     buf.putInt(base + 1); buf.putInt(base + 2);
            buf.putInt(base);     buf.putInt(base + 2); buf.putInt(base + 3);
        }
        memory.unmap(ib.allocation());
    }

    /**
     * Creates an index buffer for TRIANGLE_FAN → TRIANGLE_LIST conversion.
     * Fan: vertex 0 is center; triangles are (0,1,2), (0,2,3), (0,3,4), ...
     */
    private void createFanIndexBuffer(VulkaniumMemory memory) {
        int indexCount = (MAX_FAN_VERTICES - 2) * 3;
        int bufferSize = indexCount * 2; // 16-bit indices (short)

        VulkaniumMemory.BufferAllocation ib = memory.createBuffer(
                bufferSize,
                VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        fanIndexBuffer = ib.buffer();
        fanIndexAllocation = ib.allocation();

        long mapped = memory.map(ib.allocation());
        ByteBuffer buf = MemoryUtil.memByteBuffer(mapped, bufferSize);
        ShortBuffer idxs = buf.asShortBuffer();
        int j = 0;
        for (int i = 0; i < MAX_FAN_VERTICES - 2; i++) {
            idxs.put(j + 0, (short) 0);
            idxs.put(j + 1, (short) (i + 1));
            idxs.put(j + 2, (short) (i + 2));
            j += 3;
        }
        memory.unmap(ib.allocation());
        LOGGER.info("Fan index buffer created ({} max vertices, {} indices)", MAX_FAN_VERTICES, indexCount);
    }

    /**
     * Creates an index buffer for TRIANGLE_STRIP → TRIANGLE_LIST conversion.
     * Strip: triangles are (0,1,2), (1,2,3), (2,3,4), ...
     */
    private void createStripIndexBuffer(VulkaniumMemory memory) {
        int indexCount = (MAX_STRIP_VERTICES - 2) * 3;
        int bufferSize = indexCount * 2; // 16-bit indices (short)

        VulkaniumMemory.BufferAllocation ib = memory.createBuffer(
                bufferSize,
                VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        stripIndexBuffer = ib.buffer();
        stripIndexAllocation = ib.allocation();

        long mapped = memory.map(ib.allocation());
        ByteBuffer buf = MemoryUtil.memByteBuffer(mapped, bufferSize);
        ShortBuffer idxs = buf.asShortBuffer();
        int j = 0;
        for (int i = 0; i < MAX_STRIP_VERTICES - 2; i++) {
            idxs.put(j + 0, (short) i);
            idxs.put(j + 1, (short) (i + 1));
            idxs.put(j + 2, (short) (i + 2));
            j += 3;
        }
        memory.unmap(ib.allocation());
        LOGGER.info("Strip index buffer created ({} max vertices, {} indices)", MAX_STRIP_VERTICES, indexCount);
    }

    public long getFanIndexBuffer() { return fanIndexBuffer; }
    public long getStripIndexBuffer() { return stripIndexBuffer; }

    private void createDescriptorPool(long descriptorSetLayout) {
        this.descriptorSetLayout = descriptorSetLayout;

        // Phase 1: Create the descriptor pool (small stack usage)
        try (MemoryStack stack = stackPush()) {
            int maxSets = MAX_DESCRIPTOR_SETS_PER_FRAME;
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(maxSets);
            poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(maxSets * MAX_TEXTURE_BINDINGS);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)
                    .maxSets(maxSets)
                    .pPoolSizes(poolSizes);

            LongBuffer pPool = stack.longs(VK_NULL_HANDLE);
            int result = vkCreateDescriptorPool(device, poolInfo, null, pPool);
            checkResult(result, "Failed to create descriptor pool");
            descriptorPool = pPool.get(0);
        }

        // Phase 2: Pre-allocate all descriptor sets using HEAP buffers
        // (too large for LWJGL MemoryStack which is only ~8 KB)
        int maxSets = MAX_DESCRIPTOR_SETS_PER_FRAME;
        descriptorSets = new long[maxSets];
        LongBuffer layouts = MemoryUtil.memAllocLong(maxSets);
        LongBuffer pSets = MemoryUtil.memAllocLong(maxSets);
        try {
            for (int i = 0; i < maxSets; i++) layouts.put(i, descriptorSetLayout);

            try (MemoryStack stack = stackPush()) {
                VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                        .descriptorPool(descriptorPool)
                        .pSetLayouts(layouts);

                int result = vkAllocateDescriptorSets(device, allocInfo, pSets);
                checkResult(result, "Failed to allocate descriptor sets");
            }

            for (int i = 0; i < maxSets; i++) {
                descriptorSets[i] = pSets.get(i);
            }
        } finally {
            MemoryUtil.memFree(layouts);
            MemoryUtil.memFree(pSets);
        }
    }

    /**
     * Resets the write offsets for the given frame. Call at frame start.
     */
    public void resetFrame(int frameIndex) {
        vertexOffsets[frameIndex] = 0;
        uniformOffsets[frameIndex] = 0;
        drawCallsThisFrame = 0;
        vertexBytesThisFrame = 0;
        descriptorSetIndex = 0; // Reset per-draw descriptor set allocation
        loggedVertexOverflow = false;
        loggedUniformOverflow = false;
        loggedDescriptorOverflow = false;
    }

    /**
     * Uploads vertex data and issues a draw command.
     *
     * @param cmd          Active command buffer (inside a render pass)
     * @param frameIndex   Current frame-in-flight index
     * @param pipeline     Bound pipeline handle
     * @param pipelineLayout Pipeline layout for descriptor binding
     * @param vertexData   Vertex data from MC's BufferBuilder
     * @param vertexCount  Number of vertices
     * @param drawMode     MC's draw mode (7 = QUADS, 4 = TRIANGLES, etc.)
     * @param vertexSize   Bytes per vertex
     */
    public void draw(VkCommandBuffer cmd, int frameIndex,
                     long pipeline, long pipelineLayout,
                     ByteBuffer vertexData, int vertexCount,
                     VertexFormat.Mode mode, int vertexSize) {
        if (vertexCount <= 0 || vertexData == null || vertexData.remaining() == 0) return;

        int dataSize = vertexData.remaining();

        // Check if we have space in the vertex buffer
        if (vertexOffsets[frameIndex] + dataSize > VERTEX_BUFFER_SIZE) {
            if (!loggedVertexOverflow) {
                LOGGER.warn("Vertex buffer overflow frame {} (need {}, have {})",
                        frameIndex, dataSize, VERTEX_BUFFER_SIZE - vertexOffsets[frameIndex]);
                loggedVertexOverflow = true;
            }
            return;
        }

        // Copy vertex data to mapped buffer
        int vbOffset = vertexOffsets[frameIndex];
        MemoryUtil.memCopy(
                MemoryUtil.memAddress(vertexData),
                vertexMapped[frameIndex] + vbOffset,
                dataSize);
        vertexOffsets[frameIndex] += dataSize;

        // Bind vertex buffer
        try (MemoryStack stack = stackPush()) {
            vkCmdBindVertexBuffers(cmd, 0, stack.longs(vertexBuffers[frameIndex]), stack.longs(vbOffset));
        }

        // Draw based on MC's vertex mode.
        // QUADS, TRIANGLE_FAN, and TRIANGLE_STRIP are converted to indexed
        // TRIANGLE_LIST draws via pre-generated index buffers.
        // TRIANGLES uses direct (non-indexed) draws.
        switch (mode) {
            case QUADS -> {
                int quadCount = vertexCount / 4;
                int indexCount = quadCount * 6;
                vkCmdBindIndexBuffer(cmd, quadIndexBuffer, 0, VK_INDEX_TYPE_UINT32);
                vkCmdDrawIndexed(cmd, indexCount, 1, 0, 0, 0);
            }
            case TRIANGLE_FAN -> {
                if (vertexCount > MAX_FAN_VERTICES) {
                    LOGGER.warn("TRIANGLE_FAN vertex count {} exceeds max {}", vertexCount, MAX_FAN_VERTICES);
                    return;
                }
                int indexCount = (vertexCount - 2) * 3;
                vkCmdBindIndexBuffer(cmd, fanIndexBuffer, 0, VK_INDEX_TYPE_UINT16);
                vkCmdDrawIndexed(cmd, indexCount, 1, 0, 0, 0);
            }
            case TRIANGLE_STRIP -> {
                if (vertexCount > MAX_STRIP_VERTICES) {
                    LOGGER.warn("TRIANGLE_STRIP vertex count {} exceeds max {}", vertexCount, MAX_STRIP_VERTICES);
                    return;
                }
                int indexCount = (vertexCount - 2) * 3;
                vkCmdBindIndexBuffer(cmd, stripIndexBuffer, 0, VK_INDEX_TYPE_UINT16);
                vkCmdDrawIndexed(cmd, indexCount, 1, 0, 0, 0);
            }
            default -> {
                // TRIANGLES, LINE_STRIP, DEBUG_LINES, LINES — direct draw
                vkCmdDraw(cmd, vertexCount, 1, 0, 0);
            }
        }

        drawCallsThisFrame++;
        vertexBytesThisFrame += dataSize;
    }

    /**
     * Uploads the MVP matrix + color modulator + fog params as uniforms for the current frame.
     *
     * @return The uniform buffer offset for these uniforms
     */
    public int uploadUniformsLegacy(int frameIndex,
                                    float[] mvpMatrix,
                                    float[] colorModulator,
                                    float[] fogParams,
                                    float[] textureMat) {
        int alignedOffset = align(uniformOffsets[frameIndex], 256);
        int totalSize = SHADERPACK_UBO_SIZE;

        if (alignedOffset + totalSize > UNIFORM_BUFFER_SIZE) {
            if (!loggedUniformOverflow) {
                LOGGER.warn("Uniform buffer overflow frame {} (need {}, have {})",
                        frameIndex, totalSize, UNIFORM_BUFFER_SIZE - alignedOffset);
                loggedUniformOverflow = true;
            }
            return 0;
        }

        long ptr = uniformMapped[frameIndex] + alignedOffset;
        MemoryUtil.memSet(ptr, 0, totalSize);

        for (int i = 0; i < 16 && i < mvpMatrix.length; i++) {
            MemoryUtil.memPutFloat(ptr + i * 4L, mvpMatrix[i]);
        }

        long colorPtr = ptr + 64;
        for (int i = 0; i < 4 && i < colorModulator.length; i++) {
            MemoryUtil.memPutFloat(colorPtr + i * 4L, colorModulator[i]);
        }

        long fogColorPtr = ptr + 80;
        for (int i = 0; i < 4 && i < fogParams.length; i++) {
            MemoryUtil.memPutFloat(fogColorPtr + i * 4L, fogParams[i]);
        }

        long fogRangePtr = ptr + 96;
        MemoryUtil.memPutFloat(fogRangePtr, fogParams.length > 4 ? fogParams[4] : 0.0f);
        MemoryUtil.memPutFloat(fogRangePtr + 4, fogParams.length > 5 ? fogParams[5] : 1000.0f);
        MemoryUtil.memPutFloat(fogRangePtr + 8, 0.0f);
        MemoryUtil.memPutFloat(fogRangePtr + 12, 0.0f);

        long texMatPtr = ptr + 112;
        if (textureMat != null && textureMat.length >= 16) {
            for (int i = 0; i < 16; i++) {
                MemoryUtil.memPutFloat(texMatPtr + i * 4L, textureMat[i]);
            }
        } else {
            for (int i = 0; i < 16; i++) {
                MemoryUtil.memPutFloat(texMatPtr + i * 4L, (i % 5 == 0) ? 1.0f : 0.0f);
            }
        }

        uniformOffsets[frameIndex] = alignedOffset + totalSize;
        return alignedOffset;
    }

    public int uploadUniformsShaderpack(int frameIndex,
                                        float[] modelViewMatrix,
                                        float[] modelViewMatrixInverse,
                                        float[] projectionMatrix,
                                        float[] projectionMatrixInverse,
                                        float[] colorModulator,
                                        float[] fogParams,
                                        float[] textureMat,
                                        float[] chunkOffset) {
        int alignedOffset = align(uniformOffsets[frameIndex], 256); // UBO offset alignment
        int totalSize = SHADERPACK_UBO_SIZE;

        if (alignedOffset + totalSize > UNIFORM_BUFFER_SIZE) {
            if (!loggedUniformOverflow) {
                LOGGER.warn("Uniform buffer overflow frame {} (need {}, have {})",
                        frameIndex, totalSize, UNIFORM_BUFFER_SIZE - alignedOffset);
                loggedUniformOverflow = true;
            }
            return 0;
        }

        long ptr = uniformMapped[frameIndex] + alignedOffset;

        // Zero the full block to keep unspecified shaderpack uniforms deterministic.
        MemoryUtil.memSet(ptr, 0, totalSize);

        // Write ModelView matrix (offset 0)
        long modelViewPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_MODEL_VIEW;
        for (int i = 0; i < 16 && i < modelViewMatrix.length; i++) {
            MemoryUtil.memPutFloat(modelViewPtr + i * 4L, modelViewMatrix[i]);
        }

        // Write ModelView inverse matrix (offset 64)
        long modelViewInvPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_MODEL_VIEW_INV;
        for (int i = 0; i < 16 && i < modelViewMatrixInverse.length; i++) {
            MemoryUtil.memPutFloat(modelViewInvPtr + i * 4L, modelViewMatrixInverse[i]);
        }

        // Write Projection matrix (offset 128)
        long projectionPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_PROJECTION;
        for (int i = 0; i < 16 && i < projectionMatrix.length; i++) {
            MemoryUtil.memPutFloat(projectionPtr + i * 4L, projectionMatrix[i]);
        }

        // Write Projection inverse matrix (offset 192)
        long projectionInvPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_PROJECTION_INV;
        for (int i = 0; i < 16 && i < projectionMatrixInverse.length; i++) {
            MemoryUtil.memPutFloat(projectionInvPtr + i * 4L, projectionMatrixInverse[i]);
        }

        // Write color modulator (offset 928)
        long colorPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_COLOR_MODULATOR;
        for (int i = 0; i < 4 && i < colorModulator.length; i++) {
            MemoryUtil.memPutFloat(colorPtr + i * 4L, colorModulator[i]);
        }

        // Write fog color (offset 880)
        long fogColorPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_FOG_COLOR;
        for (int i = 0; i < 4 && i < fogParams.length; i++) {
            MemoryUtil.memPutFloat(fogColorPtr + i * 4L, fogParams[i]);
        }

        // Write fog params (offset 1072): start, end, density, shape
        long fogRangePtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_FOG_PARAMS;
        MemoryUtil.memPutFloat(fogRangePtr, fogParams.length > 4 ? fogParams[4] : 0.0f);       // FogStart
        MemoryUtil.memPutFloat(fogRangePtr + 4, fogParams.length > 5 ? fogParams[5] : 1000.0f); // FogEnd
        MemoryUtil.memPutFloat(fogRangePtr + 8, 0.0f); // FogDensity
        MemoryUtil.memPutFloat(fogRangePtr + 12, 0.0f); // FogShape

        // Write texture matrix (offset 704)
        long texMatPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_TEXTURE_MATRIX;
        if (textureMat != null && textureMat.length >= 16) {
            for (int i = 0; i < 16; i++) {
                MemoryUtil.memPutFloat(texMatPtr + i * 4L, textureMat[i]);
            }
        } else {
            // Identity matrix when no texture matrix is provided
            for (int i = 0; i < 16; i++) {
                MemoryUtil.memPutFloat(texMatPtr + i * 4L, (i % 5 == 0) ? 1.0f : 0.0f);
            }
        }

        // Write chunk offset (offset 912)
        long chunkOffsetPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_CHUNK_OFFSET;
        if (chunkOffset != null && chunkOffset.length >= 3) {
            MemoryUtil.memPutFloat(chunkOffsetPtr, chunkOffset[0]);
            MemoryUtil.memPutFloat(chunkOffsetPtr + 4, chunkOffset[1]);
            MemoryUtil.memPutFloat(chunkOffsetPtr + 8, chunkOffset[2]);
            MemoryUtil.memPutFloat(chunkOffsetPtr + 12, 0.0f);
        }

        // Safe default alpha test reference (offset 1264)
        MemoryUtil.memPutFloat(ptr + net.vulkanium.render.shader.UniformBridge.OFF_ALPHA_TEST_REF, 0.1f);

        uniformOffsets[frameIndex] = alignedOffset + totalSize;
        return alignedOffset;
    }

    /**
     * Updates a descriptor set for a specific draw call with UBO buffer and texture bindings.
     * Returns the index of the descriptor set used. Call per draw, not once per frame.
     */
    public int updateDescriptorSet(int frameIndex,
                                   long[] textureImageViews,
                                   long[] textureSamplers) {
        if (descriptorSetIndex >= MAX_DESCRIPTOR_SETS_PER_FRAME) {
            if (!loggedDescriptorOverflow) {
                LOGGER.warn("Descriptor set overflow (max {})", MAX_DESCRIPTOR_SETS_PER_FRAME);
                loggedDescriptorOverflow = true;
            }
            return descriptorSetIndex - 1; // Reuse last
        }

        int setIdx = descriptorSetIndex++;

        try (MemoryStack stack = stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(1 + MAX_TEXTURE_BINDINGS, stack);

                // Binding 0: Combined UBO (DYNAMIC — base offset 0, full Vulkanium uniform block)
            VkDescriptorBufferInfo.Buffer uboInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(uniformBuffers[frameIndex])
                    .offset(0)
                    .range(SHADERPACK_UBO_SIZE);
            writes.get(0)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSets[setIdx])
                    .dstBinding(0)
                    .descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC)
                    .pBufferInfo(uboInfo);

                // Bindings 1..N: Texture samplers
                for (int i = 0; i < MAX_TEXTURE_BINDINGS; i++) {
                long imageView = (textureImageViews != null && i < textureImageViews.length)
                    ? textureImageViews[i] : VK_NULL_HANDLE;
                long sampler = (textureSamplers != null && i < textureSamplers.length)
                    ? textureSamplers[i] : VK_NULL_HANDLE;

                VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .imageView(imageView)
                    .sampler(sampler);

                writes.get(i + 1)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSets[setIdx])
                    .dstBinding(i + 1)
                    .descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(imageInfo);
            }

            vkUpdateDescriptorSets(device, writes, null);
        }

        return setIdx;
    }

    /**
     * Binds a specific descriptor set by index with a single dynamic UBO offset.
     */
    public void bindDescriptorSet(VkCommandBuffer cmd, long pipelineLayout,
                                   int setIndex, int uniformOffset) {
        try (MemoryStack stack = stackPush()) {
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout,
                    0, stack.longs(descriptorSets[setIndex]),
                    stack.ints(uniformOffset));
        }
    }

    public int getDrawCallsThisFrame() { return drawCallsThisFrame; }
    public int getVertexBytesThisFrame() { return vertexBytesThisFrame; }

    /** Returns the shared quad index buffer for indexed quad draws. */
    public long getQuadIndexBuffer() { return quadIndexBuffer; }

    /**
     * Increments draw call stats for persistent-VBO draws that bypass the
     * streaming vertex buffer (e.g. terrain chunk draws via MixinVertexBuffer).
     */
    public void incrementDrawStats(int vtxBytes) {
        drawCallsThisFrame++;
        vertexBytesThisFrame += vtxBytes;
    }

    // Direct access for raw draw testing
    public int getVertexOffset(int frameIndex) { return vertexOffsets[frameIndex]; }
    public long getVertexMapped(int frameIndex) { return vertexMapped[frameIndex]; }
    public long getVertexBuffer(int frameIndex) { return vertexBuffers[frameIndex]; }
    public void advanceVertexOffset(int frameIndex, int bytes) { vertexOffsets[frameIndex] += bytes; }

    private static int align(int value, int alignment) {
        return (value + alignment - 1) & ~(alignment - 1);
    }

    public void destroy() {
        if (descriptorPool != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device, descriptorPool, null);
        }
        // Buffers are destroyed by VMA via VulkaniumMemory
    }
}

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

    // ── Shadow parameters (fed from ShadowDirectives) ──
    private float shadowSunPathRotation = 0.0f;
    private float shadowDistance = 128.0f;
    private float shadowIntervalSize = 4.0f;
    private float shadowNearPlane = -100.05f;
    private float shadowFarPlane = 156.0f;
    private float shadowDistanceRenderMul = -1.0f;
    private int shadowMapResolution = 1024;

    // ── Eye brightness smoothing state ──
    private float eyeBrightSmoothBlock = 0.0f;
    private float eyeBrightSmoothSky = 240.0f;  // start at full daylight

    // ── Custom uniform smoothing state (matches Iris SmoothedFloat) ──
    private float smoothedEyeBrightM = 0.5f;       // smooth(eyeBrightness.y/240, 5s, 5s)
    private float smoothedEyeBrightM2 = 1.0f;      // smooth(skyLight>239 ? 1 : 0, 2s, 2s)
    private float smoothedIsEyeInCave = 0.0f;       // 1 - smooth(caveFactor, 6s, 12s)
    private float smoothedCaveFactor = 1.0f;        // internal: smooth target for isEyeInCave
    private float smoothedRainFactor = 0.0f;         // smooth(rainStrength, 3s, 3s)
    private float smoothedFrameTime = 1.0f / 60.0f;  // smooth(frameTime, 5s, 5s)
    private long lastFrameNanoTime = 0L;

    // ── Previous camera position for TAA/motion vectors ──
    private double prevCamX = 0.0, prevCamY = 64.0, prevCamZ = 0.0;

    /**
     * Updates shadow parameters from parsed directives.
     * Call after shadow infrastructure initialization.
     */
    public void setShadowParams(float sunPathRotation, float distance, float intervalSize,
                                float nearPlane, float farPlane, float distRenderMul,
                                int resolution) {
        this.shadowSunPathRotation = sunPathRotation;
        this.shadowDistance = distance;
        this.shadowIntervalSize = intervalSize;
        this.shadowNearPlane = nearPlane;
        this.shadowFarPlane = farPlane;
        this.shadowDistanceRenderMul = distRenderMul;
        this.shadowMapResolution = resolution;
    }

    /**
     * Iris-compatible exponential smoothing: SmoothedFloat behavior.
     * Uses different half-lives for increasing vs decreasing values.
     * factor = exp(-deltaTime / halfLife * ln(2))
     */
    private static float irisSmooth(float current, float target, float halfLifeUp, float halfLifeDown, float dt) {
        float halfLife = target > current ? halfLifeUp : halfLifeDown;
        if (halfLife <= 0.0f) return target;
        float factor = (float) Math.exp(-dt / halfLife * 0.693147f); // ln(2) ≈ 0.693147
        return target + (current - target) * factor;
    }

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
        // IMPORTANT: Shaderpacks (OptiFine/Iris convention) expect ALL projection
        // matrices to use OpenGL [-1,1] depth convention.  Shaders do depth
        // reconstruction like: clip.z = depth * 2.0 - 1.0; view = projectionInverse * clip;
        // If projectionMatrix were [0,1], this math breaks.
        //
        // Vulkanium's MixinMatrix4f produces [0,1] depth (zZeroToOne=true).  We convert
        // the per-draw projection to [-1,1] before uploading, matching Iris exactly.
        // The vertex shader depth remap (injected by the GLSL transformer) converts
        // gl_Position.z back from [-1,1] to [0,1] for Vulkan clip space.
        //
        // Conversion:  m22_gl = 2*m22_vk + 1,  m32_gl = 2*m32_vk
        // Reference: Iris Shaders (LGPL-3.0) — runs on OpenGL where [-1,1] is native.
        if (projectionMatrix.length >= 16) {
            org.joml.Matrix4f perDrawProj = new org.joml.Matrix4f();
            perDrawProj.set(projectionMatrix);
            perDrawProj.m22(2.0f * perDrawProj.m22() + 1.0f);  // Vulkan→OpenGL depth
            perDrawProj.m32(2.0f * perDrawProj.m32());          // Vulkan→OpenGL depth
            float[] glProjArr = new float[16];
            perDrawProj.get(glProjArr);
            long projectionPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_PROJECTION;
            for (int i = 0; i < 16; i++) {
                MemoryUtil.memPutFloat(projectionPtr + i * 4L, glProjArr[i]);
            }

            // Write Projection inverse matrix (offset 192) — inverse of [-1,1] version
            float[] glProjInvArr = new float[16];
            new org.joml.Matrix4f(perDrawProj).invert().get(glProjInvArr);
            long projectionInvPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_PROJECTION_INV;
            for (int i = 0; i < 16; i++) {
                MemoryUtil.memPutFloat(projectionInvPtr + i * 4L, glProjInvArr[i]);
            }
        } else {
            long projectionPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_PROJECTION;
            for (int i = 0; i < 16 && i < projectionMatrix.length; i++) {
                MemoryUtil.memPutFloat(projectionPtr + i * 4L, projectionMatrix[i]);
            }
            long projectionInvPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_PROJECTION_INV;
            for (int i = 0; i < 16 && i < projectionMatrixInverse.length; i++) {
                MemoryUtil.memPutFloat(projectionInvPtr + i * 4L, projectionMatrixInverse[i]);
            }
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

        // ── Sky color (offset 864): vec4(r, g, b, 1) ──
        // Previously never written — shaderpacks reading skyColor got (0,0,0) = black.
        try {
            net.minecraft.client.Minecraft mcSky = net.minecraft.client.Minecraft.getInstance();
            if (mcSky != null && mcSky.level != null && mcSky.gameRenderer != null
                    && mcSky.gameRenderer.getMainCamera() != null) {
                net.minecraft.world.phys.Vec3 camPos = mcSky.gameRenderer.getMainCamera().getPosition();
                float partialTick = net.vulkanium.Vulkanium.getCurrentPartialTick();
                net.minecraft.world.phys.Vec3 sky = mcSky.level.getSkyColor(camPos, partialTick);
                long skyPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_SKY_COLOR;
                MemoryUtil.memPutFloat(skyPtr, (float) sky.x);
                MemoryUtil.memPutFloat(skyPtr + 4, (float) sky.y);
                MemoryUtil.memPutFloat(skyPtr + 8, (float) sky.z);
                MemoryUtil.memPutFloat(skyPtr + 12, 1.0f);
            }
        } catch (Exception ignored) {}

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

        // ── HDR params (offset 1280): vec4(currentColorSpace, hdrEnabled, maxLuminance, exposure) ──
        {
            net.vulkanium.render.hdr.HdrConfig.ColorSpaceTarget cs =
                    net.vulkanium.render.hdr.HdrConfig.getColorSpaceTarget();
            boolean hdrOn = net.vulkanium.render.hdr.HdrConfig.isHdrEnabled();
            float[] meta = net.vulkanium.render.hdr.HdrConfig.getHdrMetadata();
            long hdrPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_HDR_PARAMS;
            MemoryUtil.memPutFloat(hdrPtr, (float) cs.index);
            MemoryUtil.memPutFloat(hdrPtr + 4, hdrOn ? 1.0f : 0.0f);
            MemoryUtil.memPutFloat(hdrPtr + 8, meta[0]);   // maxContentLuminance
            MemoryUtil.memPutFloat(hdrPtr + 12, 1.0f);     // exposure

            long hdrDispPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_HDR_DISPLAY;
            MemoryUtil.memPutFloat(hdrDispPtr, meta[3]);     // whitePointX
            MemoryUtil.memPutFloat(hdrDispPtr + 4, meta[4]); // whitePointY
            MemoryUtil.memPutFloat(hdrDispPtr + 8, meta[2]); // minLuminance
            MemoryUtil.memPutFloat(hdrDispPtr + 12, 0.0f);   // reserved
        }

        // ── Screen size (offset 1024): vec4(viewWidth, viewHeight, 1/w, 1/h) ──
        float screenW, screenH;
        if (net.vulkanium.Vulkanium.getVulkanSwapchain() != null) {
            screenW = (float) net.vulkanium.Vulkanium.getVulkanSwapchain().getWidth();
            screenH = (float) net.vulkanium.Vulkanium.getVulkanSwapchain().getHeight();
        } else {
            screenW = Math.max(net.vulkanium.compat.VRenderSystem.getViewportWidth(), 1);
            screenH = Math.max(net.vulkanium.compat.VRenderSystem.getViewportHeight(), 1);
        }
        if (screenW < 1) screenW = 1;
        if (screenH < 1) screenH = 1;
        long screenPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_SCREEN_SIZE;
        MemoryUtil.memPutFloat(screenPtr, screenW);
        MemoryUtil.memPutFloat(screenPtr + 4, screenH);
        MemoryUtil.memPutFloat(screenPtr + 8, 1.0f / screenW);
        MemoryUtil.memPutFloat(screenPtr + 12, 1.0f / screenH);

        // ── View params (offset 1040): vec4(aspectRatio, near, far, fov) ──
        long viewParamsPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_VIEW_PARAMS;
        MemoryUtil.memPutFloat(viewParamsPtr, screenW / screenH);  // aspectRatio
        MemoryUtil.memPutFloat(viewParamsPtr + 4, 0.05f);          // near (MC default)
        float farPlane = 256.0f; // safe default
        try {
            net.minecraft.client.Minecraft mc0 = net.minecraft.client.Minecraft.getInstance();
            if (mc0 != null && mc0.options != null) {
                farPlane = mc0.options.getEffectiveRenderDistance() * 16.0f;
            }
        } catch (Exception ignored) {}
        MemoryUtil.memPutFloat(viewParamsPtr + 8, farPlane);       // far
        float fov = 70.0f;
        try {
            net.minecraft.client.Minecraft mcFov = net.minecraft.client.Minecraft.getInstance();
            if (mcFov != null && mcFov.options != null) {
                fov = mcFov.options.fov().get().floatValue();
            }
        } catch (Exception ignored) {}
        MemoryUtil.memPutFloat(viewParamsPtr + 12, fov);             // fov from game settings

        // ── Time (offset 1056): vec4(frameTimeCounter, worldTime, frameCounter, sunAngle) ──
        long timePtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_TIME;
        float frameTimeCounter = (System.nanoTime() % 3_600_000_000_000L) / 1_000_000_000.0f;
        float worldTime = 0.0f;
        float skyAngle = 0.0f;
        float sunAngle = 0.0f;
        try {
            net.minecraft.client.Minecraft mc1 = net.minecraft.client.Minecraft.getInstance();
            if (mc1 != null && mc1.level != null) {
                worldTime = mc1.level.getDayTime() % 24000L;
                // Use getTimeOfDay(partialTick) for smooth interpolation, matching Iris
                float partialTick = net.vulkanium.Vulkanium.getCurrentPartialTick();
                skyAngle = mc1.level.getTimeOfDay(partialTick);
                // Iris conversion: skyAngle → sunAngle (0 = noon, 0.5 = midnight)
                sunAngle = skyAngle < 0.75f ? skyAngle + 0.25f : skyAngle - 0.75f;
            }
        } catch (Exception ignored) {}
        MemoryUtil.memPutFloat(timePtr, frameTimeCounter);
        MemoryUtil.memPutFloat(timePtr + 4, worldTime);
        MemoryUtil.memPutFloat(timePtr + 8, (float) net.vulkanium.Vulkanium.getFrameCounter());
        MemoryUtil.memPutFloat(timePtr + 12, sunAngle);

        // ── Camera position (offset 768): vec4(x, y, z, 0) ──
        try {
            net.minecraft.client.Minecraft mc2 = net.minecraft.client.Minecraft.getInstance();
            if (mc2 != null && mc2.gameRenderer != null && mc2.gameRenderer.getMainCamera() != null) {
                net.minecraft.world.phys.Vec3 camPos = mc2.gameRenderer.getMainCamera().getPosition();
                long camPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_CAMERA_POS;
                MemoryUtil.memPutFloat(camPtr, (float) camPos.x);
                MemoryUtil.memPutFloat(camPtr + 4, (float) camPos.y);
                MemoryUtil.memPutFloat(camPtr + 8, (float) camPos.z);
                MemoryUtil.memPutFloat(camPtr + 12, 0.0f);
            }
        } catch (Exception ignored) {}

        // ── Normal matrix (offset 640): mat4 = transpose(inverse(modelView)) ──
        if (modelViewMatrix.length >= 16) {
            org.joml.Matrix4f mv = new org.joml.Matrix4f();
            mv.set(modelViewMatrix);
            org.joml.Matrix4f normalMat = new org.joml.Matrix4f(mv).invert().transpose();
            float[] normalArr = new float[16];
            normalMat.get(normalArr);
            long normalPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_NORMAL_MAT4;
            for (int i = 0; i < 16; i++) {
                MemoryUtil.memPutFloat(normalPtr + i * 4L, normalArr[i]);
            }
        }

        // ── Previous-frame matrices (offsets 256, 320) ──
        {
            org.joml.Matrix4f prevMV = net.vulkanium.compat.VRenderSystem.getPrevWorldRenderModelView();
            org.joml.Matrix4f prevProj = net.vulkanium.compat.VRenderSystem.getPrevWorldRenderProjection();
            float[] prevMVArr = new float[16];
            prevMV.get(prevMVArr);
            long prevMVPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_PREV_MODEL_VIEW;
            for (int i = 0; i < 16; i++) {
                MemoryUtil.memPutFloat(prevMVPtr + i * 4L, prevMVArr[i]);
            }

            // Convert previous projection from Vulkan [0,1] to OpenGL [-1,1]
            // for consistency with the current-frame per-draw projection.
            org.joml.Matrix4f prevProjGL = new org.joml.Matrix4f(prevProj);
            prevProjGL.m22(2.0f * prevProjGL.m22() + 1.0f);
            prevProjGL.m32(2.0f * prevProjGL.m32());
            float[] prevProjArr = new float[16];
            prevProjGL.get(prevProjArr);
            long prevProjPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_PREV_PROJECTION;
            for (int i = 0; i < 16; i++) {
                MemoryUtil.memPutFloat(prevProjPtr + i * 4L, prevProjArr[i]);
            }
        }

        // ── Sun / Moon / Shadow Light positions (offsets 800, 816, 832) ──
        // Match Iris CelestialUniforms: start with (0, y, 0), apply
        // gbufferModelView * rotateY(-90°) * rotateZ(sunPathRotation) * rotateX(skyAngle * 360°)
        {
            // Build celestial rotation matrix matching Iris's getCelestialPosition()
            org.joml.Matrix4f mvMat = net.vulkanium.compat.VRenderSystem.getWorldRenderModelView();
            org.joml.Matrix4f celestial = new org.joml.Matrix4f(mvMat);
            // Same rotations MC applies in renderSky
            celestial.rotate((float) Math.toRadians(-90.0f),  0.0f, 1.0f, 0.0f); // Y(-90°)
            // sunPathRotation from shaderpack
            celestial.rotate((float) Math.toRadians(shadowSunPathRotation), 0.0f, 0.0f, 1.0f); // Z(sunPathRotation)
            celestial.rotate((float) Math.toRadians(skyAngle * 360.0f), 1.0f, 0.0f, 0.0f); // X(skyAngle*360)

            // Sun position: transform (0, 100, 0) by celestial matrix
            org.joml.Vector4f sunPos = new org.joml.Vector4f(0.0f, 100.0f, 0.0f, 0.0f);
            celestial.transform(sunPos);

            long sunPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_SUN_POS;
            MemoryUtil.memPutFloat(sunPtr, sunPos.x);
            MemoryUtil.memPutFloat(sunPtr + 4, sunPos.y);
            MemoryUtil.memPutFloat(sunPtr + 8, sunPos.z);
            MemoryUtil.memPutFloat(sunPtr + 12, 0.0f);

            // Moon position: transform (0, -100, 0)
            org.joml.Vector4f moonPos = new org.joml.Vector4f(0.0f, -100.0f, 0.0f, 0.0f);
            celestial.transform(moonPos);

            long moonPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_MOON_POS;
            MemoryUtil.memPutFloat(moonPtr, moonPos.x);
            MemoryUtil.memPutFloat(moonPtr + 4, moonPos.y);
            MemoryUtil.memPutFloat(moonPtr + 8, moonPos.z);
            MemoryUtil.memPutFloat(moonPtr + 12, 0.0f);

            // Shadow light = sun during day (sunAngle <= 0.5), moon during night
            long shadowPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_SHADOW_LIGHT_POS;
            if (sunAngle <= 0.5f) {
                MemoryUtil.memPutFloat(shadowPtr, sunPos.x);
                MemoryUtil.memPutFloat(shadowPtr + 4, sunPos.y);
                MemoryUtil.memPutFloat(shadowPtr + 8, sunPos.z);
            } else {
                MemoryUtil.memPutFloat(shadowPtr, moonPos.x);
                MemoryUtil.memPutFloat(shadowPtr + 4, moonPos.y);
                MemoryUtil.memPutFloat(shadowPtr + 8, moonPos.z);
            }
            MemoryUtil.memPutFloat(shadowPtr + 12, 0.0f);

            // Up position: (0, 100, 0) transformed by gbufferModelView * rotateY(-90°) only
            // (no skyAngle rotation — matches Iris's getUpPosition())
            org.joml.Matrix4f preCelestial = new org.joml.Matrix4f(mvMat);
            preCelestial.rotate((float) Math.toRadians(-90.0f), 0.0f, 1.0f, 0.0f);
            org.joml.Vector4f upPos = new org.joml.Vector4f(0.0f, 100.0f, 0.0f, 0.0f);
            preCelestial.transform(upPos);

            long upPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_UP_POS;
            MemoryUtil.memPutFloat(upPtr, upPos.x);
            MemoryUtil.memPutFloat(upPtr + 4, upPos.y);
            MemoryUtil.memPutFloat(upPtr + 8, upPos.z);
            MemoryUtil.memPutFloat(upPtr + 12, 0.0f);

            // ── GBuffer ModelView (offset 1312, 1376): per-frame camera-only matrix ──
            // In Iris, gbufferModelView is the per-frame camera matrix (no per-draw
            // celestial/chunk rotations), while gl_ModelViewMatrix is the per-draw
            // matrix. Shaderpacks use gbufferModelView for screen-space calculations
            // (e.g., reconstructing world position from depth). Writing the per-frame
            // snapshot here ensures sky shaders get the correct camera-only matrix
            // for gbufferModelView while still receiving the per-draw matrix via
            // iris_ModelViewMatrix (gl_ModelViewMatrix) at offset 0.
            float[] gbufMV = new float[16];
            mvMat.get(gbufMV);
            float[] gbufMVInv = new float[16];
            new org.joml.Matrix4f(mvMat).invert().get(gbufMVInv);
            long gbufMVPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_GBUFFER_MODEL_VIEW;
            long gbufMVInvPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_GBUFFER_MODEL_VIEW_INV;
            for (int i = 0; i < 16; i++) {
                MemoryUtil.memPutFloat(gbufMVPtr + i * 4L, gbufMV[i]);
                MemoryUtil.memPutFloat(gbufMVInvPtr + i * 4L, gbufMVInv[i]);
            }

            // ── GBuffer Projection (offset 1440, 1504): per-frame camera projection ──
            // Separate from per-draw iris_ProjectionMatrix so that composite shaders
            // can set gl_ProjectionMatrix to identity while gbufferProjection retains
            // the camera projection for ray/depth reconstruction.
            //
            // IMPORTANT: Shaderpacks (OptiFine/Iris convention) expect gbufferProjection
            // to use OpenGL depth conventions [-1,1].  Shaders reconstruct positions via:
            //   vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
            //   vec4 view = gbufferProjectionInverse * clip;
            // The "depth * 2.0 - 1.0" maps Vulkan's [0,1] depth to the [-1,1] range that
            // the inverse projection matrix expects.  Therefore we must supply the OpenGL-
            // convention projection matrix (with [-1,1] depth mapping) so the math works.
            //
            // Vulkanium's MixinMatrix4f produces [0,1] depth (zZeroToOne=true).  We convert
            // back to [-1,1] by adjusting elements [2][2] and [3][2] of the projection matrix:
            //   OpenGL:  m22 = -(far+near)/(far-near),  m32 = -2*near*far/(far-near)
            //   Vulkan:  m22 = -far/(far-near),          m32 = -near*far/(far-near)
            // Conversion: m22_gl = 2*m22_vk + 1,  m32_gl = 2*m32_vk
            //
            // Reference: Iris Shaders (LGPL-3.0) — runs on OpenGL where [-1,1] is native.
            // Vulkanium must convert the Vulkan [0,1] projection back to [-1,1] for the
            // uniform so that shaderpack depth reconstruction formulas work correctly.
            org.joml.Matrix4f projMat = net.vulkanium.compat.VRenderSystem.getWorldRenderProjection();
            // Convert projection from Vulkan [0,1] depth to OpenGL [-1,1] depth convention
            // for the gbufferProjection uniform that shaderpacks expect.
            org.joml.Matrix4f glProjMat = new org.joml.Matrix4f(projMat);
            float m22 = glProjMat.m22();
            float m32 = glProjMat.m32();
            glProjMat.m22(2.0f * m22 + 1.0f);  // Vulkan→OpenGL: m22_gl = 2*m22_vk + 1
            glProjMat.m32(2.0f * m32);          // Vulkan→OpenGL: m32_gl = 2*m32_vk
            float[] gbufProj = new float[16];
            glProjMat.get(gbufProj);
            float[] gbufProjInv = new float[16];
            new org.joml.Matrix4f(glProjMat).invert().get(gbufProjInv);
            long gbufProjPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_GBUFFER_PROJECTION;
            long gbufProjInvPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_GBUFFER_PROJECTION_INV;
            for (int i = 0; i < 16; i++) {
                MemoryUtil.memPutFloat(gbufProjPtr + i * 4L, gbufProj[i]);
                MemoryUtil.memPutFloat(gbufProjInvPtr + i * 4L, gbufProjInv[i]);
            }
        }

        // ── Shadow matrices (offsets 384, 448, 512, 576) ──
        // Use the EXACT shadow matrices that were used during shadow map rendering.
        // Recomputing from parameters risks subtle mismatches (timing, precision)
        // that cause shadow depth comparison failures → everything appears in shadow.
        //
        // The shadow projection matrix stored in ShadowRenderer.PROJECTION uses
        // Vulkan [0,1] depth (from ShadowMatrices.createOrthoMatrix with zZeroToOne=true).
        // However, shaders expect the shadowProjection uniform in OpenGL [-1,1]
        // convention for shadow coordinate reconstruction (same as gbufferProjection).
        // We convert the uniform value to [-1,1] while keeping the actual rendering
        // pipeline using [0,1] depth.
        //
        // Reference: Iris Shaders (LGPL-3.0) — shadow matrices are natively [-1,1].
        {
            org.joml.Matrix4f shadowMV = new org.joml.Matrix4f(
                    net.vulkanium.render.shadow.ShadowRenderer.MODELVIEW);
            org.joml.Matrix4f shadowProj = new org.joml.Matrix4f(
                    net.vulkanium.render.shadow.ShadowRenderer.PROJECTION);

            // Convert shadow projection from Vulkan [0,1] to OpenGL [-1,1] for the
            // uniform, so shaderpack shadow coordinate reconstruction works correctly.
            org.joml.Matrix4f glShadowProj = new org.joml.Matrix4f(shadowProj);
            float sm22 = glShadowProj.m22();
            float sm32 = glShadowProj.m32();
            glShadowProj.m22(2.0f * sm22 + 1.0f);  // Vulkan→OpenGL depth
            glShadowProj.m32(2.0f * sm32);          // Vulkan→OpenGL depth

            float[] sMV = new float[16], sP = new float[16];
            shadowMV.get(sMV);
            glShadowProj.get(sP);
            float[] sMVInv = new float[16], sPInv = new float[16];
            new org.joml.Matrix4f(shadowMV).invert().get(sMVInv);
            new org.joml.Matrix4f(glShadowProj).invert().get(sPInv);

            long smvPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_SHADOW_MODEL_VIEW;
            long spPtr  = ptr + net.vulkanium.render.shader.UniformBridge.OFF_SHADOW_PROJECTION;
            long smviPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_SHADOW_MODEL_VIEW_INV;
            long spiPtr  = ptr + net.vulkanium.render.shader.UniformBridge.OFF_SHADOW_PROJECTION_INV;
            for (int i = 0; i < 16; i++) {
                MemoryUtil.memPutFloat(smvPtr + i * 4L, sMV[i]);
                MemoryUtil.memPutFloat(spPtr + i * 4L, sP[i]);
                MemoryUtil.memPutFloat(smviPtr + i * 4L, sMVInv[i]);
                MemoryUtil.memPutFloat(spiPtr + i * 4L, sPInv[i]);
            }

            // Shadow params: vec4(shadowMapResolution, shadowDistance, distanceRenderMul, 0)
            long spParamsPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_SHADOW_PARAMS;
            MemoryUtil.memPutFloat(spParamsPtr, (float) shadowMapResolution);
            MemoryUtil.memPutFloat(spParamsPtr + 4, shadowDistance);
            MemoryUtil.memPutFloat(spParamsPtr + 8, shadowDistanceRenderMul);
            MemoryUtil.memPutFloat(spParamsPtr + 12, 0.0f);
        }

        // ── Weather (offset 1088): vec4(rainStrength, wetness, thunderStrength, 0) ──
        try {
            net.minecraft.client.Minecraft mc3 = net.minecraft.client.Minecraft.getInstance();
            if (mc3 != null && mc3.level != null) {
                float rain = mc3.level.getRainLevel(1.0f);
                float thunder = mc3.level.getThunderLevel(1.0f);
                long weatherPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_WEATHER;
                MemoryUtil.memPutFloat(weatherPtr, rain);
                MemoryUtil.memPutFloat(weatherPtr + 4, rain);       // wetness ≈ rain (simplified)
                MemoryUtil.memPutFloat(weatherPtr + 8, thunder);
                MemoryUtil.memPutFloat(weatherPtr + 12, 0.0f);
            }
        } catch (Exception ignored) {}

        // ── Eye Brightness (offset 1120): vec4(blockLight, skyLight, blockSmooth, skySmooth) ──
        // Match Iris: use LightLayer.BLOCK and LightLayer.SKY at camera eye position,
        // multiplied by 16 to get 0-240 range. Smoothed values use exponential decay.
        try {
            net.minecraft.client.Minecraft mc4 = net.minecraft.client.Minecraft.getInstance();
            if (mc4 != null && mc4.player != null && mc4.level != null) {
                net.minecraft.world.phys.Vec3 feet = mc4.player.position();
                net.minecraft.core.BlockPos eyePos = net.minecraft.core.BlockPos.containing(
                        feet.x, mc4.player.getEyeY(), feet.z);
                int blockLight = mc4.level.getBrightness(
                        net.minecraft.world.level.LightLayer.BLOCK, eyePos);
                int skyLightVal = mc4.level.getBrightness(
                        net.minecraft.world.level.LightLayer.SKY, eyePos);
                float blockF = blockLight * 16.0f;
                float skyF = skyLightVal * 16.0f;

                // Simple exponential smoothing (half-life ~1s at 60fps)
                float alpha = 0.03f;
                eyeBrightSmoothBlock = eyeBrightSmoothBlock + (blockF - eyeBrightSmoothBlock) * alpha;
                eyeBrightSmoothSky = eyeBrightSmoothSky + (skyF - eyeBrightSmoothSky) * alpha;

                long eyePtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_EYE_BRIGHTNESS;
                MemoryUtil.memPutFloat(eyePtr, blockF);
                MemoryUtil.memPutFloat(eyePtr + 4, skyF);
                MemoryUtil.memPutFloat(eyePtr + 8, eyeBrightSmoothBlock);
                MemoryUtil.memPutFloat(eyePtr + 12, eyeBrightSmoothSky);
            }
        } catch (Exception ignored) {}

        // ── World State (offset 1136): vec4(moonPhase, isEyeInWater, biomeTemp, biomeRainfall) ──
        try {
            net.minecraft.client.Minecraft mc5 = net.minecraft.client.Minecraft.getInstance();
            if (mc5 != null && mc5.level != null) {
                int moonPhase = mc5.level.getMoonPhase();
                int isEyeInWater = 0;
                if (mc5.gameRenderer != null && mc5.gameRenderer.getMainCamera() != null) {
                    isEyeInWater = mc5.gameRenderer.getMainCamera().getFluidInCamera()
                            != net.minecraft.world.level.material.FogType.NONE ? 1 : 0;
                }
                long worldPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_WORLD_STATE;
                MemoryUtil.memPutFloat(worldPtr, (float) moonPhase);
                MemoryUtil.memPutFloat(worldPtr + 4, (float) isEyeInWater);
                MemoryUtil.memPutFloat(worldPtr + 8, 0.5f);   // biomeTemp (default temperate)
                MemoryUtil.memPutFloat(worldPtr + 12, 0.5f);  // biomeRainfall (default moderate)
            }
        } catch (Exception ignored) {}

        // ── Previous camera position (offset 784): vec4(x, y, z, 0) ──
        // Use stored previous frame's camera position for TAA/motion vectors.
        {
            long prevCamPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_PREV_CAMERA_POS;
            MemoryUtil.memPutFloat(prevCamPtr, (float) prevCamX);
            MemoryUtil.memPutFloat(prevCamPtr + 4, (float) prevCamY);
            MemoryUtil.memPutFloat(prevCamPtr + 8, (float) prevCamZ);
            MemoryUtil.memPutFloat(prevCamPtr + 12, 0.0f);
        }
        // Update stored previous camera position for next frame
        try {
            net.minecraft.client.Minecraft mc6 = net.minecraft.client.Minecraft.getInstance();
            if (mc6 != null && mc6.gameRenderer != null && mc6.gameRenderer.getMainCamera() != null) {
                net.minecraft.world.phys.Vec3 camPos = mc6.gameRenderer.getMainCamera().getPosition();
                prevCamX = camPos.x;
                prevCamY = camPos.y;
                prevCamZ = camPos.z;
            }
        } catch (Exception ignored) {}

        // ── Player State (offset 1104): vec4(nightVision, blindness, darknessFactor, playerMood) ──
        // Match Iris CommonUniforms: nightVision from MobEffects.NIGHT_VISION,
        // blindness from MobEffects.BLINDNESS, darknessFactor from DARKNESS effect.
        try {
            net.minecraft.client.Minecraft mc7 = net.minecraft.client.Minecraft.getInstance();
            if (mc7 != null && mc7.player != null) {
                float nightVision = 0.0f;
                float blindness = 0.0f;
                float darknessFactor = 0.0f;

                // Night vision effect strength
                net.minecraft.world.effect.MobEffectInstance nvEffect =
                        mc7.player.getEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION);
                if (nvEffect != null) {
                    float partialTick = net.vulkanium.Vulkanium.getCurrentPartialTick();
                    nightVision = net.minecraft.client.renderer.GameRenderer
                            .getNightVisionScale(mc7.player, partialTick);
                }

                // Blindness
                if (mc7.player.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)) {
                    blindness = 1.0f;
                }

                // Darkness
                if (mc7.player.hasEffect(net.minecraft.world.effect.MobEffects.DARKNESS)) {
                    darknessFactor = 1.0f;
                }

                long playerPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_PLAYER_STATE;
                MemoryUtil.memPutFloat(playerPtr, nightVision);
                MemoryUtil.memPutFloat(playerPtr + 4, blindness);
                MemoryUtil.memPutFloat(playerPtr + 8, darknessFactor);
                MemoryUtil.memPutFloat(playerPtr + 12, 0.0f); // playerMood (placeholder)
            }
        } catch (Exception ignored) {}

        // ── Depth Params (offset 1152): vec4(centerDepthSmooth, near, far, 0) ──
        // centerDepthSmooth should be read from the depth buffer center texel;
        // for now use 1.0 (far plane) as a safe default so auto-exposure doesn't break.
        {
            long depthPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_DEPTH_PARAMS;
            MemoryUtil.memPutFloat(depthPtr, 1.0f);       // centerDepthSmooth (far plane default)
            MemoryUtil.memPutFloat(depthPtr + 4, 0.05f);   // near
            MemoryUtil.memPutFloat(depthPtr + 8, farPlane); // far
            MemoryUtil.memPutFloat(depthPtr + 12, 0.0f);
        }

        // ── Blocklight Color (offset 1200): vec4(r, g, b, 1) ──
        // Warm torch-light color matching Iris defaults.
        {
            long blcPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_BLOCKLIGHT_COLOR;
            MemoryUtil.memPutFloat(blcPtr, 1.0f);
            MemoryUtil.memPutFloat(blcPtr + 4, 0.7f);
            MemoryUtil.memPutFloat(blcPtr + 8, 0.4f);
            MemoryUtil.memPutFloat(blcPtr + 12, 1.0f);
        }

        // ═══════════════════════════════════════════════════════════════
        //  Extended Custom Uniforms — filling former padding slots
        // ═══════════════════════════════════════════════════════════════

        // Compute real delta time for smoothing and frameTime uniform
        long nowNano = System.nanoTime();
        float deltaTime;
        if (lastFrameNanoTime == 0L) {
            deltaTime = 1.0f / 60.0f;
            lastFrameNanoTime = nowNano;
        } else {
            deltaTime = (nowNano - lastFrameNanoTime) / 1_000_000_000.0f;
            deltaTime = Math.max(0.0001f, Math.min(deltaTime, 1.0f)); // clamp to [0.1ms, 1s]
            lastFrameNanoTime = nowNano;
        }

        // ── Custom A (offset 944): vec4(screenBrightness, eyeAltitude, worldDay, darknessLightFactor) ──
        {
            float screenBrightness = 0.5f;
            float eyeAltitude = 64.0f;
            float worldDayF = 0.0f;
            float darknessLightFactor = 0.0f;
            try {
                net.minecraft.client.Minecraft mc8 = net.minecraft.client.Minecraft.getInstance();
                if (mc8 != null) {
                    // screenBrightness = game gamma setting (Options > Video > Brightness)
                    if (mc8.options != null) {
                        screenBrightness = (float) mc8.options.gamma().get().doubleValue();
                    }
                    // eyeAltitude = camera Y coordinate
                    if (mc8.gameRenderer != null && mc8.gameRenderer.getMainCamera() != null) {
                        eyeAltitude = (float) mc8.gameRenderer.getMainCamera().getPosition().y;
                    }
                    // worldDay = total day count
                    if (mc8.level != null) {
                        worldDayF = (float) (mc8.level.getDayTime() / 24000L);
                    }
                    // darknessLightFactor: captures the darkness effect's light suppression
                    // In Iris this comes from CapturedRenderingState; approximate from effect
                    if (mc8.player != null && mc8.player.hasEffect(net.minecraft.world.effect.MobEffects.DARKNESS)) {
                        // Darkness pulsates — compute from effect duration
                        net.minecraft.world.effect.MobEffectInstance darkEffect =
                                mc8.player.getEffect(net.minecraft.world.effect.MobEffects.DARKNESS);
                        if (darkEffect != null) {
                            float partialTick = net.vulkanium.Vulkanium.getCurrentPartialTick();
                            float effectTicks = (float) darkEffect.getDuration() - partialTick;
                            // Pulsating factor similar to vanilla's darkness calculations
                            darknessLightFactor = Math.max(0.0f,
                                    (float) Math.cos((effectTicks * Math.PI / 40.0f)) * 0.5f + 0.5f);
                        }
                    }
                }
            } catch (Exception ignored) {}
            long caPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_CUSTOM_A;
            MemoryUtil.memPutFloat(caPtr, screenBrightness);
            MemoryUtil.memPutFloat(caPtr + 4, eyeAltitude);
            MemoryUtil.memPutFloat(caPtr + 8, worldDayF);
            MemoryUtil.memPutFloat(caPtr + 12, darknessLightFactor);
        }

        // ── Custom B (offset 960): vec4(reserved, isEyeInCave, eyeBrightnessM, eyeBrightnessM2) ──
        // These require CPU-side exponential smoothing matching Iris SmoothedFloat.
        {
            float rawEyeBrightMTarget = 0.5f;
            float rawEyeBrightM2Target = 1.0f;
            float rawCaveFactorTarget = 1.0f; // 1.0 = surface, low = cave
            try {
                net.minecraft.client.Minecraft mc9 = net.minecraft.client.Minecraft.getInstance();
                if (mc9 != null && mc9.player != null && mc9.level != null) {
                    net.minecraft.world.phys.Vec3 feet = mc9.player.position();
                    net.minecraft.core.BlockPos eyePos = net.minecraft.core.BlockPos.containing(
                            feet.x, mc9.player.getEyeY(), feet.z);
                    int skyL = mc9.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, eyePos);
                    float skyLightNorm = (skyL * 16.0f) / 240.0f;

                    rawEyeBrightMTarget = skyLightNorm;
                    rawEyeBrightM2Target = (skyL * 16.0f) > 239.0f ? 1.0f : 0.0f;

                    // Cave factor: if eyeAltitude < 5 → use sky brightness, else 1.0 (surface)
                    float eyeY = (float) mc9.gameRenderer.getMainCamera().getPosition().y;
                    int isInWater = mc9.gameRenderer.getMainCamera().getFluidInCamera()
                            != net.minecraft.world.level.material.FogType.NONE ? 1 : 0;
                    if (isInWater == 0) {
                        rawCaveFactorTarget = eyeY < 5.0f ? skyLightNorm : 1.0f;
                    } else {
                        rawCaveFactorTarget = 1.0f; // not in cave when underwater
                    }
                }
            } catch (Exception ignored) {}

            // Apply Iris-compatible exponential smoothing: factor = exp(-dt / halfLife * ln2)
            smoothedEyeBrightM = irisSmooth(smoothedEyeBrightM, rawEyeBrightMTarget, 5.0f, 5.0f, deltaTime);
            smoothedEyeBrightM2 = irisSmooth(smoothedEyeBrightM2, rawEyeBrightM2Target, 2.0f, 2.0f, deltaTime);
            smoothedCaveFactor = irisSmooth(smoothedCaveFactor, rawCaveFactorTarget, 6.0f, 12.0f, deltaTime);
            smoothedIsEyeInCave = 1.0f - smoothedCaveFactor;

            long cbPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_CUSTOM_B;
            MemoryUtil.memPutFloat(cbPtr, 0.0f); // reserved
            MemoryUtil.memPutFloat(cbPtr + 4, smoothedIsEyeInCave);
            MemoryUtil.memPutFloat(cbPtr + 8, smoothedEyeBrightM);
            MemoryUtil.memPutFloat(cbPtr + 12, smoothedEyeBrightM2);
        }

        // ── Custom C (offset 976): vec4(rainFactor, frameTimeSmooth, maxBlindnessDarkness, frameTime) ──
        {
            float rawRain = 0.0f;
            float blindnessVal = 0.0f;
            float darknessVal = 0.0f;
            try {
                net.minecraft.client.Minecraft mc10 = net.minecraft.client.Minecraft.getInstance();
                if (mc10 != null && mc10.level != null) {
                    rawRain = mc10.level.getRainLevel(net.vulkanium.Vulkanium.getCurrentPartialTick());
                }
                if (mc10 != null && mc10.player != null) {
                    if (mc10.player.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)) {
                        blindnessVal = 1.0f;
                    }
                    if (mc10.player.hasEffect(net.minecraft.world.effect.MobEffects.DARKNESS)) {
                        darknessVal = 1.0f;
                    }
                }
            } catch (Exception ignored) {}

            smoothedRainFactor = irisSmooth(smoothedRainFactor, rawRain, 3.0f, 3.0f, deltaTime);
            smoothedFrameTime = irisSmooth(smoothedFrameTime, deltaTime, 5.0f, 5.0f, deltaTime);

            long ccPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_CUSTOM_C;
            MemoryUtil.memPutFloat(ccPtr, smoothedRainFactor);
            MemoryUtil.memPutFloat(ccPtr + 4, smoothedFrameTime);
            MemoryUtil.memPutFloat(ccPtr + 8, Math.max(blindnessVal, darknessVal));
            MemoryUtil.memPutFloat(ccPtr + 12, deltaTime); // real frame delta time
        }

        // ── Camera Position Integer (offset 992): vec4(floor(x), floor(y), floor(z), 0) ──
        // ── Prev Camera Position Integer (offset 1008): vec4(floor(prevX), floor(prevY), floor(prevZ), 0) ──
        try {
            net.minecraft.client.Minecraft mc11 = net.minecraft.client.Minecraft.getInstance();
            if (mc11 != null && mc11.gameRenderer != null && mc11.gameRenderer.getMainCamera() != null) {
                net.minecraft.world.phys.Vec3 camPos = mc11.gameRenderer.getMainCamera().getPosition();
                long ciPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_CAMERA_POS_INT;
                MemoryUtil.memPutFloat(ciPtr, (float) Math.floor(camPos.x));
                MemoryUtil.memPutFloat(ciPtr + 4, (float) Math.floor(camPos.y));
                MemoryUtil.memPutFloat(ciPtr + 8, (float) Math.floor(camPos.z));
                MemoryUtil.memPutFloat(ciPtr + 12, 0.0f);

                long piPtr = ptr + net.vulkanium.render.shader.UniformBridge.OFF_PREV_CAMERA_POS_INT;
                MemoryUtil.memPutFloat(piPtr, (float) Math.floor(prevCamX));
                MemoryUtil.memPutFloat(piPtr + 4, (float) Math.floor(prevCamY));
                MemoryUtil.memPutFloat(piPtr + 8, (float) Math.floor(prevCamZ));
                MemoryUtil.memPutFloat(piPtr + 12, 0.0f);
            }
        } catch (Exception ignored) {}

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
        return updateDescriptorSet(frameIndex, textureImageViews, textureSamplers, null);
    }

    /**
     * Updates a descriptor set with per-texture image layout overrides.
     * {@code imageLayouts} may be null (defaults to SHADER_READ_ONLY_OPTIMAL for all)
     * or a sparse array where non-zero entries override the default layout.
     * Use VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL for depth/shadow textures.
     */
    public int updateDescriptorSet(int frameIndex,
                                   long[] textureImageViews,
                                   long[] textureSamplers,
                                   int[] imageLayouts) {
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

                // Use per-texture layout if provided, otherwise default to SHADER_READ_ONLY
                int layout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
                if (imageLayouts != null && i < imageLayouts.length && imageLayouts[i] != 0) {
                    layout = imageLayouts[i];
                }

                VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(layout)
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

    /** Returns the VkBuffer handle for the per-frame uniform buffer. */
    public long getUniformBuffer(int frameIndex) { return uniformBuffers[frameIndex]; }

    /** Returns the range (in bytes) of a single shaderpack UBO upload. */
    public int getUniformBufferRange() { return SHADERPACK_UBO_SIZE; }

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

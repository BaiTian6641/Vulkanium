package net.vulkanium.render.perf;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * GPU-side frustum culling using compute shaders.
 *
 * <p>Moves frustum culling from the CPU to the GPU, reducing CPU work and
 * enabling culling of much larger section counts. The compute shader reads
 * section bounding boxes from an SSBO, tests them against frustum planes,
 * and writes visible section indices to an indirect draw command buffer.</p>
 *
 * <h3>Pipeline</h3>
 * <pre>
 *   Input:   SSBO[N] of { vec4 minBounds, vec4 maxBounds, uint drawParams[5] }
 *   Uniform: Frustum planes (6 × vec4) + camera position
 *   Output:  Indirect draw buffer + atomic draw count
 * </pre>
 *
 * <h3>Two-Phase Culling</h3>
 * <ol>
 *   <li><b>Phase 1 — Frustum cull:</b> Test each section AABB against 6 frustum planes.
 *       Write surviving sections to indirect draw buffer.</li>
 *   <li><b>Phase 2 — Occlusion cull (optional):</b> Use previous frame's HiZ pyramid
 *       to discard sections hidden behind large geometry. Reduces overdraw
 *       significantly in complex scenes.</li>
 * </ol>
 *
 * <h3>Indirect Drawing</h3>
 * <p>The output buffer contains {@code VkDrawIndirectCommand} structs. The graphics
 * pipeline then uses {@code vkCmdDrawIndirect} with the count from the atomic counter,
 * eliminating the CPU→GPU round-trip for draw call counts.</p>
 */
public class GPUFrustumCuller {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/GPUCull");

    /** Maximum sections that can be tested per dispatch */
    public static final int MAX_SECTIONS = 65536;

    /** Workgroup size (matches frustum_cull.comp) */
    public static final int WORKGROUP_SIZE = 256;

    // ── Vulkan objects ──
    private final long device;
    private long computePipeline = VK_NULL_HANDLE;
    private long pipelineLayout = VK_NULL_HANDLE;
    private long descriptorSetLayout = VK_NULL_HANDLE;

    /** SSBO for section bounding boxes + draw params */
    private long sectionDataBuffer = VK_NULL_HANDLE;
    private long sectionDataAllocation = VK_NULL_HANDLE;

    /** Indirect draw command buffer (output) */
    private long indirectDrawBuffer = VK_NULL_HANDLE;
    private long indirectDrawAllocation = VK_NULL_HANDLE;

    /** Atomic draw count buffer */
    private long drawCountBuffer = VK_NULL_HANDLE;
    private long drawCountAllocation = VK_NULL_HANDLE;

    /** UBO for frustum planes + camera */
    private long frustumUBO = VK_NULL_HANDLE;
    private long frustumUBOAllocation = VK_NULL_HANDLE;

    /** Descriptor set */
    private long descriptorSet = VK_NULL_HANDLE;

    // ── State ──
    private int sectionCount = 0;
    private boolean enabled = true;

    // ── Stats ──
    private int visibleSections = 0;
    private int culledSections = 0;

    public GPUFrustumCuller(long device) {
        this.device = device;
    }

    /**
     * Creates the compute pipeline and allocates buffers.
     *
     * @param allocator       VMA allocator
     * @param computeModule   SPIR-V module for frustum_cull.comp
     */
    public void create(long allocator, long computeModule) {
        createDescriptorSetLayout();
        createPipelineLayout();
        createComputePipeline(computeModule);
        allocateBuffers(allocator);
        LOGGER.info("GPU frustum culler created (max {} sections)", MAX_SECTIONS);
    }

    private void createDescriptorSetLayout() {
        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(4, stack);

            // Binding 0: UBO (frustum planes + camera) — 112 bytes
            bindings.get(0)
                    .binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            // Binding 1: SSBO input (section data, read-only)
            bindings.get(1)
                    .binding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            // Binding 2: SSBO output (indirect draw commands, write-only)
            bindings.get(2)
                    .binding(2)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            // Binding 3: SSBO output (atomic draw count)
            bindings.get(3)
                    .binding(3)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo ci = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings);

            LongBuffer pLayout = stack.mallocLong(1);
            int result = vkCreateDescriptorSetLayout(vkDevice, ci, null, pLayout);
            if (result != VK_SUCCESS) throw new RuntimeException("Failed to create cull desc layout: " + result);
            descriptorSetLayout = pLayout.get(0);
        }
    }

    private void createPipelineLayout() {
        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();
        try (MemoryStack stack = stackPush()) {
            VkPipelineLayoutCreateInfo ci = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(descriptorSetLayout));

            LongBuffer pLayout = stack.mallocLong(1);
            int result = vkCreatePipelineLayout(vkDevice, ci, null, pLayout);
            if (result != VK_SUCCESS) throw new RuntimeException("Failed to create cull pipeline layout: " + result);
            pipelineLayout = pLayout.get(0);
        }
    }

    private void createComputePipeline(long computeModule) {
        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();
        try (MemoryStack stack = stackPush()) {
            VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                    .layout(pipelineLayout);
            ci.stage()
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(computeModule)
                    .pName(stack.UTF8("main"));

            LongBuffer pPipeline = stack.mallocLong(1);
            int result = vkCreateComputePipelines(vkDevice, VK_NULL_HANDLE, ci, null, pPipeline);
            if (result != VK_SUCCESS) throw new RuntimeException("Failed to create cull compute pipeline: " + result);
            computePipeline = pPipeline.get(0);
        }
    }

    private void allocateBuffers(long allocator) {
        try (MemoryStack stack = stackPush()) {
            long[] alloc = new long[1]; // reusable output for allocation handle

            // Section data: MAX_SECTIONS × 48 bytes (CPU-visible for upload)
            sectionDataBuffer = createBuffer(stack, allocator, (long) MAX_SECTIONS * 48,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, VMA_MEMORY_USAGE_CPU_TO_GPU, alloc);
            sectionDataAllocation = alloc[0];

            // Indirect draw: MAX_SECTIONS × 20 bytes (VkDrawIndirectCommand)
            indirectDrawBuffer = createBuffer(stack, allocator, (long) MAX_SECTIONS * 20,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT,
                    VMA_MEMORY_USAGE_GPU_ONLY, alloc);
            indirectDrawAllocation = alloc[0];

            // Draw count: 4 bytes
            drawCountBuffer = createBuffer(stack, allocator, 4,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
                    | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VMA_MEMORY_USAGE_GPU_ONLY, alloc);
            drawCountAllocation = alloc[0];

            // Frustum UBO: 112 bytes (host-visible, coherent)
            frustumUBO = createBuffer(stack, allocator, 112,
                    VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, VMA_MEMORY_USAGE_CPU_TO_GPU, alloc);
            frustumUBOAllocation = alloc[0];
        }
    }

    private long sectionDataAllocLong, indirectDrawAllocLong, drawCountAllocLong, frustumUBOAllocLong;

    /**
     * Creates a VMA-backed buffer.
     *
     * @param allocOut single-element array; receives the VMA allocation handle on success
     * @return the VkBuffer handle
     */
    private long createBuffer(MemoryStack stack, long allocator, long size, int usage, int memoryUsage, long[] allocOut) {
        VkBufferCreateInfo bufCI = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(size)
                .usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

        VmaAllocationCreateInfo allocCI = VmaAllocationCreateInfo.calloc(stack)
                .usage(memoryUsage);
        if (memoryUsage == VMA_MEMORY_USAGE_CPU_TO_GPU) {
            allocCI.flags(VMA_ALLOCATION_CREATE_MAPPED_BIT);
        }

        LongBuffer pBuffer = stack.mallocLong(1);
        var pAlloc = stack.mallocPointer(1);
        int result = vmaCreateBuffer(allocator, bufCI, allocCI, pBuffer, pAlloc, null);
        if (result != VK_SUCCESS) throw new RuntimeException("Failed to create GPU cull buffer: " + result);
        allocOut[0] = pAlloc.get(0);
        return pBuffer.get(0);
    }

    /**
     * Updates the section data buffer with current section bounding boxes.
     *
     * @param sectionData Array of [minX, minY, minZ, padding, maxX, maxY, maxZ, padding,
     *                    vertexCount, instanceCount, firstVertex, firstInstance, drawParams...]
     * @param count       Number of sections
     */
    public void updateSectionData(float[] sectionData, int count) {
        this.sectionCount = Math.min(count, MAX_SECTIONS);
        if (sectionDataBuffer == VK_NULL_HANDLE || sectionCount == 0) return;

        long allocator = net.vulkanium.vulkan.memory.VulkanBuffer.getAllocator();
        try (MemoryStack stack = stackPush()) {
            var pData = stack.mallocPointer(1);
            vmaMapMemory(allocator, sectionDataAllocation, pData);
            long ptr = pData.get(0);
            long copySize = (long) sectionCount * 48;
            for (int i = 0; i < Math.min(sectionData.length, (int)(copySize / 4)); i++) {
                MemoryUtil.memPutFloat(ptr + (long) i * 4, sectionData[i]);
            }
            vmaUnmapMemory(allocator, sectionDataAllocation);
        }
    }

    /**
     * Updates the frustum planes UBO.
     *
     * @param frustumPlanes 6 frustum planes as float[24] (4 floats per plane: nx, ny, nz, d)
     * @param cameraX       Camera X
     * @param cameraY       Camera Y
     * @param cameraZ       Camera Z
     */
    public void updateFrustum(float[] frustumPlanes, float cameraX, float cameraY, float cameraZ) {
        if (frustumUBO == VK_NULL_HANDLE) return;

        long allocator = net.vulkanium.vulkan.memory.VulkanBuffer.getAllocator();
        try (MemoryStack stack = stackPush()) {
            var pData = stack.mallocPointer(1);
            vmaMapMemory(allocator, frustumUBOAllocation, pData);
            long ptr = pData.get(0);
            // Write 6 frustum planes (24 floats = 96 bytes)
            for (int i = 0; i < Math.min(frustumPlanes.length, 24); i++) {
                MemoryUtil.memPutFloat(ptr + (long) i * 4, frustumPlanes[i]);
            }
            // Write camera position (3 floats + padding at offset 96)
            MemoryUtil.memPutFloat(ptr + 96, cameraX);
            MemoryUtil.memPutFloat(ptr + 100, cameraY);
            MemoryUtil.memPutFloat(ptr + 104, cameraZ);
            MemoryUtil.memPutFloat(ptr + 108, 0.0f); // padding
            vmaUnmapMemory(allocator, frustumUBOAllocation);
        }
    }

    /**
     * Dispatches the GPU frustum cull compute shader.
     *
     * @param commandBuffer Active command buffer
     */
    public void dispatch(long commandBuffer) {
        if (!enabled || sectionCount == 0) return;

        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                net.vulkanium.core.VulkaniumDevice.getGlobalDevice());

        try (MemoryStack stack = stackPush()) {
            // Reset draw count to 0
            vkCmdFillBuffer(cmd, drawCountBuffer, 0, 4, 0);

            // Barrier: fill → compute read
            VkMemoryBarrier.Buffer fillBarrier = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, fillBarrier, null, null);

            // Bind compute pipeline + descriptors
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, computePipeline);
            if (descriptorSet != VK_NULL_HANDLE) {
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                        pipelineLayout, 0, stack.longs(descriptorSet), null);
            }

            // Dispatch
            int groupCount = (sectionCount + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE;
            vkCmdDispatch(cmd, groupCount, 1, 1);

            // Barrier: compute write → indirect draw read
            VkMemoryBarrier.Buffer computeBarrier = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
            vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
                    0, computeBarrier, null, null);
        }
    }

    /**
     * Returns the indirect draw buffer for use with vkCmdDrawIndirect.
     */
    public long getIndirectDrawBuffer() { return indirectDrawBuffer; }

    /**
     * Returns the draw count buffer for use with vkCmdDrawIndirectCount.
     */
    public long getDrawCountBuffer() { return drawCountBuffer; }

    public int getSectionCount() { return sectionCount; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }

    // ── Lifecycle ──

    public void destroy(long allocator) {
        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();
        if (computePipeline != VK_NULL_HANDLE) vkDestroyPipeline(vkDevice, computePipeline, null);
        if (pipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(vkDevice, pipelineLayout, null);
        if (descriptorSetLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(vkDevice, descriptorSetLayout, null);

        // Free VMA-backed buffers
        if (sectionDataBuffer != VK_NULL_HANDLE) vmaDestroyBuffer(allocator, sectionDataBuffer, sectionDataAllocation);
        if (indirectDrawBuffer != VK_NULL_HANDLE) vmaDestroyBuffer(allocator, indirectDrawBuffer, indirectDrawAllocation);
        if (drawCountBuffer != VK_NULL_HANDLE) vmaDestroyBuffer(allocator, drawCountBuffer, drawCountAllocation);
        if (frustumUBO != VK_NULL_HANDLE) vmaDestroyBuffer(allocator, frustumUBO, frustumUBOAllocation);

        LOGGER.debug("GPU frustum culler destroyed");
    }
}

package net.vulkanium.render.perf;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * GPU-based weighted blended order-independent transparency (WBOIT) sorter.
 *
 * <p>Translucent geometry (water, stained glass, particles) normally requires
 * back-to-front sorting on the CPU. This compute shader approach implements
 * per-pixel weighted blended OIT, eliminating the need for CPU sorting entirely.</p>
 *
 * <h3>Algorithm: Weighted Blended OIT (McGuire & Bavoil 2013)</h3>
 * <ol>
 *   <li><b>Accumulation pass:</b> For each translucent fragment, accumulate
 *       weighted color and alpha into accumulation + revealage render targets</li>
 *   <li><b>Composite pass:</b> Compute shader (or fragment shader) combines
 *       the accumulated values using the weighted blended formula</li>
 * </ol>
 *
 * <p>This avoids the O(n log n) CPU sort entirely and handles overlapping
 * translucent surfaces correctly regardless of draw order.</p>
 *
 * <h3>Render Targets Required</h3>
 * <ul>
 *   <li><b>Accumulation (RGBA16F):</b> Weighted premultiplied color accumulation</li>
 *   <li><b>Revealage (R8):</b> Product of (1 - alpha) for all fragments</li>
 * </ul>
 *
 * <h3>Fallback</h3>
 * <p>If disabled, falls back to traditional per-chunk distance sorting on CPU
 * (Sodium's approach). The fallback uses a compute shader for parallel radix sort
 * of chunk section centroids by camera distance.</p>
 */
public class GPUTranslucentSorter {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/OIT");

    /** Maximum translucent quads per frame for distance-based fallback */
    public static final int MAX_TRANSLUCENT_QUADS = 262144; // 256K quads

    // ── Mode ──
    public enum Mode {
        /** CPU distance sort (Sodium-compatible, no extra render targets) */
        CPU_SORT,
        /** GPU compute radix sort of section distances */
        GPU_DISTANCE_SORT,
        /** Weighted Blended OIT (no sorting needed, 2 extra render targets) */
        WBOIT
    }

    private Mode mode = Mode.GPU_DISTANCE_SORT;

    // ── Vulkan objects ──
    private final long device;

    // -- WBOIT render targets --
    private long accumulationImage = VK_NULL_HANDLE;
    private long accumulationView = VK_NULL_HANDLE;
    private long revealageImage = VK_NULL_HANDLE;
    private long revealageView = VK_NULL_HANDLE;

    // -- GPU distance sort --
    private long sortComputePipeline = VK_NULL_HANDLE;
    private long sortPipelineLayout = VK_NULL_HANDLE;

    /** SSBO for section distances (input) */
    private long distanceBuffer = VK_NULL_HANDLE;
    /** SSBO for sorted section indices (output) */
    private long indexBuffer = VK_NULL_HANDLE;

    /** VMA allocations for WBOIT images */
    private long accumulationAllocation = VK_NULL_HANDLE;
    private long revealageAllocation = VK_NULL_HANDLE;
    private long distanceAllocation = VK_NULL_HANDLE;
    private long indexAllocation = VK_NULL_HANDLE;

    // ── State ──
    private int sectionCount = 0;

    public GPUTranslucentSorter(long device) {
        this.device = device;
    }

    /**
     * Creates resources for the selected OIT mode.
     *
     * @param allocator    VMA allocator
     * @param mode         OIT mode to use
     * @param screenWidth  Render width (for WBOIT targets)
     * @param screenHeight Render height (for WBOIT targets)
     */
    public void create(long allocator, Mode mode, int screenWidth, int screenHeight) {
        this.mode = mode;

        switch (mode) {
            case WBOIT -> createWBOITTargets(allocator, screenWidth, screenHeight);
            case GPU_DISTANCE_SORT -> createGPUSortPipeline(allocator);
            case CPU_SORT -> {} // no GPU resources needed
        }

        LOGGER.info("Translucent sorter created (mode: {})", mode);
    }

    private void createWBOITTargets(long allocator, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();

            // Accumulation image: RGBA16F
            VkImageCreateInfo accumCI = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(VK_FORMAT_R16G16B16A16_SFLOAT)
                    .mipLevels(1).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                            | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            accumCI.extent().width(width).height(height).depth(1);

            VmaAllocationCreateInfo allocCI = VmaAllocationCreateInfo.calloc(stack)
                    .usage(VMA_MEMORY_USAGE_GPU_ONLY);

            LongBuffer pImage = stack.mallocLong(1);
            var pAlloc = stack.mallocPointer(1);

            vmaCreateImage(allocator, accumCI, allocCI, pImage, pAlloc, null);
            accumulationImage = pImage.get(0);
            accumulationAllocation = pAlloc.get(0);

            // Accumulation image view
            VkImageViewCreateInfo viewCI = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(accumulationImage)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK_FORMAT_R16G16B16A16_SFLOAT);
            viewCI.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);

            LongBuffer pView = stack.mallocLong(1);
            vkCreateImageView(vkDevice, viewCI, null, pView);
            accumulationView = pView.get(0);

            // Revealage image: R8_UNORM
            accumCI.format(VK_FORMAT_R8_UNORM);
            vmaCreateImage(allocator, accumCI, allocCI, pImage, pAlloc, null);
            revealageImage = pImage.get(0);
            revealageAllocation = pAlloc.get(0);

            viewCI.image(revealageImage).format(VK_FORMAT_R8_UNORM);
            vkCreateImageView(vkDevice, viewCI, null, pView);
            revealageView = pView.get(0);
        }
    }

    private void createGPUSortPipeline(long allocator) {
        // Allocate distance + index SSBOs for parallel radix sort
        try (MemoryStack stack = stackPush()) {
            long distSize = (long) MAX_TRANSLUCENT_QUADS * 4; // float per section
            long idxSize = (long) MAX_TRANSLUCENT_QUADS * 4;  // uint per section

            VkBufferCreateInfo bufCI = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            VmaAllocationCreateInfo allocCI = VmaAllocationCreateInfo.calloc(stack)
                    .usage(VMA_MEMORY_USAGE_CPU_TO_GPU)
                    .flags(VMA_ALLOCATION_CREATE_MAPPED_BIT);

            LongBuffer pBuf = stack.mallocLong(1);
            var pAlloc = stack.mallocPointer(1);

            bufCI.size(distSize);
            vmaCreateBuffer(allocator, bufCI, allocCI, pBuf, pAlloc, null);
            distanceBuffer = pBuf.get(0);
            distanceAllocation = pAlloc.get(0);

            allocCI.usage(VMA_MEMORY_USAGE_GPU_ONLY);
            bufCI.size(idxSize);
            vmaCreateBuffer(allocator, bufCI, allocCI, pBuf, pAlloc, null);
            indexBuffer = pBuf.get(0);
            indexAllocation = pAlloc.get(0);
        }
        // Compute pipeline creation deferred until SPIR-V module is available
    }

    /**
     * Updates section distances from camera for GPU sort mode.
     *
     * @param distances Camera-to-section-centroid distances
     * @param count     Number of translucent sections
     */
    public void updateDistances(float[] distances, int count) {
        this.sectionCount = count;
        if (mode != Mode.GPU_DISTANCE_SORT || count == 0) return;
        if (distanceBuffer == VK_NULL_HANDLE) return;

        long allocator = net.vulkanium.vulkan.memory.VulkanBuffer.getAllocator();
        try (MemoryStack stack = stackPush()) {
            var pData = stack.mallocPointer(1);
            vmaMapMemory(allocator, distanceAllocation, pData);
            long ptr = pData.get(0);
            int copyCount = Math.min(count, MAX_TRANSLUCENT_QUADS);
            for (int i = 0; i < copyCount; i++) {
                org.lwjgl.system.MemoryUtil.memPutFloat(ptr + (long) i * 4, distances[i]);
            }
            vmaUnmapMemory(allocator, distanceAllocation);
        }
    }

    /**
     * Dispatches the GPU sort or prepares WBOIT for the translucent pass.
     *
     * @param commandBuffer Active command buffer
     */
    public void prepare(long commandBuffer) {
        switch (mode) {
            case GPU_DISTANCE_SORT -> dispatchSort(commandBuffer);
            case WBOIT -> clearWBOITTargets(commandBuffer);
            case CPU_SORT -> {} // nothing to do
        }
    }

    private void dispatchSort(long commandBuffer) {
        if (sectionCount == 0 || sortComputePipeline == VK_NULL_HANDLE) return;

        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                net.vulkanium.core.VulkaniumDevice.getGlobalDevice());

        try (MemoryStack stack = stackPush()) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, sortComputePipeline);
            int groups = (sectionCount + 255) / 256;
            vkCmdDispatch(cmd, groups, 1, 1);

            // Barrier: compute write → vertex/index read
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_INDEX_READ_BIT | VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT);
            vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_VERTEX_INPUT_BIT,
                    0, barrier, null, null);
        }
    }

    private void clearWBOITTargets(long commandBuffer) {
        if (accumulationImage == VK_NULL_HANDLE || revealageImage == VK_NULL_HANDLE) return;

        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer,
                net.vulkanium.core.VulkaniumDevice.getGlobalDevice());

        try (MemoryStack stack = stackPush()) {
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);

            // Clear accumulation to (0,0,0,0)
            VkClearColorValue clearZero = VkClearColorValue.calloc(stack);
            clearZero.float32(0, 0f).float32(1, 0f).float32(2, 0f).float32(3, 0f);
            vkCmdClearColorImage(cmd, accumulationImage,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clearZero, range);

            // Clear revealage to 1.0
            VkClearColorValue clearOne = VkClearColorValue.calloc(stack);
            clearOne.float32(0, 1f);
            vkCmdClearColorImage(cmd, revealageImage,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clearOne, range);
        }
    }

    /**
     * For WBOIT mode: composites the accumulated transparency onto the opaque scene.
     * This is a fullscreen pass after all translucent geometry is rendered.
     *
     * @param commandBuffer Active command buffer
     */
    public void compositeWBOIT(long commandBuffer) {
        if (mode != Mode.WBOIT) return;

        // TODO: Fullscreen triangle pass that reads accumulation + revealage
        // and blends with the opaque scene using the WBOIT formula:
        //   color = accum.rgb / max(accum.a, 1e-5)
        //   alpha = 1.0 - revealage
        //   finalColor = color * alpha + opaqueColor * (1 - alpha)
    }

    // ── Getters ──

    public Mode getMode() { return mode; }
    public long getAccumulationView() { return accumulationView; }
    public long getRevealageView() { return revealageView; }
    public long getSortedIndexBuffer() { return indexBuffer; }
    public int getSectionCount() { return sectionCount; }

    // ── Resize ──

    public void resize(long allocator, int width, int height) {
        if (mode == Mode.WBOIT) {
            // TODO: Destroy old targets, recreate at new size
            createWBOITTargets(allocator, width, height);
        }
    }

    // ── Lifecycle ──

    public void destroy(long allocator) {
        VkDevice vkDevice = net.vulkanium.core.VulkaniumDevice.getGlobalDevice();
        if (sortComputePipeline != VK_NULL_HANDLE) vkDestroyPipeline(vkDevice, sortComputePipeline, null);
        if (sortPipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(vkDevice, sortPipelineLayout, null);

        // TODO: Destroy WBOIT images/views via VMA
        // TODO: Destroy distance/index buffers via VMA

        LOGGER.debug("Translucent sorter destroyed");
    }
}

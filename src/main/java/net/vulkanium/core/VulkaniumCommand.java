package net.vulkanium.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.vulkanium.core.VulkaniumInstance.checkResult;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Command buffer management: allocation, recording helpers, secondary command buffers
 * for multi-threaded recording.
 *
 * <p>Key features:</p>
 * <ul>
 *   <li>Per-frame primary command buffers (one per frame-in-flight)</li>
 *   <li>Secondary command buffer pool for parallel chunk rendering</li>
 *   <li>Reset-per-frame with RESET_COMMAND_BUFFER_BIT pools</li>
 * </ul>
 */
public class VulkaniumCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Command");

    private VkDevice device;
    private int framesInFlight;

    // Per-frame primary command buffers
    private VkCommandBuffer[] primaryCommandBuffers;
    private long graphicsPool;

    /**
     * Allocate per-frame primary command buffers from the graphics pool.
     */
    public void initialize(VulkaniumDevice vulkaniumDevice, VulkaniumQueues queues, int framesInFlight) {
        this.device = vulkaniumDevice.getLogicalDevice();
        this.framesInFlight = framesInFlight;
        this.graphicsPool = queues.getGraphicsCommandPool();

        primaryCommandBuffers = allocateCommandBuffers(graphicsPool, VK_COMMAND_BUFFER_LEVEL_PRIMARY, framesInFlight);

        LOGGER.info("Allocated {} primary command buffers", framesInFlight);
    }

    /**
     * Allocates N command buffers from the given pool.
     */
    public VkCommandBuffer[] allocateCommandBuffers(long pool, int level, int count) {
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(pool)
                    .level(level)
                    .commandBufferCount(count);

            PointerBuffer pBuffers = stack.mallocPointer(count);
            int result = vkAllocateCommandBuffers(device, allocInfo, pBuffers);
            checkResult(result, "Failed to allocate command buffers");

            VkCommandBuffer[] buffers = new VkCommandBuffer[count];
            for (int i = 0; i < count; i++) {
                buffers[i] = new VkCommandBuffer(pBuffers.get(i), device);
            }
            return buffers;
        }
    }

    /**
     * Begins recording a primary command buffer for the given frame index.
     */
    public VkCommandBuffer beginFrame(int frameIndex) {
        VkCommandBuffer cmd = primaryCommandBuffers[frameIndex];

        try (MemoryStack stack = stackPush()) {
            int result = vkResetCommandBuffer(cmd, 0);
            checkResult(result, "Failed to reset command buffer");

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            result = vkBeginCommandBuffer(cmd, beginInfo);
            checkResult(result, "Failed to begin command buffer");
        }

        return cmd;
    }

    /**
     * Ends recording the primary command buffer for the given frame.
     */
    public void endFrame(int frameIndex) {
        int result = vkEndCommandBuffer(primaryCommandBuffers[frameIndex]);
        checkResult(result, "Failed to end command buffer");
    }

    /**
     * Allocates a secondary command buffer for multi-threaded recording.
     */
    public VkCommandBuffer allocateSecondary(long pool) {
        return allocateCommandBuffers(pool, VK_COMMAND_BUFFER_LEVEL_SECONDARY, 1)[0];
    }

    /**
     * Begins a secondary command buffer with inheritance info for render pass continuation.
     */
    public void beginSecondary(VkCommandBuffer cmd, long renderPass, int subpass, long framebuffer) {
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferInheritanceInfo inheritanceInfo = VkCommandBufferInheritanceInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_INHERITANCE_INFO)
                    .renderPass(renderPass)
                    .subpass(subpass)
                    .framebuffer(framebuffer);

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT |
                            VK_COMMAND_BUFFER_USAGE_RENDER_PASS_CONTINUE_BIT)
                    .pInheritanceInfo(inheritanceInfo);

            int result = vkBeginCommandBuffer(cmd, beginInfo);
            checkResult(result, "Failed to begin secondary command buffer");
        }
    }

    // === Pipeline Barriers ===

    /**
     * Begins a single-use command buffer for one-time GPU operations (texture uploads, etc.).
     * Must be paired with {@link #endSingleTimeCommand(VkCommandBuffer)}.
     */
    public VkCommandBuffer beginSingleTimeCommand() {
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(graphicsPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);

            PointerBuffer pBuffer = stack.mallocPointer(1);
            checkResult(vkAllocateCommandBuffers(device, allocInfo, pBuffer), "Failed to allocate single time cmd buf");
            VkCommandBuffer cmd = new VkCommandBuffer(pBuffer.get(0), device);

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            checkResult(vkBeginCommandBuffer(cmd, beginInfo), "Failed to begin single time cmd buf");

            return cmd;
        }
    }

    /**
     * Ends, submits, and waits for a single-time command buffer to complete.
     * The command buffer is freed after execution.
     */
    public void endSingleTimeCommand(VkCommandBuffer cmd) {
        checkResult(vkEndCommandBuffer(cmd), "Failed to end single time cmd buf");

        try (MemoryStack stack = stackPush()) {
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(cmd));

            // We need the graphics queue — get it from VulkaniumQueues
            VkQueue queue = net.vulkanium.Vulkanium.getVulkanQueues().getGraphicsQueue();
            checkResult(vkQueueSubmit(queue, submitInfo, VK_NULL_HANDLE), "Failed to submit single time cmd");
            vkQueueWaitIdle(queue);
        }

        vkFreeCommandBuffers(device, graphicsPool, cmd);
    }

    /**
     * Inserts an image layout transition barrier.
     */
    public static void transitionImageLayout(VkCommandBuffer cmd, long image,
                                             int oldLayout, int newLayout,
                                             int srcAccessMask, int dstAccessMask,
                                             int srcStage, int dstStage,
                                             int aspectMask) {
        try (MemoryStack stack = stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(oldLayout)
                    .newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image)
                    .srcAccessMask(srcAccessMask)
                    .dstAccessMask(dstAccessMask);

            barrier.subresourceRange()
                    .aspectMask(aspectMask)
                    .baseMipLevel(0)
                    .levelCount(VK_REMAINING_MIP_LEVELS)
                    .baseArrayLayer(0)
                    .layerCount(VK_REMAINING_ARRAY_LAYERS);

            vkCmdPipelineBarrier(cmd,
                    srcStage, dstStage,
                    0,
                    null, null, barrier);
        }
    }

    /**
     * Convenience: transition color attachment → shader read.
     */
    public static void transitionToShaderRead(VkCommandBuffer cmd, long image) {
        transitionImageLayout(cmd, image,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT);
    }

    /**
     * Convenience: transition shader read → color attachment.
     */
    public static void transitionToColorAttachment(VkCommandBuffer cmd, long image) {
        transitionImageLayout(cmd, image,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_ACCESS_SHADER_READ_BIT,
                VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT);
    }

    /**
     * Inserts a buffer memory barrier (transfer write → vertex/index read).
     * Used after staging buffer copies to ensure data is visible before draw commands.
     */
    public static void insertBufferMemoryBarrier(VkCommandBuffer cmd) {
        try (MemoryStack stack = stackPush()) {
            VkMemoryBarrier.Buffer memoryBarrier = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK_ACCESS_INDEX_READ_BIT);

            vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_VERTEX_INPUT_BIT,
                    0,
                    memoryBarrier, null, null);
        }
    }

    // === Cleanup ===

    public void destroy() {
        // Command buffers are freed when their pool is destroyed (in VulkaniumQueues)
        primaryCommandBuffers = null;
        device = null;
    }

    // === Getters ===

    public VkCommandBuffer getPrimaryCommandBuffer(int frameIndex) {
        return primaryCommandBuffers[frameIndex];
    }

    public int getFramesInFlight() { return framesInFlight; }
}

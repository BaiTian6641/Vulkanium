package net.vulkanium.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;

import static net.vulkanium.core.VulkaniumInstance.checkResult;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Manages Vulkan queues: graphics, present, transfer, compute.
 *
 * <p>Unlike VulkanMod's scattered queue handling, Vulkanium centralises all
 * queue retrieval and provides per-queue command pool creation for
 * multi-threaded command recording.</p>
 *
 * <p>Features:</p>
 * <ul>
 *   <li>4 queue types with dedicated pools where the hardware supports them</li>
 *   <li>Supports async transfer for streaming chunk data without stalling graphics</li>
 *   <li>Supports async compute for GPU-side frustum culling and translucent sort</li>
 *   <li>Transient command pool for short-lived one-shot commands</li>
 * </ul>
 */
public class VulkaniumQueues {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanium/Queues");

    private VkDevice device;
    private VulkaniumDevice.QueueFamilyIndices indices;

    // Queues
    private VkQueue graphicsQueue;
    private VkQueue presentQueue;
    private VkQueue transferQueue;
    private VkQueue computeQueue;

    // Per-queue-family command pools
    private long graphicsCommandPool;
    private long transferCommandPool;
    private long computeCommandPool;

    // Transient pool for short-lived commands on the graphics queue
    private long transientCommandPool;

    /**
     * Retrieves all queues from the logical device and creates command pools.
     */
    public void initialize(VulkaniumDevice vulkaniumDevice) {
        this.device = vulkaniumDevice.getLogicalDevice();
        this.indices = vulkaniumDevice.getQueueFamilyIndices();

        try (MemoryStack stack = stackPush()) {
            PointerBuffer pQueue = stack.pointers(VK_NULL_HANDLE);

            // Graphics queue
            vkGetDeviceQueue(device, indices.graphicsFamily(), 0, pQueue);
            graphicsQueue = new VkQueue(pQueue.get(0), device);
            LOGGER.info("Graphics queue: family {}", indices.graphicsFamily());

            // Present queue (may be same as graphics)
            vkGetDeviceQueue(device, indices.presentFamily(), 0, pQueue);
            presentQueue = new VkQueue(pQueue.get(0), device);
            LOGGER.info("Present queue: family {}", indices.presentFamily());

            // Transfer queue
            vkGetDeviceQueue(device, indices.transferFamily(), 0, pQueue);
            transferQueue = new VkQueue(pQueue.get(0), device);
            LOGGER.info("Transfer queue: family {} (dedicated: {})",
                    indices.transferFamily(), indices.hasDedicatedTransfer());

            // Compute queue
            vkGetDeviceQueue(device, indices.computeFamily(), 0, pQueue);
            computeQueue = new VkQueue(pQueue.get(0), device);
            LOGGER.info("Compute queue: family {} (dedicated: {})",
                    indices.computeFamily(), indices.hasDedicatedCompute());
        }

        // Create command pools
        graphicsCommandPool = createCommandPool(indices.graphicsFamily(), 0);
        transientCommandPool = createCommandPool(indices.graphicsFamily(), VK_COMMAND_POOL_CREATE_TRANSIENT_BIT);

        transferCommandPool = createCommandPool(indices.transferFamily(), VK_COMMAND_POOL_CREATE_TRANSIENT_BIT);
        computeCommandPool = createCommandPool(indices.computeFamily(), 0);

        LOGGER.info("Command pools created (graphics, transient, transfer, compute)");
    }

    /**
     * Creates a command pool for the given queue family.
     *
     * @param queueFamilyIndex Queue family to create the pool for
     * @param flags            Additional VkCommandPoolCreateFlags (e.g., TRANSIENT_BIT, RESET_COMMAND_BUFFER_BIT)
     * @return Handle to the created command pool
     */
    public long createCommandPool(int queueFamilyIndex, int flags) {
        try (MemoryStack stack = stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .queueFamilyIndex(queueFamilyIndex)
                    .flags(flags | VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);

            long[] pPool = new long[1];
            int result = vkCreateCommandPool(device, poolInfo, null, pPool);
            checkResult(result, "Failed to create command pool");

            return pPool[0];
        }
    }

    // === One-shot command helpers ===

    /**
     * Begins a single-use command buffer on the graphics transient pool.
     * Pair with {@link #endSingleTimeCommand(VkCommandBuffer)}.
     */
    public VkCommandBuffer beginSingleTimeCommand() {
        return beginSingleTimeCommand(transientCommandPool);
    }

    /**
     * Begins a single-use command buffer on the specified pool.
     */
    public VkCommandBuffer beginSingleTimeCommand(long commandPool) {
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandPool(commandPool)
                    .commandBufferCount(1);

            PointerBuffer pCommandBuffer = stack.mallocPointer(1);
            vkAllocateCommandBuffers(device, allocInfo, pCommandBuffer);
            VkCommandBuffer commandBuffer = new VkCommandBuffer(pCommandBuffer.get(0), device);

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            vkBeginCommandBuffer(commandBuffer, beginInfo);
            return commandBuffer;
        }
    }

    /**
     * Ends and submits a single-use command buffer to the graphics queue, then waits for idle.
     */
    public void endSingleTimeCommand(VkCommandBuffer commandBuffer) {
        endSingleTimeCommand(commandBuffer, graphicsQueue, transientCommandPool);
    }

    /**
     * Ends and submits a single-use command buffer to the specified queue.
     */
    public void endSingleTimeCommand(VkCommandBuffer commandBuffer, VkQueue queue, long commandPool) {
        try (MemoryStack stack = stackPush()) {
            vkEndCommandBuffer(commandBuffer);

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(commandBuffer));

            vkQueueSubmit(queue, submitInfo, VK_NULL_HANDLE);
            vkQueueWaitIdle(queue);

            vkFreeCommandBuffers(device, commandPool, commandBuffer);
        }
    }

    /**
     * Submits command buffers to the graphics queue with optional synchronisation.
     */
    public void submitGraphics(VkCommandBuffer[] commandBuffers,
                               long[] waitSemaphores, int[] waitStages,
                               long[] signalSemaphores, long fence) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pCommandBuffers = stack.mallocPointer(commandBuffers.length);
            for (VkCommandBuffer cb : commandBuffers) {
                pCommandBuffers.put(cb);
            }
            pCommandBuffers.flip();

            VkSubmitInfo.Buffer submitInfo = VkSubmitInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(pCommandBuffers);

            if (waitSemaphores != null && waitSemaphores.length > 0) {
                submitInfo.waitSemaphoreCount(waitSemaphores.length);
                submitInfo.pWaitSemaphores(stack.longs(waitSemaphores));
                submitInfo.pWaitDstStageMask(stack.ints(waitStages));
            }

            if (signalSemaphores != null && signalSemaphores.length > 0) {
                submitInfo.pSignalSemaphores(stack.longs(signalSemaphores));
            }

            int result = vkQueueSubmit(graphicsQueue, submitInfo, fence);
            checkResult(result, "Failed to submit to graphics queue");
        }
    }

    /**
     * Submits a transfer command buffer to the dedicated transfer queue.
     */
    public void submitTransfer(VkCommandBuffer commandBuffer, long fence) {
        try (MemoryStack stack = stackPush()) {
            VkSubmitInfo.Buffer submitInfo = VkSubmitInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(commandBuffer));

            int result = vkQueueSubmit(transferQueue, submitInfo, fence);
            checkResult(result, "Failed to submit to transfer queue");
        }
    }

    /**
     * Submits a compute command buffer to the compute queue.
     */
    public void submitCompute(VkCommandBuffer commandBuffer, long fence,
                              long[] waitSemaphores, int[] waitStages,
                              long[] signalSemaphores) {
        try (MemoryStack stack = stackPush()) {
            VkSubmitInfo.Buffer submitInfo = VkSubmitInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(commandBuffer));

            if (waitSemaphores != null && waitSemaphores.length > 0) {
                submitInfo.waitSemaphoreCount(waitSemaphores.length);
                submitInfo.pWaitSemaphores(stack.longs(waitSemaphores));
                submitInfo.pWaitDstStageMask(stack.ints(waitStages));
            }

            if (signalSemaphores != null && signalSemaphores.length > 0) {
                submitInfo.pSignalSemaphores(stack.longs(signalSemaphores));
            }

            int result = vkQueueSubmit(computeQueue, submitInfo, fence);
            checkResult(result, "Failed to submit to compute queue");
        }
    }

    // === Cleanup ===

    public void destroy() {
        if (device == null) return;

        vkDeviceWaitIdle(device);

        destroyPool(graphicsCommandPool);
        destroyPool(transientCommandPool);
        destroyPool(transferCommandPool);
        destroyPool(computeCommandPool);

        graphicsCommandPool = VK_NULL_HANDLE;
        transientCommandPool = VK_NULL_HANDLE;
        transferCommandPool = VK_NULL_HANDLE;
        computeCommandPool = VK_NULL_HANDLE;

        device = null;
    }

    private void destroyPool(long pool) {
        if (pool != VK_NULL_HANDLE) {
            vkDestroyCommandPool(device, pool, null);
        }
    }

    // === Getters ===

    public VkQueue getGraphicsQueue() { return graphicsQueue; }
    public VkQueue getPresentQueue() { return presentQueue; }
    public VkQueue getTransferQueue() { return transferQueue; }
    public VkQueue getComputeQueue() { return computeQueue; }

    public long getGraphicsCommandPool() { return graphicsCommandPool; }
    public long getTransientCommandPool() { return transientCommandPool; }
    public long getTransferCommandPool() { return transferCommandPool; }
    public long getComputeCommandPool() { return computeCommandPool; }

    public VulkaniumDevice.QueueFamilyIndices getIndices() { return indices; }
}
